Goal: extract a lower-level `session-state` component from `agent-session` so session-shaped state/lifecycle/query-context machinery becomes an explicit reusable boundary between the generic `state-kernel` and higher-level prompt/turn/workflow/tool behavior.

Context:
- task 095 extracted the generic dispatch/state/effects substrate into `components/state-kernel/`
- task 096 moved global resolver/mutation/domain registration ownership up into the explicit composition root `psi.system-bootstrap.core`
- those two tasks removed the deepest generic/runtime and whole-system assembly concerns from `agent-session`
- what remains in `agent-session` is still structurally too broad: it mixes session-state mechanics with prompt lifecycle, turn semantics, workflow/tool orchestration, and session-facing query/bootstrap helpers
- 095 explicitly identified a likely later follow-on extraction of a lower `session-state` component once the abstract kernel exists
- this task is that follow-on: it should pull out the next low-level component below prompt/turn/workflow/tool behavior, not jump directly to `turn`

Problem:
- `agent-session` still appears to be the owner of several concerns that are lower-level than prompt/turn/workflow/tool behavior:
  - session record/state model
  - session lifecycle primitives
  - parent/child session relationships and session-tree mechanics
  - worktree-path and session identity invariants
  - session-local query-context construction and registration helpers
  - session-scoped lookup/update helpers used by higher-level runtime features
- because those concerns remain embedded inside `agent-session`, any future extraction of `turn`, prompt orchestration, workflow runtime, or tool execution still risks dragging a large session model/runtime substrate along with it
- the current boundary is therefore better than before 095/096, but still not clean enough: `agent-session` remains both a domain and an accidental substrate

Intent:
- create one explicit `components/session-state/` boundary below the higher-level agent/prompt/turn/workflow/tool layer
- move session-shaped but not turn-shaped machinery into that component
- leave prompt lifecycle, turn content semantics, workflow execution, tool execution, extension behavior, and UI/RPC projections above that boundary
- make a later `turn` extraction smaller and more obvious by ensuring `turn` depends on `session-state`, not the reverse
- preserve current behavior while clarifying ownership and dependency direction

Component decision:
- chosen component name/path for this task: `components/session-state/`
- the component represents session identity, state, lifecycle, hierarchy, and session-local context/query support
- it is intentionally more specific than `state-kernel` and intentionally lower-level than `turn`

Boundary definition for this task:
The `session-state` component may own machinery that is session-shaped and reusable across multiple higher-level session consumers, but is not application-independent enough to belong in `state-kernel`.

That includes, if present after shaping:
- session identity and metadata model
- session lifecycle primitives (create/open/close/update state transitions that are about session existence/ownership rather than prompt turns)
- session tree / parent-child relationship mechanics
- worktree-path invariants and session directory semantics
- canonical session lookup/update helpers over root state
- session-local context or query-context construction helpers when they are truly about session-scoped isolated execution state
- session-facing local resolver/mutation registration helpers, if they are part of isolated session query-context construction rather than whole-system composition
- session-scoped scheduler/background ownership only where that ownership is fundamentally part of session lifecycle/state rather than prompt/tool/workflow behavior

That does not include:
- generic dispatch/event/effect substrate already owned by `state-kernel`
- global domain registration/composition already owned by `psi.system-bootstrap.core`
- prompt request preparation/submission/record/finish semantics
- turn message/content assembly semantics
- workflow step/attempt/runtime behavior
- tool execution/orchestration behavior
- extension-specific runtime behavior
- adapter/RPC/TUI/Emacs projection concerns
- provider/model/auth semantics

Likely extraction candidates:
- session model/state helpers currently under `components/agent-session/src/psi/agent_session/`
- pure session initialization/state-transform helpers underlying higher-level lifecycle orchestration
- canonical session lookup/update operations
- session tree traversal and lower-level parent/child relationship helpers
- authoritative worktree-path/session-directory semantics
- tests/support helpers that currently expose session-state semantics through `agent-session` only because there is no lower component boundary yet

Likely non-candidates for this task:
- prompt lifecycle namespaces
- turn/transcript/content-block shaping
- workflow runtime/execution/judge/delegate code
- tool execution/batch/post-tool orchestration
- extension activation or UI projection code
- app-runtime, rpc, tui, emacs layers

Key design rule:
- if a namespace or helper can be reused by multiple higher-level session consumers without knowing anything about prompts, turns, workflows, or tools, it is a good `session-state` candidate
- if a namespace or helper encodes agent conversation semantics, it stays above the new boundary

Question status for this task:

Settled after the first inspection pass:
- what is the authoritative public API of `session-state`?
- which currently exported `agent-session` helpers are truly session-state and should migrate?
- which currently mixed namespaces need splitting before moving code?
- where is the clean line between session lifecycle and prompt lifecycle?
- which current tests are really session-state tests and should move with the component?

Still conditional during implementation:
- does `initialize-child-session-state` move in the first cut only after being split into a lower-level child-session state initializer plus higher-level prompt-state derivation, or does the current mixed function remain above the boundary behind a temporary compat seam?

Inventory refinement — settled answers after the first inspection pass:
- authoritative first-cut `session-state` API should be deliberately narrow and center on four surfaces:
  - pure session data/model helpers
  - root-state session access/update/path helpers
  - pure session initialization/lifecycle state transforms
  - session tree/worktree invariants and traversal helpers
- strongest existing extraction candidates are:
  - `psi.agent-session.session` → pure data/model/schema/defaults/derived predicates
  - `psi.agent-session.session-state` → canonical root-state paths, reads/writes, journal/session registry helpers, tree traversal, worktree lookup
  - `psi.agent-session.dispatch-handlers.session-state` → pure initialize/update transforms for new/resumed/forked/child sessions and related slot initialization
- likely first-cut new namespaces inside `components/session-state/src/psi/session_state/` are now clearer:
  - `model.clj` for the current pure `psi.agent-session.session` content
  - `state.clj` for the current atom/root-state accessors from `psi.agent-session.session-state`
  - `init.clj` for the current pure initialization helpers from `psi.agent-session.dispatch-handlers.session-state`
  - `tree.clj` only if parent/child traversal and close-order logic become large enough to merit a split; otherwise keep them in `state.clj` in the first cut
  - `worktree.clj` only if worktree/session-directory semantics can be isolated cleanly without creating unnecessary namespace churn in the first cut
- initial public API should expose canonical helpers equivalent in role to:
  - `initial-session`, `valid-session?`, `pending-message-count`, `has-pending-messages`, `context-fraction-used`
  - `state-path`, `get-state-value-in`, `assoc-state-value-in!`, `update-state-value-in!`, `get-session-data-in`, `apply-root-state-update-in!`
  - `session-update`, `session-worktree-path-in`, `journal-append-in!`, `get-sessions-map-in`, `list-context-sessions-in`, `children-of-in`, `descendants-of-in`
  - pure state initializers now living in `dispatch-handlers.session-state`
- mixed namespace split decisions are also clearer:
  - `psi.agent-session.session-lifecycle` should stay above the new boundary because it composes persistence, extension events, runtime creation, workflow clearing, and dispatch orchestration even though it uses lower-level session-state transforms
  - `psi.agent-session.mutations.session` should stay above the new boundary; it is a Pathom mutation façade over session-state plus prompt/runtime behavior
  - `psi.agent-session.context` should not move wholesale; it still owns broad application context creation and callback wiring
- isolated query-context ownership is now refined:
  - isolated session query-context construction does **not** move into `session-state` in this first cut
  - assembled multi-domain registration remains in `psi.system-bootstrap.core`
  - broad callback wiring, runtime registries, and cross-domain composition remain in `psi.agent-session.context`
  - future movement of any session-local qctx helper would require a later slice that first detaches it cleanly from application composition concerns
- clean lifecycle line for this task:
  - `session-state` owns existence/identity/hierarchy/worktree/persistence-slot state transitions
  - `agent-session` continues to own runtime activation, prompt lifecycle, extension notifications, workflow/session orchestration, and runtime handle installation
- likely first test migration targets are:
  - session model/schema/defaults tests
  - session creation/fork/resume state-shape tests that currently only prove pure initialization/state transitions
  - session tree / close-order / worktree invariant tests
  - keep tests that require runtime creation, dispatch orchestration, extension events, or prompt execution in `agent-session`

In scope:
- define the `session-state` boundary explicitly
- identify the current `agent-session` namespaces and namespace parts that belong in it
- extract the lower-level session-state machinery into `components/session-state/`
- split mixed namespaces where needed so only session-state concerns move
- update `agent-session` and any other consumers to depend on `session-state` instead of owning that machinery locally
- preserve current behavior while changing ownership
- add or update focused tests proving the extracted session-state behavior
- record the resulting ownership split clearly enough to guide a later `turn` extraction

Out of scope:
- extracting `turn` in this task
- redesigning prompt lifecycle or transcript semantics
- restructuring workflow/tool runtimes beyond import changes required by the new boundary
- broad rewrite of agent-session domain behavior just because it is nearby
- moving anything back down into `state-kernel` that is genuinely session-shaped
- changing global registration ownership already clarified by task 096

Acceptance:
- a new `components/session-state/` component exists with a clear purpose
- the component contains only session-shaped, lower-level machinery and no prompt/turn/workflow/tool semantics
- `agent-session` no longer owns the canonical session-state substrate locally
- dependency direction is one-way: higher layers depend on `session-state`, and `session-state` may depend on `state-kernel`, but not the reverse
- no new component cycle is introduced
- focused proof covers the extracted session-state behavior sufficiently to preserve confidence
- task notes explain what moved, what stayed above the boundary, and why
- the resulting structure makes a later `turn` extraction more plausible rather than less

Concrete done criteria:
- the chosen component name/path is recorded explicitly as `components/session-state/`
- the authoritative session identity/state/lifecycle/tree/worktree-path machinery no longer resides only under `components/agent-session/`
- isolated session query-context construction remains above `session-state` in this task, and that boundary is documented explicitly
- generic dispatch ownership remains in `state-kernel`; this task does not recreate dispatch substrate inside `session-state`
- prompt lifecycle ownership remains above `session-state`; this task does not quietly become a `turn` or prompt extraction
- `agent-session` compiles and focused session-state/consumer verification is green while consuming the extracted component through explicit dependencies
- minimum focused proof for this task covers:
  - session creation/lookup/update through the extracted component
  - session tree or child-session relationship behavior through the extracted component
  - worktree-path/session identity invariants through the extracted component
  - at least one consuming `agent-session` path still working through the extracted component
- tests or helper surfaces that only need session-state semantics stop depending on higher-level `agent-session` APIs where a lower component API is now available

Design constraints:
- prefer a crisp boundary over a maximal extraction
- extract lower-level session substrate first, not prompt/turn semantics
- preserve current behavior; ownership changes are the goal
- keep the dependency slope obvious: `state-kernel` -> `session-state` -> `agent-session`/higher layers
- split mixed namespaces when needed instead of moving higher-level semantics downward
- do not let this task quietly expand into workflow/tool/prompt cleanup beyond what boundary extraction requires

Proposed namespace sketch:
- settled first-cut home areas inside `components/session-state/src/psi/session_state/`:
  - `model.clj` for the pure session data/schema/defaults/derived-predicate authority currently concentrated in `psi.agent-session.session`
  - `state.clj` for canonical root-state path/read/update helpers, session registry reads, journal append, worktree lookup, prompt-contribution helpers, and tree traversal currently concentrated in `psi.agent-session.session-state`
  - `init.clj` for the pure session initialization/state-transform authority currently concentrated in `psi.agent-session.dispatch-handlers.session-state`
- possible later splits only if implementation pressure justifies them:
  - `tree.clj` if parent/child traversal or close-order logic grows enough to merit its own namespace
  - `worktree.clj` if worktree/session-directory invariants become large or conceptually independent enough to stand alone
- `context.clj` is not part of the first-cut namespace plan; isolated session query-context helpers move only if they can be separated cleanly from higher-level composition/bootstrap wiring without widening scope
- likely remaining ownership in `agent-session` after this task:
  - prompt lifecycle
  - turn/transcript semantics
  - tool runtime/orchestration
  - workflow runtime/orchestration
  - higher-level session command/event semantics that compose session-state with prompt/tool/workflow behavior

Related work:
- task 095 established the lower generic substrate in `components/state-kernel/`
- task 096 established explicit global registration ownership in `psi.system-bootstrap.core`
- this task is the next extraction layer between those lower pieces and a later `turn` extraction
- a likely follow-on after this task is a smaller, more tractable `turn` component extraction once session-state ownership is explicit
