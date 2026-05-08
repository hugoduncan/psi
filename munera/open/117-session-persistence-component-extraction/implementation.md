2026-05-07

Implemented the first-cut `session-persistence` extraction.

What moved down
- created `components/session-persistence/` with authoritative public namespace `psi.session-persistence.core`
- moved canonical persistence-specific path ownership there via `session-journal-path` and `session-flush-state-path`
- moved canonical persistence subtree constructors there via `flush-state` and opts-driven `persistence-state`
- moved canonical append/persist semantics there via `append-journal-entry-in!`, compatibility `append-entry-in!`, `persist-journal-in!`, and compatibility `persist-entry-in!`
- moved session-facing journal read helpers, in-memory journal helpers, semantic entry constructors, and session-file store wrappers there

Initialization ownership outcome
- `agent-session.session-runtime/persistence-state` is now a delegating shim over `psi.session-persistence.core/persistence-state`
- `agent-session.child-session-state` now builds child persistence subtrees via `psi.session-persistence.core/persistence-state` instead of reconstructing raw maps inline
- this landed the primitive canonical subtree ownership in the lower component without redesigning higher lifecycle scenario selection

Path ownership outcome
- `psi.session-state.state` no longer owns the persistence-specific path implementation directly
- `session-journal-path` and `session-flush-state-path` in `session-state.state` are now narrow delegating compatibility seams to `psi.session-persistence.core`
- `append-journal-entry-in!` in `session-state.state` now delegates to `psi.session-persistence.core/append-journal-entry-in!`
- authoritative production callers were updated toward `psi.session-persistence.core`

Consumer migration outcome
- `psi.turn` now depends on `psi.session-persistence.core` rather than `psi.agent-session.persistence`
- app-runtime session-summary/selectors and agent-session runtime/lifecycle/workflow/dispatch consumers now depend downward on the extracted component
- project/component deps were updated to include `psi/session-persistence`

Compatibility wrappers intentionally retained for this cut
- `psi.agent-session.persistence` remains as a compatibility re-export namespace only
- `psi.session-state.state` retains delegating seams for `session-journal-path`, `session-flush-state-path`, and `append-journal-entry-in!`

Why wrappers remain for now
- they reduce migration risk while focused tests and remaining consumers are moved in one task slice
- the closure target remains to avoid broad production dependence on those wrappers; this implementation already moved the primary production consumers off `psi.agent-session.persistence`

Verification
- focused regression set green via `clojure -M:test --focus ...` covering:
  - `psi.session-persistence.core-test`
  - `psi.agent-session.journal-append-convergence-test`
  - `psi.session-state.state-test`
  - `psi.session-state.init-test`
  - `psi.agent-session.session-lifecycle-test`
  - `psi.app-runtime.selectors-test`
  - `psi.app-runtime.messages-test`
  - `psi.app-runtime.navigation-test`
- result: `30 tests, 173 assertions, 0 failures`
- an initial implementation attempt introduced a `session-persistence <-> session-state.state` load cycle; fixed by removing the `session-state.state` require from `psi.session-persistence.core` and having the new component own its ctx-level atom access directly
