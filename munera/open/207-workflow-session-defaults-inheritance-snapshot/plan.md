# 207 — Plan

## Approach

Capture the inheritable default session details from the parent **once**, at the
point a workflow run is created, persist the resolved snapshot on the run's
canonical state, and have step config resolution read inherited defaults from
that snapshot instead of re-reading the live parent session.

Architectural anchors (from design):

- **Purity boundary (Decision 6).** `workflow-runtime.core/create-run` stays a
  pure root-state lifecycle op (takes `state`, no ctx reads). The snapshot is
  resolved **impurely by each caller** and passed as already-resolved data via
  a new optional `:inherited-defaults` key in `create-run` `opts`, persisted
  verbatim (like `:parent-session-id`).
- **Single ownership of snapshot derivation (Decisions 7, 7a).**
  `workflow-step-session-config` is the single component that derives snapshots.
  It exposes two functions:
  - `resolve-inherited-defaults-snapshot (ctx parent-session-id) → snapshot` —
    top-level path; performs the live ctx reads the resolver's no-override path
    already uses (`get-session-data` → `:model`/`:prompt-mode`, `all-skills`,
    tool source + `:tool-ids` → `:tool-defs`, `:thinking-level`) **plus two new
    ctx reads it does not have today** — `:speed-mode` and `:effort-override`
    from the parent session (Decision 7 / I1).
  - `effective-config->snapshot (effective-config) → snapshot` — nested path;
    pure projection of an already-resolved effective step-config into the
    snapshot field set, no ctx reads. Shared so the two paths cannot drift.
- **Field-set authority (Decisions 8, 8a).** The snapshot field set is a named
  subset spanning two `session-state/init.clj` authorities — `:model` and
  `:thinking-level` from `model-identity-fields`; `:prompt-mode`, `:speed-mode`,
  `:effort-override`, `:tool-ids`→`:tool-defs`, `:skill-ids`→`:skills` from
  `common-inherited-fields`. Those constants are promoted to public vars (or
  small accessors) so the snapshot references them rather than re-enumerating
  keys; a test asserts the invariant and fails on drift.
- **Resolution sites (Decision 6a).** Exactly three direct `create-run` callers
  resolve/pass the snapshot:
  1. `mutations/canonical_workflows.clj` `create-workflow-run` mutation — holds
     `agent-session-ctx`+`session-id`; resolves via
     `resolve-inherited-defaults-snapshot`. The two upstream `mutate!` callers
     (`workflow/core.clj`, `orchestration.clj` `continue-terminal-run-async!`)
     stay **unchanged**, so continuation captures a fresh snapshot for free
     (Decision 5b).
  2. `psi_tool_workflow.clj` `create-run` op — holds `ctx`+`session-id`;
     resolves via `resolve-inherited-defaults-snapshot`.
  3. `statechart_runtime/delegate.clj` nested create-run — derives the
     delegating step's effective config via `resolve-step-session-config` then
     `effective-config->snapshot` (Decision 3 / 7a). It must **not** call
     `resolve-inherited-defaults-snapshot` (would lose step overrides).
- **Consumption.** `resolve-step-session-config` reads inherited defaults from
  `(:inherited-defaults workflow-run)` instead of from the live parent session
  for its no-override fields, and feeds the snapshot's `:model` into
  `resolved-model-query` selection context (Decision 7 / AC 7). For runs without
  a snapshot (forward-looking only; pre-existing runs), it falls back to the
  current live-read path so existing behaviour is preserved (Decision: scope —
  forward-looking only; AC 6).
- **resume vs continue (Decision 5).** `resume-run`/`continue-blocked-run-async!`
  reuse the existing stored snapshot (no re-capture; no resolver call —
  unchanged). `continue-terminal-run-async!` creates a new run and therefore
  captures a fresh snapshot via the unchanged mutation path.

## Risks

- **Layering / dependency direction.** `workflow-runtime` must NOT depend on
  `workflow-step-session-config`; the dependency stays caller→both. `create-run`
  must remain pure. Mitigation: snapshot resolution lives only at the three
  caller sites; `create-run` only records data.
- **Schema validation.** `workflow-runtime/model.clj` `workflow-run-schema` is
  validated in `create-run`; adding `:inherited-defaults` requires a schema
  entry (optional) or validation fails. Define a snapshot schema.
- **resolved-vs-raw shape.** The snapshot stores resolved `:tool-defs`/`:skills`
  while the authority stores `:tool-ids`/`:skill-ids`. The validation test must
  encode the documented substitution; risk of mis-encoding the invariant.
- **Two-path drift.** Top-level and nested derivation must produce identical
  field sets. Mitigation: both funnel through the same field-set constant and
  `effective-config->snapshot` is the single config→snapshot projection.
- **Fallback correctness.** Mixed state (some runs with, some without snapshot)
  during/after rollout. Mitigation: explicit `(:inherited-defaults run)` presence
  check with live-read fallback; covered by AC 6 test.
- **Determinism of resolved-model-query.** Selection context must come from the
  snapshot model, not a live re-read, or AC 7 fails.

## Slice order

Vertical slices, each independently buildable and testable; ordered so each
builds on persisted state from the previous.

1. **S1 — Field-set authority surface.** Promote `common-inherited-fields` and
   `model-identity-fields` to public in `session-state/init.clj`; add the named
   snapshot source-key constant + validation test in
   `workflow-step-session-config`. (No behaviour change; establishes the
   single-source-of-truth used by later slices.)
2. **S2 — Snapshot derivation functions.** Add
   `resolve-inherited-defaults-snapshot` and `effective-config->snapshot` to
   `workflow-step-session-config`, built on the existing no-override read logic
   plus the two new `:speed-mode`/`:effort-override` reads. Unit-test both.
3. **S3 — Persist snapshot on the run.** Add optional `:inherited-defaults` to
   `workflow-run-schema` (+ a snapshot sub-schema), and thread it through pure
   `create-run` opts (record verbatim, no resolution). Test create-run persists
   it and stays pure.
4. **S4 — Top-level capture sites.** Resolve+pass the snapshot in the
   `create-workflow-run` mutation and the psi-tool `create-run` op (leave the
   two upstream `mutate!` callers unchanged). Test capture at invoke time +
   fresh capture on continue (Decision 5b).
5. **S5 — Consume snapshot in step config resolution.** Make
   `resolve-step-session-config` read inherited defaults from
   `(:inherited-defaults workflow-run)` (with live-read fallback), and feed the
   snapshot model into `resolved-model-query`. Tests for AC 1/2/3/6/7 + resume
   reuse (AC 8).
6. **S6 — Nested/delegated capture.** In `delegate.clj`, derive the delegating
   step's effective config via `resolve-step-session-config`, project it via
   `effective-config->snapshot`, and pass as `:inherited-defaults` to the child
   `create-run`. Test AC 4 (overridden model propagates to sub-delegation).
7. **S7 — Coherence + docs.** Verify `meta`/`spec`/tests/docs coherence; update
   user-facing docs (workflows/architecture) and changelog if user-visible;
   run lint + full test suite.

## Acceptance → slice map

- AC 1, 2, 3 → S5
- AC 4 → S6
- AC 5 → S5 (override path untouched; regression test)
- AC 6 → S5 (no-mutation/no-snapshot fallback)
- AC 7 → S5 (resolved-model-query from snapshot)
- AC 8 → S4/S5 (resume reuse; continue fresh per Decision 5)
- AC 9 → S3 (snapshot on canonical run state, schema-validated)
