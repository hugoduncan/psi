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
  - `effective-config->snapshot (effective-config parent-snapshot) → snapshot` —
    nested path; pure projection of an already-resolved effective step-config
    into the snapshot field set, no ctx reads. Because
    `resolve-step-session-config` outputs NEITHER `:speed-mode` NOR
    `:effort-override` (resolved I1 — its result set is `:developer-prompt`,
    `:prompt-mode`, `:response-mode`, `:tool-defs`, `:thinking-level`, `:skills`,
    `:model`, `:prompt-component-selection`, + optional
    temperature/model-fallback/logprob), a `select-keys` over the effective
    config alone yields only **5 of the 7** snapshot keys and would silently drop
    speed-mode/effort-override under delegation (breaking AC3/AC4 for those two,
    resolved P2). So `effective-config->snapshot` takes a second arg — the
    **parent run's snapshot** — and carries its `:speed-mode`/`:effort-override`
    through (these are not per-step overridable today, so the parent snapshot is
    the correct source). The five resolver-emitted inherited keys come from the
    effective config; the two speed/effort keys come from the parent snapshot.
    Shared projection so the two paths cannot drift.
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
- **Consumption (per-field source swap, not a whole-path fork — resolved P5).**
  `resolve-step-session-config` keeps its current structure; the snapshot only
  changes the **source** of the 7 inherited-default fields (`:model`,
  `:prompt-mode`, `:tool-defs`, `:skills`, `:thinking-level`, `:speed-mode`,
  `:effort-override`). All other resolver outputs that are NEVER inherited from
  the parent and always derive from step-def/base-meta (`:developer-prompt`,
  `:response-mode`, `:prompt-component-selection`, `:temperature`, logprob,
  `:model-fallback`) stay on their existing code path regardless of snapshot
  presence. When `(:inherited-defaults workflow-run)` is present, the
  `parent-session`-sourced bindings for those 7 fields are read from the snapshot
  instead of the live `get-session-data`/`all-skills`/tool reads; when absent
  (pre-existing runs, forward-looking only), they fall back to the current
  live-read path (Decision: scope — forward-looking only; AC 6).
- **`parent-session-model` replaced wholesale (resolved P4).** The single
  `parent-session-model` binding (`core.clj:164`, today `(:model parent-session)`)
  feeds FOUR sites: `resolved-step-model-config` for the step `:session` override
  (`:173`), for the base-meta override (`:175`), the bare no-override `:model`
  fallback (`:183`), and (transitively, via those) `resolved-model-query`. When a
  snapshot is present, the **binding itself** is set to the snapshot's
  `{:provider :id}` `:model` (still a single binding), so ALL four consumers —
  including the two override-resolution calls at `:173`/`:175` and the
  model-query selection context — observe the snapshot model, not a live re-read.
  Replacing only the model-query / no-override subset would leak AC1/AC2 via
  override resolution still reading the live parent. AC 5 (explicit override wins)
  is preserved because the override path's *output* still takes precedence; only
  its *selection context* (`parent-session-model`) is sourced from the snapshot.
- **resume vs continue (Decision 5).** `resume-run`/`continue-blocked-run-async!`
  reuse the existing stored snapshot (no re-capture; no resolver call —
  unchanged). `continue-terminal-run-async!` creates a new run and therefore
  captures a fresh snapshot via the unchanged mutation path.

## Risks

- **Layering / dependency direction.** `workflow-step-session-config` ALREADY
  depends on `workflow-runtime` (`deps.edn`: `psi/workflow-runtime`; `core.clj:16/17`
  require `execution-adapter`/`statechart`). The reverse edge — `workflow-runtime`
  (`delegate.clj`) requiring `workflow-step-session-config` — would therefore form
  a require **cycle**, not the safe caller→both direction. This is **certain, not
  conditional** (resolved P1). Decision: S6 does NOT add a direct require; instead
  the snapshot resolver is **injected as a passed fn** into
  `delegate-step-runtime-result` (mirroring its existing
  `create-workflow-context-fn`/`send-and-drain-fn` injected params). The injected
  fn is wired at the caller (agent-session / orchestration layer, which already
  depends on both components), so `delegate.clj` never requires
  `workflow-step-session-config`. `create-run` stays pure (records data only).
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
- **Snapshot `:model` shape (resolved P3).** The live source `(:model
  parent-session)` is a `{:provider :id}`-shaped map:
  `model-query->selection-request` (`core.clj:104`) reads `(:provider …)`/`(:id
  …)`, and `candidate->session-model` emits `{:provider (name …) :id …}`. The
  snapshot therefore stores `:model` in that **same `{:provider :id}` map shape**
  (a verbatim copy of the live `(:model parent-session)`), NOT a bare id string —
  so it drops directly into `parent-session-model` and the model-query
  destructure without any reshaping. The `inherited-defaults-schema` encodes
  `:model` as a `{:provider :id}` map (optional). Same shape on both top-level
  (`resolve-inherited-defaults-snapshot` copies `(:model parent-session)`) and
  nested (`effective-config->snapshot` copies the effective config's already
  `{:provider :id}`-shaped `:model`) paths.

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
6. **S6 — Nested/delegated capture.** Add a new injected fn param to
   `delegate-step-runtime-result` (e.g. `resolve-inherited-defaults-fn`,
   alongside the existing `create-workflow-context-fn`/`send-and-drain-fn`).
   The caller (which depends on both components) binds it to a closure over
   `resolve-step-session-config` + `effective-config->snapshot`; `delegate.clj`
   invokes it to derive the delegating step's effective-config snapshot and pass
   it as `:inherited-defaults` to the child `create-run`. `delegate.clj` does NOT
   require `workflow-step-session-config` (avoids the certain require cycle, P1).
   The nested derivation also threads the parent run's snapshot
   `:speed-mode`/`:effort-override` (P2). Test AC 4 (overridden model propagates
   to sub-delegation).
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
