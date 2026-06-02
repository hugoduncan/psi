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
