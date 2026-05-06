Task created.

Initial sketch captured from post-095/096 orientation:
- `state-kernel` now owns the generic dispatch/state/effect substrate
- `system-bootstrap` now owns whole-system registration/composition
- the next low-level extraction should therefore be the session-shaped substrate still embedded in `agent-session`
- expected extraction focus:
  - session creation/update/lookups
  - child-session relationships and session tree mechanics
  - worktree-path/session identity invariants
  - session-local context/query helpers where they are truly lower-level session machinery
- expected non-goals for this task:
  - prompt lifecycle
  - turn/transcript semantics
  - workflow runtime
  - tool orchestration

Hypothesis to test during implementation:
- a clean `components/session-state/` boundary should make a later `turn` extraction materially smaller and more obvious

Initial inventory pass — 2026-05-06
- inspected the main candidate namespaces and the nearest lifecycle/composition consumers:
  - `components/agent-session/src/psi/agent_session/session.clj`
  - `components/agent-session/src/psi/agent_session/session_state.clj`
  - `components/agent-session/src/psi/agent_session/dispatch_handlers/session_state.clj`
  - `components/agent-session/src/psi/agent_session/session_lifecycle.clj`
  - `components/agent-session/src/psi/agent_session/context.clj`
  - `components/agent-session/src/psi/agent_session/resolvers/session.clj`
  - `components/agent-session/src/psi/agent_session/mutations/session.clj`
- strongest result: there is already a fairly coherent latent `session-state` cluster, but it is split across three namespaces with mixed naming and ownership:
  - `session.clj` is already a pure data/model namespace and looks like the natural seed for `psi.session-state.model`
  - `session_state.clj` already owns canonical root-state accessors, session registry reads, journal append, tree traversal, and worktree lookup; it looks like the natural seed for `psi.session-state.state`
  - `dispatch_handlers/session_state.clj` already owns pure initialization/state-transform helpers for new/resumed/forked/child session setup; despite its current location, it looks like the natural seed for `psi.session-state.init`
- strongest non-candidate result: `session_lifecycle.clj` should remain above the new boundary in the first cut because it composes:
  - persistence file I/O
  - extension lifecycle events
  - workflow clearing
  - dispatch orchestration
  - runtime handle creation/installation
  Those are higher-level application/session-runtime concerns, not pure `session-state` substrate.
- `mutations/session.clj` also stays above the boundary; it is a Pathom/API façade and mixes session-state operations with prompt/runtime behavior.
- `context.clj` is not a first-cut extraction target; it still owns app-level context creation, callback wiring, registries, executors, and cross-domain composition.

Refined extraction sketch
- first-cut target namespaces under `components/session-state/src/psi/session_state/`:
  - `model.clj` ← current `psi.agent-session.session`
  - `state.clj` ← current `psi.agent-session.session-state`
  - `init.clj` ← current pure helpers from `psi.agent-session.dispatch-handlers.session-state`
- defer separate `tree.clj` and `worktree.clj` unless code size/clarity justifies the split during implementation; both concerns are currently small enough to remain folded into `state.clj` in a first pass.
- keep a thin compatibility seam under `agent-session` only if needed to avoid broad caller churn during migration.

Refined open-question answers
- authoritative first-cut public API:
  - pure model/defaults/schema helpers from current `session.clj`
  - canonical root-state path/read/update helpers from current `session_state.clj`
  - session tree traversal helpers (`children-of-in`, `descendants-of-in`)
  - authoritative worktree-path lookup/invariant helper (`session-worktree-path-in`)
  - pure initialization transforms for new/resumed/forked/child sessions from current `dispatch_handlers/session_state.clj`
- isolated query-context construction decision:
  - not required in the first extraction cut
  - keep assembled registration in `psi.system-bootstrap.core`
  - keep broad context/callback wiring in `psi.agent-session.context`
  - only move session-local qctx helpers if they can be separated cleanly from app composition without widening scope
- lifecycle boundary decision:
  - `session-state` owns state shape and pure transitions for session existence/hierarchy/persistence slots
  - `agent-session` owns runtime activation, extension notifications, dispatch orchestration, prompt lifecycle, and workflow/tool/session runtime composition
- likely first consumer migration path:
  - migrate `session_lifecycle.clj`, `resolvers/session.clj`, and dispatch/session handlers to depend on the new `session-state` namespaces directly while preserving their current higher-level responsibilities
- likely first test migration path:
  - move or add tests around pure model validation/defaults, session initialization transforms, session tree traversal, and worktree invariants
  - keep runtime/dispatch/persistence orchestration proofs in `agent-session`

Next implementation posture
- start with the three-namespace extraction (`model`, `state`, `init`) rather than trying to solve `context` and qctx ownership immediately
- treat compatibility wrappers as acceptable only when they reduce churn without obscuring the new authority
- reassess after the first consumer migration whether a separate `tree` or `worktree` namespace is warranted

File-by-file move plan — 2026-05-06
- used the `clj-surgeon` skill to inspect outlines and dependency slices before planning moves:
  - `:ls` on `session.clj`, `session_state.clj`, and `dispatch_handlers/session_state.clj`
  - `:deps` on `initial-session`, `list-context-sessions-in`, `initialize-child-session-state`, and `initialize-new-session-state`
  - `:ls-deps` / `:ls-extract` on the pure initialization helpers to identify the natural minimal extraction units
- strongest planning result from `clj-surgeon`: the first cut should be a mostly whole-file relocation of three coherent authorities, with only one meaningful internal split question inside the current dispatch-handler state helpers.

1. Move `components/agent-session/src/psi/agent_session/session.clj`
- target namespace/file: `components/session-state/src/psi/session_state/model.clj`
- move whole file in the first cut, but with one internal cleanup decision:
  - keep the pure model/schema/defaults/predicates in `model.clj`
  - move the duplicated root-state path/accessor helpers currently at the bottom of `session.clj` out of this authority; they belong with state access in `state.clj`, not in the pure model namespace
- concrete forms to keep in `model.clj`:
  - all schemas through `agent-session-schema`
  - validation fns
  - `default-config`
  - `initial-session`
  - `idle?`
  - `pending-message-count`
  - `has-pending-messages?`
  - `context-fraction-used`
  - `above-compaction-threshold?`
  - `clamp-thinking-level`
  - `next-thinking-level`
  - `next-model`
  - `make-entry`
  - `append-entry`
  - `retry-error?`
  - `context-overflow-error?`
  - `exponential-backoff-ms`
- concrete forms to *not* preserve here because they duplicate state ownership:
  - `session-data-path`
  - `session-telemetry-path`
  - `session-journal-path`
  - `session-flush-state-path`
  - `session-turn-ctx-path`
  - `static-state-paths`
  - `session-state-path-builders`
  - `state-path`
  - `get-state-value-in`
  - `assoc-state-value-in!`
  - `get-session-data-in`
- migration note: this is the one existing namespace where a pre-move cleanup is worth doing so the new component does not inherit duplicated state authority.

2. Move `components/agent-session/src/psi/agent_session/session_state.clj`
- target namespace/file: `components/session-state/src/psi/session_state/state.clj`
- move nearly whole file as the authoritative state/root-path/session-registry surface
- concrete forms to move:
  - `agent-ctx-in`
  - `sc-session-id-in`
  - all private path builders
  - `static-state-paths`
  - `session-state-path-builders`
  - `state-path`
  - internal state read/write helpers
  - `get-state-value-in`
  - `assoc-state-value-in!`
  - `update-state-value-in!`
  - `get-session-data-in`
  - `session-update`
  - `apply-root-state-update-in!`
  - `session-worktree-path-in`
  - `journal-append-in!`
  - `get-sessions-map-in`
  - `list-context-sessions-in`
  - `sc-phase-in`
  - `idle-in?`
  - `sorted-prompt-contributions`
  - `list-prompt-contributions-in`
  - `children-of-in`
  - `descendants-of-in`
- `clj-surgeon` result worth preserving: `list-context-sessions-in` has a tiny extraction footprint (`latest-message-timestamp` + `get-sessions-map-in`), which confirms this namespace is already cohesive and should move largely intact.

3. Move pure initialization helpers out of `components/agent-session/src/psi/agent_session/dispatch_handlers/session_state.clj`
- target namespace/file: `components/session-state/src/psi/session_state/init.clj`
- move the genuinely pure session initialization/state-transform layer
- concrete forms to move:
  - path helpers if still locally needed after state extraction, otherwise require them from `psi.session-state.state`
  - `initial-telemetry`
  - `bounded-append`
  - `initialize-session-slots`
  - `update-runtime-rpc-trace-state`
  - `update-nrepl-runtime-state`
  - `update-oauth-projection-state`
  - `update-recursion-projection-state`
  - `update-background-jobs-store-state`
  - `initialize-resume-missing-state`
  - `carry-runtime-handles`
  - `initialize-new-session-state`
  - `initialize-resumed-session-state`
  - `initialize-forked-session-state`
- conditional move inside this file:
  - `default-child-system-prompt-build-opts`
  - `derive-child-prompt-state`
  - `initialize-child-session-state`
- reason for the conditional decision:
  - `clj-surgeon :ls-extract` showed `initialize-child-session-state` is a coherent extraction unit with just three local dependencies plus prompt-building helpers
  - but those prompt-building helpers depend on `psi.agent-session.system-prompt`, which may be too high-level for a first-cut `session-state` boundary if we want the new component to be prompt-agnostic
- preferred first-cut decision:
  - keep child-session *identity/hierarchy/worktree/default-state* initialization in `session-state.init`
  - if necessary, split prompt-assembly concerns out of `initialize-child-session-state` so `session-state` receives already-resolved prompt/tool/skill state from higher layers rather than calling `system-prompt` directly
- fallback if that split proves noisy:
  - leave `initialize-child-session-state` in `agent-session` temporarily and extract the other initializers first
  - record child prompt-state derivation as the main remaining mixed seam

4. Keep `components/agent-session/src/psi/agent_session/session_lifecycle.clj` in place, but repoint imports
- no move in this task’s first cut
- repoint from:
  - `psi.agent-session.session-state`
  - `psi.agent-session.session`
- to:
  - `psi.session-state.state`
  - `psi.session-state.model`
  - `psi.session-state.init` as needed indirectly through dispatch/session handlers
- role after extraction:
  - higher-level orchestration façade over persistence, runtime creation, extension events, and dispatch

5. Keep `components/agent-session/src/psi/agent_session/context.clj` in place, but repoint imports
- no move in the first cut
- repoint session-state imports to the new component
- do not move `register-resolvers-in!`, `register-mutations-in!`, callback wiring, runtime registries, or cross-domain composition yet

6. Keep `components/agent-session/src/psi/agent_session/mutations/session.clj` in place, but repoint imports
- no move
- repoint pure session helpers to `psi.session-state.model` / `psi.session-state.state`
- keep Pathom/API surface ownership in `agent-session`

7. Keep `components/agent-session/src/psi/agent_session/resolvers/session.clj` in place, but repoint imports
- no move
- repoint from `psi.agent-session.session` and `psi.agent-session.session-state` to the new component authorities
- this is a good early consumer migration proof because it uses the public lower-level session-state read/model surfaces without requiring prompt lifecycle moves

8. Keep `components/agent-session/src/psi/agent_session/dispatch_handlers/session_lifecycle.clj` and `dispatch_handlers/session_mutations.clj` in place, but repoint imports
- no move
- repoint from the old `dispatch-handlers.session-state`, `session`, and `session-state` namespaces onto the new component authorities
- these handlers are likely the first place where the `init.clj` extraction will pay off immediately

9. Compatibility-seam plan
- likely temporary wrappers to keep during migration:
  - `psi.agent-session.session` as a temporary compatibility alias/wrapper to `psi.session-state.model`
  - `psi.agent-session.session-state` as a temporary compatibility alias/wrapper to `psi.session-state.state`
  - `psi.agent-session.dispatch-handlers.session-state` either:
    - shrinks to a thin wrapper over `psi.session-state.init`, or
    - remains only for the deferred child-session prompt-state logic if that is the one unresolved mixed seam
- removal order after consumer migration:
  - first migrate direct lower-level consumers in `agent-session`, `rpc`, and `app-runtime`
  - then shrink or delete compat wrappers once only a small compat-test surface remains

10. Test move plan
- move with `model.clj` authority:
  - `components/agent-session/test/psi/agent_session/session_test.clj`
- move with `state.clj` authority:
  - `components/agent-session/test/psi/agent_session/session_state_enumeration_test.clj`
  - any focused worktree invariant assertions currently embedded in broader tests can be split into a new session-state test namespace
- add/move with `init.clj` authority:
  - focused pure tests for `initialize-new-session-state`
  - `initialize-resume-missing-state`
  - `initialize-resumed-session-state`
  - `initialize-forked-session-state`
  - possibly `initialize-child-session-state` only if the prompt-state seam is resolved cleanly enough to keep that function in the component
- keep in `agent-session`:
  - `session_lifecycle_test.clj`
  - `child_session_mutation_test.clj`
  - `session_close_test.clj`
  - broader persistence/runtime/dispatch tests

11. Recommended implementation order
- Step A: create `components/session-state/` with `model.clj`, `state.clj`, and a minimal deps setup
- Step B: move `session.clj` model authority first, deleting the duplicated state-path section rather than copying that duplication forward
- Step C: move `session_state.clj` nearly whole into `state.clj`
- Step D: move the clearly pure initializer set from `dispatch_handlers/session_state.clj` into `init.clj`
- Step E: defer or split `initialize-child-session-state` depending on whether prompt-state derivation can be cleanly injected from above
- Step F: repoint `session_lifecycle.clj`, `resolvers/session.clj`, handler namespaces, then rpc/app-runtime consumers
- Step G: move focused tests and add new pure init tests
- Step H: decide whether the old `agent-session` namespaces can become wrappers or disappear immediately

12. Main technical risk identified by `clj-surgeon`-guided planning
- the hardest seam is not the bulk of the move; it is the child-session initialization path because the current pure state initializer still performs prompt-shaping work through `psi.agent-session.system-prompt`
- that seam should be treated as the likely place requiring a small pre-move split, while the rest of the `model` and `state` extraction can proceed straightforwardly

Task ambiguity review — 2026-05-06
- review verdict: the task is now mostly clear and executable, but a few implementation ambiguities remained after the extraction sketch and needed explicit closure posture.

Ambiguity 1 — what counts as `session lifecycle primitives` for this task?
- ambiguity: the design text could be read too broadly, making `session_lifecycle.clj` itself sound in-scope because it owns create/resume/fork/close operations.
- resolution: for task 097, `session lifecycle primitives` means the *pure state-shape and identity/hierarchy/persistence-slot transforms* underlying those operations, not the higher-level orchestration namespace.
- explicit non-goal clarified by review: `psi.agent-session.session-lifecycle` stays above the boundary in the first cut and is only a consumer-migration target.

Ambiguity 2 — whether child-session initialization belongs in the first cut
- ambiguity: the current design could still be read as requiring all child-session helpers to move, even though `initialize-child-session-state` currently performs prompt-state derivation through `psi.agent-session.system-prompt`.
- resolution: task completion does **not** require moving `initialize-child-session-state` unchanged.
- accepted completion shapes are now:
  - split prompt derivation from identity/hierarchy/default-state initialization and move the lower-level part into `session-state`, or
  - defer `initialize-child-session-state` behind a temporary compat seam while extracting the other pure initializers first.
- this is the main sanctioned partial-defer seam in the task.

Ambiguity 3 — should the old `agent-session` namespaces disappear immediately?
- ambiguity: acceptance language about canonical ownership could be read as requiring immediate deletion of `psi.agent-session.session`, `psi.agent-session.session-state`, and `dispatch-handlers.session-state`.
- resolution: deletion is preferred but not required in the first implementation slice.
- task acceptance allows temporary compatibility wrappers if:
  - the new component is the authoritative owner,
  - lower-level consumers are migrated toward it,
  - the wrappers no longer contain unique authority except possibly the explicitly deferred child-session prompt seam.

Ambiguity 4 — how much `rpc` and `app-runtime` migration is required for done-ness?
- ambiguity: “update any other consumers” is broader than necessary for a first extraction slice.
- resolution: done-ness requires representative migration, not exhaustive same-pass cleanup of every consumer in the repo.
- minimum required migration posture:
  - repoint the core `agent-session` consumers that establish the boundary (`session_lifecycle`, session resolvers, relevant session dispatch handlers)
  - migrate at least one real lower-level non-agent-session consumer path, with `rpc` or `app-runtime` both acceptable proof surfaces
  - leave further caller cleanup as an allowed follow-on if compat wrappers preserve behavior cleanly.

Ambiguity 5 — whether isolated query-context helpers are mandatory scope
- ambiguity: earlier design text listed session-local query-context construction and registration helpers as possible ownership, which could still imply mandatory extraction.
- resolution: those helpers are optional in this task.
- explicit closure rule: if moving any qctx helper would drag app composition, callback wiring, or assembled registration responsibilities with it, defer that helper and record the deferral.

Ambiguity 6 — where tests must physically live after extraction
- ambiguity: “move tests” could imply every test must relocate to the new component tree during the same slice.
- resolution: physical relocation is desirable but not mandatory when it causes broad fixture churn.
- what matters for task completion is that the proof surface matches the new ownership:
  - pure model/state/init behavior has focused tests clearly proving the new authority
  - orchestration tests can remain in `agent-session` if their subject is still orchestration rather than the extracted lower component.

Post-review closure posture
- no blocking ambiguity remains for starting implementation.
- strongest explicit implementation guardrails after review are:
  - keep `session_lifecycle.clj` above the boundary
  - treat `initialize-child-session-state` as the one allowed deferred seam
  - treat qctx/helper movement as optional
  - allow temporary compat wrappers while migrating core consumers toward the new authority
- if implementation uncovers a second seam as mixed as the child-session prompt derivation path, record it immediately before widening scope.

Implementation pass — 2026-05-06
- created new component `components/session-state/` with first-cut authorities:
  - `src/psi/session_state/model.clj`
  - `src/psi/session_state/state.clj`
  - `src/psi/session_state/init.clj`
  - `deps.edn`
- wired root/component deps so the new component is on the normal runtime and test classpaths:
  - root `deps.edn`
  - `components/agent-session/deps.edn`
  - `components/app-runtime/deps.edn`
  - `components/rpc/deps.edn`
- moved canonical pure session model ownership to `psi.session-state.model`
- moved canonical root-state path/read/update/tree/worktree ownership to `psi.session-state.state`
- moved canonical pure init/update transforms to `psi.session-state.init`

Compatibility posture after extraction
- replaced the old mixed authorities with thin compat wrappers:
  - `psi.agent-session.session` now delegates to `psi.session-state.model` and re-exports a small set of state helpers still depended on by older mixed callers
  - `psi.agent-session.session-state` now delegates to `psi.session-state.state`
  - `psi.agent-session.dispatch-handlers.session-state` now delegates to `psi.session-state.init` for pure init/update transforms
- intentionally kept one mixed seam above the lower boundary:
  - `initialize-child-session-state`
  - its prompt/tool/skill/system-prompt derivation still depends on `psi.agent-session.system-prompt`, so it remains in the compat namespace for now rather than being pushed down into `session-state.init`

Direct consumer migration completed in this slice
- repointed representative higher-level consumers directly onto the new component authorities:
  - `psi.agent-session.session-lifecycle` → `psi.session-state.state`
  - `psi.agent-session.resolvers.session` → `psi.session-state.model` + `psi.session-state.state`
  - `psi.agent-session.mutations.session` → `psi.session-state.model` + `psi.session-state.state`
  - `psi.agent-session.dispatch-handlers.session-lifecycle` → `psi.session-state.init` + `psi.session-state.state`, while still using the compat seam for child-session init
  - `psi.agent-session.dispatch-handlers.session-mutations` → `psi.session-state.init` + `psi.session-state.state`
  - `psi.rpc.session.ops` → `psi.session-state.state`
  - `psi.app-runtime.context` → `psi.session-state.state`
- this satisfied the task requirement to migrate at least one real non-`agent-session` consumer path; both RPC and app-runtime now consume the extracted component directly.

Important boundary corrections discovered during implementation
- first cut of `psi.session-state.state` accidentally depended on higher-level `agent-session` namespaces (`persistence`, `message-text`, `statechart`), which violated the desired slope and created load/cycle issues.
- corrected final posture:
  - `journal-append-in!` no longer requires `persistence`; it delegates through `ctx :journal-append-fn`
  - context wiring now points `:journal-append-fn` at `psi.agent-session.persistence/append-entry-in!` rather than back at `ss/journal-append-in!`, avoiding recursion
  - display-name shaping in `list-context-sessions-in` is now implemented locally in `psi.session-state.state` rather than depending on `message-text`
  - `sc-phase-in` no longer depends on `psi.agent-session.statechart`; it reads the session statechart working memory directly via Fulcro Statecharts protocols, preserving correct session phase semantics without recreating a namespace cycle
- one transient regression came from mistakenly reading the child agent-core phase instead of the session statechart phase; fixing that restored the expected `:streaming` / `:idle` semantics across runtime/UI tests.

Focused proof added at the new component layer
- added component-local tests:
  - `components/session-state/test/psi/session_state/model_test.clj`
  - `components/session-state/test/psi/session_state/state_test.clj`
  - `components/session-state/test/psi/session_state/init_test.clj`
- proof now covers:
  - session creation defaults and model predicates
  - session creation/lookup/update through extracted state helpers
  - journal append via extracted state helper
  - session tree traversal (`children-of-in`, `descendants-of-in`)
  - worktree-path invariant
  - pure init transforms for new/resume-missing/resumed/forked sessions

Verification
- representative focused regressions green after fallout cleanup:
  - `psi.app-runtime.context-test`
  - `psi.rpc-events-test`
  - `psi.agent-session.model-dispatch-test`
  - `psi.agent-session.background-jobs-test`
  - `psi.agent-session.child-session-mutation-test`
  - `psi.agent-session.prompt-lifecycle-test`
  - result: `1514 tests, 11692 assertions, 0 failures`
- full unit suite green:
  - `1514 tests, 11692 assertions, 0 failures`

Settled ownership split after implementation
- `state-kernel`
  - still owns the generic dispatch/state/effect substrate only
- `session-state`
  - now owns canonical session model/data
  - root-state session access/update/path helpers
  - worktree/session identity invariants
  - session tree traversal
  - pure non-child init/update transforms
  - session phase read helper over session statechart working memory
- `agent-session`
  - still owns prompt lifecycle
  - session runtime creation/installation
  - persistence orchestration/wiring
  - extension notifications and dispatch orchestration
  - child-session prompt/tool/skill/system-prompt derivation seam
  - Pathom/API façades and broader runtime composition

Follow-on candidates exposed by this slice
- split `initialize-child-session-state` so lower child identity/hierarchy/default-slot state can move fully into `session-state.init` while prompt derivation stays above
- continue direct caller migration away from compat wrappers until `psi.agent-session.session` no longer needs to re-export any state helpers
- consider whether the lightweight local display-name shaping in `session-state.state` should remain there as the lower-level session-listing authority, or whether a later shared lower utility component is warranted
