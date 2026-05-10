Goal: extract the turn lifecycle into a dedicated component with a clear boundary, while preserving the current canonical submit/start → prepare → execute → record → continue/finish flow.

Refinement intent:
- remove ambiguity about what “component extraction” means for this task
- make done-ness testable without requiring a broader prompt-behavior redesign

Context:
- turn lifecycle ownership has been converging under the existing prompt lifecycle umbrella task (`003-prompt-lifecycle-architectural-convergence`)
- the turn scaffold already exists end-to-end, but the implementation is still spread across broader runtime/session namespaces rather than living behind one dedicated component boundary
- the extracted component should use a more distinctive name than `prompt`, because prompt terminology is already overloaded across prompt text, system prompts, prompt templates, and prompt submission
- the component is really about the turn lifecycle, so the component name and namespace should reflect turn ownership directly
- tests for this work should stay focused and minimal; broad test-architecture rewrites are out of scope for this extraction task

Problem:
- turn lifecycle logic is currently harder to reason about and evolve because turn coordination is distributed across broader runtime code
- this makes ownership blurrier for request preparation, execution, recording, finishing, and related prompt/request metadata shaping
- distributed ownership also makes it easier for duplicate seams, wrapper-local behavior, or compatibility paths to reappear
- test changes can become scope creep unless the task keeps proof changes narrowly tied to the extraction itself

Scope:
In scope:
- define and implement a dedicated component boundary for turn lifecycle behavior
- move the canonical turn orchestration into that component
- make the component boundary explicit enough that other runtime/session layers depend on it rather than reimplementing turn coordination locally
- preserve the canonical lifecycle phases and current behavior unless a small behavioral correction is required by the extraction
- clarify which turn responsibilities belong inside the new component versus in surrounding runtime/session code
- update focused documentation/comments where they would otherwise describe the old ownership model
- add or update only the focused tests needed to preserve or clarify behavior during extraction
- avoid broad test-strategy rewrites as part of this task

For this task, “turn lifecycle orchestration” means the authoritative coordination of:
- request preparation
- turn submission/start
- prepared-request execution/control
- response/result recording
- continue/finish progression

For this task, “component extraction” means:
- there is one new authoritative component namespace/family that owns the turn coordination API
- old callers are reduced to delegation/adaptation rather than retaining parallel turn logic
- turn phase ownership can be explained by file/namespace boundaries without relying on tribal knowledge

Public API ownership for this task:
- owned by `psi.turn`:
  - turn submission/start entrypoints
  - prepared-request construction
  - prepared-request execution
  - assistant/execution result classification and recording
  - continue/finish progression
- not owned by `psi.turn` in this task unless required only as a narrow migration seam:
  - steering/follow-up queue policy
  - interrupt semantics
  - generic journal utilities
  - system-prompt assembly rules
  - prompt template / skill discovery

Out of scope:
- redesigning the turn lifecycle semantics from scratch
- changing isolated workflow-runtime ownership boundaries unless required only at the interface to the extracted component
- broad prompt UX changes unrelated to component extraction
- unrelated adapter/UI refactors
- replacing existing working behavior just to force a cleaner namespace split
- moving every prompt-adjacent helper into the new component
- changing cache-breakpoint behavior beyond the minimal interface adjustments required for extraction
- converting any part of the prompt test area to a new testing architecture as part of this task

Acceptance:
- turn lifecycle orchestration has one authoritative namespace family
- the key migration seams (`prompt-control`, `context` callback bindings, and `dispatch-handlers.prompt-lifecycle`) call into that component instead of co-owning turn coordination
- turn ownership is clearer for prepare, execute, record, continue, and finish responsibilities
- no duplicate wrapper-local turn lifecycle behavior remains in the named migration seams for this task
- focused proof covers at least the canonical submit/start → prepare → execute → record → continue/finish flow well enough to preserve confidence during the move
- any test edits stay narrowly scoped to extraction safety and behavioral preservation
- docs/comments that describe turn ownership are aligned with the extracted component boundary

Concrete done criteria:
- the task names the authoritative turn component namespace(s) explicitly in task notes or implementation notes
- `psi.agent-session.prompt-control` delegates to `psi.turn` rather than owning turn coordination itself
- `context` callback bindings for prepared-request building, prepared-request execution, and record-response building point at `psi.turn` functions before old prompt implementation ownership is considered migrated
- lifecycle control flow no longer requires re-reading persisted transcript/journal state just to recover the semantic prompt result when a direct execution result is already available
- any test changes made by this task are limited to the minimum needed to keep the extraction safe and understandable
- existing tests do not need style conversion merely because they touch turn lifecycle behavior

Design constraints:
- preserve the current canonical turn lifecycle unless a change is necessary to maintain coherence
- prefer extracting an obvious, singular ownership boundary over adding another compatibility seam
- keep execution semantics and persistence/history semantics clearly separated where the current architecture already intends that separation
- keep test changes narrowly tied to the extraction
- prefer preserving existing proof unless a targeted update is needed to reflect the new ownership boundary

Related work:
- `003-prompt-lifecycle-architectural-convergence` remains the broader ownership/convergence umbrella
- `006-agent-tool-skill-prelude-follow-on` remains the concrete skill-prelude/cache-breakpoint follow-on unless this extraction requires a small interface adjustment there

Planning decisions:
- Public API boundary: `psi.turn` will own the canonical turn flow end to end — submit/start, prepare, execute, record, and continue/finish. Higher-level callers should call into `psi.turn` rather than coordinating multiple lifecycle phases themselves.
- Ownership move set: turn-owned entrypoints and orchestration should move under the new component boundary, including turn entry, prepared-request construction, prepared-request execution, result recording/classification, and continue/finish routing.
- Shared utility set: lower-level reusable mechanisms should remain outside the turn owner and be consumed by it as dependencies, including provider auth/model lookup, generic conversation projection helpers, generic system-prompt contribution logic, prompt template / skill expansion primitives where reused elsewhere, raw stream/statechart helpers, persistence primitives, and generic session-state helpers.
- Handler end state for this task: keep `psi.agent-session.dispatch-handlers.prompt-lifecycle` only as a thin registration/adaptation layer; do not leave turn logic owned there.
- Testing stance: do not use this task to rewrite tests toward a new testing architecture. Only make the minimum focused test updates needed to keep the extraction safe, understandable, and behaviorally covered.

Concrete namespace decisions:
- Authoritative turn owner: introduce a top-level `psi.turn` namespace family as the singular owner of turn orchestration.
- Recommended initial shape:
  - `psi.turn` — canonical public API and orchestration entrypoint for submit/start and execution-result submission
  - `psi.turn.prepare` — prepared-request construction and turn/request projection
  - `psi.turn.execute` — effectful prepared-request execution against the provider turn runtime
  - `psi.turn.record` — assistant-message classification, record-response shaping, and next-step decision logic
- `psi.agent-session.prompt-control` should not remain a co-owner. Preferred direction: keep it only as a thin compatibility/public facade during migration, delegating to `psi.turn`.
- `psi.agent-session.dispatch-handlers.prompt-lifecycle` should be reduced to registration/adaptation only; the real turn logic should move out of the dispatch-handlers area.
- Naming rationale: the second namespace segment should match the component name directly; keeping `agent-session` in the namespace would misstate the extracted boundary and preserve unnecessary ownership confusion.

Concrete dependency boundary decisions:
- Safe for the `psi.turn` family to depend on:
  - `session-state`
  - `state-accessors`
  - `conversation`
  - `system-prompt`
  - `skills`
  - `prompt-templates`
  - `provider-auth`
  - `prompt-stream`
  - `turn-accumulator`
  - `turn-statechart`
  - persistence primitives
  - model-registry / ai-model helpers where needed
- These are treated as leaf/shared mechanisms, not as lifecycle-owned orchestration.

Concrete keep-vs-move decisions:
- Move implementation ownership into the `psi.turn` family:
  - current `prompt_control` turn entrypoint implementations, while leaving `prompt-control` itself as a temporary compatibility facade
  - current `prompt_request/build-prepared-request` and closely coupled request-preparation helpers
  - current `prompt_runtime/execute-prepared-request!` and closely coupled execution helpers
  - current `prompt_recording/classify-assistant-message` and `build-record-response`
  - current turn lifecycle dispatch handler logic for prepare / record / continue / finish orchestration
- Keep outside as shared dependencies:
  - `skills/invoke-skill`
  - `prompt-templates/invoke-template`
  - `conversation/agent-messages->ai-conversation`
  - `system-prompt/apply-prompt-contributions`
  - `provider-auth/provider-api-key`
  - `model-registry/find-model`
  - raw `prompt-stream` helpers
  - raw `turn-sc` / turn-statechart helpers
  - persistence primitives such as `persist/message-entry`
  - session-state primitives such as `journal-append-in!`
- Must stay above the `psi.turn` family and depend on it rather than being required by it:
  - `core`
  - `context`
  - `dispatch-handlers`
  - `dispatch-handlers.prompt-lifecycle`
  - `workflow-statechart-runtime`
  - `workflow-judge`
  - `mutations.session`
  - adapter / RPC / UI callers
- Rationale: shared helpers are lower-level mechanisms the lifecycle consumes, while higher-level orchestrators and adapters must remain callers so the dependency slope stays one-way and circular dependencies are avoided.

Cycle-avoidance guidance:
- keep `prompt-control` as a thin delegating facade during migration so existing callers do not need to move immediately
- move real turn logic out of `dispatch-handlers.prompt-lifecycle`; keep that namespace as registration/adaptation only for this task
- rewire `context` callback ownership early so callback bindings point at turn-owned functions rather than scattered prompt internals
- do not make `psi.turn` depend on workflows, `core`, `context`, or mutation namespaces

Migration guidance:
- first introduce `psi.turn` as the authoritative owner while keeping `prompt-control` as a thin delegating facade for compatibility
- then move request preparation, execution, and record logic under the `psi.turn` family
- then rebind `context` callback fns to turn-owned functions
- finally thin `dispatch-handlers.prompt-lifecycle` so it only wires events to the turn owner

Ambiguity resolutions for this task:
- “new component” does not require a new deployable/package boundary; a coherent component-local namespace family inside the current repo is sufficient
- “preserve behavior” means preserve externally relevant turn-lifecycle semantics and current acceptance surfaces; internal call structure may change freely
- test style is not being standardized by this task; existing tests may remain in their current style unless a narrow extraction-driven change is needed
- “clear boundary” means a future reader can identify one obvious turn owner for prepare/execute/record/finish, and neighboring namespaces can be described as callers, adapters, or utilities instead of co-owners
