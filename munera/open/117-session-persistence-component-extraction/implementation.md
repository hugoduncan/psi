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

2026-05-08 review note — task implementation review
- verdict: implementation is mostly aligned and valuable, but the task is not fully complete against its own design
- strong positives:
  - authoritative lower component `psi.session-persistence.core` exists and owns the intended public persistence API surface
  - `psi.turn` no longer depends on `psi.agent-session.persistence`
  - production consumers were migrated downward to the new component
  - compatibility wrappers were narrowed substantially rather than left as the main production path
  - focused regression and lint verification were run and were green at implementation time
- actionable gap 1: top-level persistence subtree initialization is not fully moved down yet
  - `psi.session-state.init` still constructs flush-state maps directly in `initialize-resume-missing-state`, `initialize-new-session-state`, `initialize-resumed-session-state`, and `initialize-forked-session-state`
  - `psi.session-state.init/initialize-session-slots` still writes journal state through `session-state` path helpers instead of delegating to canonical `psi.session-persistence.core` constructors/helpers
  - this means the task only fully moved child-session runtime initialization and the `agent-session.session-runtime` shim; it did not fully land the broader design statement that top-level and child-session persistence slot initialization delegate to the extracted component
- actionable gap 2: temporary compatibility wrappers remain and need explicit completion treatment
  - `psi.agent-session.persistence` still exists as a broad re-export namespace
  - `psi.session-state.state` still retains delegating seams for `session-journal-path`, `session-flush-state-path`, and `append-journal-entry-in!`
  - these wrappers may be acceptable temporarily, but task closure should either remove them or explicitly justify each remaining seam as intentional with concrete remaining consumers
- actionable gap 3: the implementation exposed a deeper architectural issue worth follow-up tracking
  - the initial `session-persistence <-> session-state.state` load cycle was fixed locally
  - but the cycle is evidence that persistence semantics and persistence IO execution still press against the current boundary shape
  - a follow-on task now exists for that: `118-session-persistence-io-effect-extraction`
- recommended follow-up direction for this task:
  - finish moving the remaining pure persistence subtree initialization helpers in `session-state.init` to `psi.session-persistence.core`, or add canonical persistence-owned pure constructors/update helpers there and delegate from `session-state.init`
  - audit remaining compatibility seams and either remove them or explicitly document why each one remains
  - after those follow-ups, re-run focused persistence/session-lifecycle regressions before considering task closure

2026-05-08 follow-up execution
- moved top-level/session-state persistence subtree initialization onto persistence-owned helpers
  - added `assoc-persistence-state` and `initialize-persistence-state` to `psi.session-persistence.core`
  - `psi.session-state.init/initialize-session-slots` now seeds the journal via canonical persistence initialization instead of direct `assoc-in` on persistence-specific paths
  - `initialize-resume-missing-state`, `initialize-new-session-state`, `initialize-resumed-session-state`, and `initialize-forked-session-state` now build persistence state via `psi.session-persistence.core` helpers rather than raw flush-state maps
- removed the `psi.agent-session.persistence` compatibility namespace from production code
- moved the old compatibility-oriented persistence test into the extracted component test tree as `components/session-persistence/test/psi/session_persistence/compat_removed_test.clj`
- focused follow-up verification green via:
  - `psi.session-persistence.core-test`
  - `psi.session-persistence.compat-removed-test`
  - `psi.session-state.init-test`
  - `psi.session-state.state-test`
  - `psi.agent-session.session-lifecycle-test`
  - `psi.agent-session.journal-append-convergence-test`
- result: `20 tests, 149 assertions, 0 failures`

2026-05-08 final review pass
- reviewed the post-follow-up implementation against the task design and architecture constraints
- conclusion: task `117` now satisfies its stated implementation goals
- accepted retained seam: `psi.session-state.state` still exposes `session-journal-path`, `session-flush-state-path`, and `append-journal-entry-in!`, but these now act as thin session-state compatibility/query surfaces delegating to the persistence owner rather than competing implementations
- no new actionable task-local follow-up items were found in this review pass
- separate architectural follow-up remains correctly tracked as task `118-session-persistence-io-effect-extraction`; that is a boundary refinement beyond the closure criteria of task `117`, not a blocker on `117` itself

2026-05-08 code-shaper review note
- verdict: the implementation is shaped well enough to keep and is acceptable to close for task `117`, but a few non-blocking shaping follow-ups remain worth making explicit
- strengths from the shaping review:
  - persistence ownership is now centralized in `psi.session-persistence.core`
  - consumer dependency direction is simpler and more coherent
  - persistence subtree initialization is now delegated instead of repeatedly rebuilt inline in `session-state.init`
  - compatibility cleanup materially reduced indirection by removing the old production `psi.agent-session.persistence` namespace
- shaping follow-up 1: `psi.session-persistence.core` is still somewhat broad
  - it currently mixes subtree/path construction, ctx mutation helpers, atom journal helpers, disk/store wrappers, and lazy flush orchestration in one namespace
  - this is acceptable for the extraction task, but it is a sign that later reshaping may improve local comprehensibility
- shaping follow-up 2: assistant-message detection logic is duplicated
  - the same assistant-presence check appears in `persist-state-entry!`, `persist-entry!`, and `persist-journal-in!`
  - extracting one local helper would improve consistency and reduce duplication
- shaping follow-up 3: persistence helper layering could be more explicit
  - the new helpers `assoc-persistence-state`, `initialize-persistence-state`, and `append-journal-entry-root-update` are a good start
  - a later local reshaping pass could make the file easier to mutate by grouping subtree/update helpers separately from journal read helpers and persistence execution helpers
- shaping follow-up 4: the deeper semantic-vs-IO separation remains a separate architectural refinement
  - this is already tracked correctly as task `118-session-persistence-io-effect-extraction`
  - it should be treated as a boundary follow-up, not a defect in `117`

2026-05-08 code-shaper follow-up execution
- extracted local helpers `assistant-message-entry?` and `has-assistant-message?` in `psi.session-persistence.core`
- updated `persist-state-entry!`, `persist-entry!`, and `persist-journal-in!` to use the shared helper instead of repeating inline assistant-message detection logic
- result: reduced duplication and more consistent persistence-guard logic across atom/state/ctx persistence paths
- broader local namespace reorganization was explicitly deferred
  - rationale: task `117` is already closure-ready after the helper extraction, and larger persistence-namespace restructuring would overlap substantially with the architecture-oriented boundary work already tracked in `118-session-persistence-io-effect-extraction`
- focused shaping verification green via:
  - `psi.session-persistence.core-test`
  - `psi.session-state.init-test`
  - `psi.agent-session.journal-append-convergence-test`
- result: `7 tests, 39 assertions, 0 failures`
