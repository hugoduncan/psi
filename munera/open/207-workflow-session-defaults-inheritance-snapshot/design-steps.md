# Design follow-up steps — 207

## Architecture-fit follow-ups

- [x] Specify that the inherited-default snapshot is resolved impurely by the
      caller (ctx reads) and passed as already-resolved data into the pure
      `workflow-runtime.core/create-run`, preserving create-run's pure
      root-state lifecycle contract (no ctx reads inside create-run).
      → design.md Decision 6.
- [x] Assign single component ownership for deriving a nested/delegated run's
      effective snapshot (run snapshot ⊕ step overrides) and define the data
      hand-off into child `create-run`, avoiding duplicated resolution logic
      across `workflow-step-session-config` and `workflow-runtime` or a layering
      inversion between them.
      → design.md Decision 7.
- [x] Make the workflow snapshot field set a derivation of (or explicitly
      validated against) the canonical `common-inherited-fields` in
      `session-state/init.clj`, rather than a parallel hand-maintained list, to
      preserve single-source-of-truth for inheritance fields.
      → design.md Decision 8.

## Ambiguity follow-ups

- [x] Decision 8: specify the exact snapshot field set as a *named subset* of
      the canonical authority. `common-inherited-fields` has ~20 fields and
      excludes `:model`/`:thinking-level` (those live in `model-identity-fields`
      in `init.clj`). State which fields the snapshot includes/excludes and how
      it is validated against the authority beyond the `:tool-defs`/`:skills`
      resolved-vs-raw caveat (i.e. account for the dozen extra
      authoritative/runtime fields and the cross-constant model/thinking-level
      gap).
      → design.md Decision 8a.
- [x] Decision 5: disambiguate "continue/resume". Define snapshot behaviour for
      `continue-terminal-run-async!` (creates a NEW run via `create-run`)
      separately from `resume-run` (same run-id). State whether continue reuses
      the original run's snapshot or captures a fresh one, and how a new
      create-run obtains the original snapshot if reuse is intended.
      → design.md Decision 5a (resume reuses) + 5b (continue captures fresh).
- [x] Decision 7: specify the entry point/signature for nested
      effective-snapshot derivation. `delegate.clj`'s
      `delegate-step-runtime-result` does not currently resolve the delegating
      step's effective config. State whether delegate calls
      `resolve-step-session-config` to obtain it and the function it then calls
      to derive the child snapshot (effective config → snapshot), distinct from
      `resolve-inherited-defaults-snapshot(ctx, parent-session-id)`.
      → design.md Decision 7a.
- [x] Decision 6: pin the snapshot-resolution site for the mutation path.
      `create-run` is reached via the `create-workflow-run` mutation
      (`agent-session-ctx`+`session-id`) and via upstream `mutate!` callers
      (`workflow/core.clj`, `orchestration.clj`). State whether the impure
      `resolve-inherited-defaults-snapshot` call lives inside the mutation or in
      the upstream caller, given the resolver needs `ctx`+`parent-session-id`.
      → design.md Decision 6a (inside the mutation; upstream `mutate!` callers
      unchanged).

## Inconsistency follow-ups

- [ ] I1: Decisions 7 and 7a claim `resolve-inherited-defaults-snapshot`
      "reuses the same live-read logic `resolve-step-session-config` uses for
      the no-override path" and list `speed-mode`/`effort-override` among those
      reads, but `resolve-step-session-config` reads/outputs neither (it
      produces `:developer-prompt :prompt-mode :response-mode :tool-defs
      :thinking-level :skills :model` only) — contradicting both the code and
      Decision 1 (which frames `speed-mode`/`effort-override` as recently
      introduced overrides on top of the live-inherited set). State that the
      snapshot resolver must *add* `speed-mode`/`effort-override` ctx reads
      rather than reuse a no-override path that already includes them; align
      Decisions 1, 7, and 7a.
- [ ] I2 (minor): Decision 8a says `common-inherited-fields` has "~20 fields"
      and "the dozen other entries are deliberately excluded", but the vector
      has 19 keys and 14 are excluded (8a itself lists 14). Reconcile the count
      ("14" excluded, 19 total) with the enumeration.
