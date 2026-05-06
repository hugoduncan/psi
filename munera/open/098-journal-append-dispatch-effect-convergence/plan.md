Plan:
- inspect the current `journal-append-in!` call graph and classify three responsibilities explicitly: pure in-memory append, higher-level persistence side-effect, and legacy callback wiring
- introduce one authoritative dispatch-owned generic journal-append effect carrying a canonical journal entry
- implement the effect so it performs the pure in-memory journal append through `session-state` and then invokes the existing higher-level persistence boundary when persistence is enabled
- reduce any existing typed journal-append effects to thin shapers over the authoritative generic append-entry effect, or migrate their call sites directly where simpler
- migrate the minimum required representative production callers:
  - session-lifecycle initial journal writes
  - prompt-runtime assistant journal append
  - runtime raw user journal append helper
  - extension `append-entry` mutation
- use `psi.session-state.state/journal-append-in!` only as a temporary migration aid if absolutely necessary during the refactor, then remove the compatibility seam before task completion
- add focused proofs for pure append behavior, canonical effect in-memory behavior, persistence-boundary reachability, and at least one representative migrated production path
- run focused verification first, then full unit verification

Risks:
- journal append is touched by runtime, persistence, tests, and projections, so apparently local changes may expose hidden reliance on the callback seam
- preserving current persistence behavior without broad churn requires keeping the pure append/update boundary crisp and reusing the existing persistence machinery instead of re-inventing it
- there is a small migration-shape risk if some call sites currently rely on typed append effects or helper-local entry construction in ways that are not obvious from the direct append call graph
