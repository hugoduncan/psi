Goal: extract the prompt lifecycle into a dedicated component with a clear boundary, while preserving the current canonical prepare → execute → record → finish flow.

Refinement intent:
- remove ambiguity about what “component extraction” means for this task
- make done-ness testable without requiring a broader prompt-behavior redesign

Context:
- prompt lifecycle ownership has been converging under the existing prompt lifecycle umbrella task (`003-prompt-lifecycle-architectural-convergence`)
- the lifecycle scaffold already exists end-to-end, but the implementation is still spread across broader runtime/session namespaces rather than living behind one dedicated component boundary
- prompt behavior is important enough to deserve a component-local home for lifecycle orchestration, contracts, and focused proof
- tests for this work should stay focused and minimal; broad test-architecture rewrites are out of scope for this extraction task

Problem:
- prompt lifecycle logic is currently harder to reason about and evolve because lifecycle coordination is distributed across broader runtime code
- this makes ownership blurrier for request preparation, execution, recording, finishing, and related prompt metadata shaping
- distributed ownership also makes it easier for duplicate seams, wrapper-local behavior, or compatibility paths to reappear
- test changes can become scope creep unless the task keeps proof changes narrowly tied to the extraction itself

Scope:
In scope:
- define and implement a dedicated component boundary for prompt lifecycle behavior
- move the canonical lifecycle orchestration into that component
- make the component boundary explicit enough that other runtime/session layers depend on it rather than reimplementing lifecycle coordination locally
- preserve the canonical lifecycle phases and current behavior unless a small behavioral correction is required by the extraction
- clarify which prompt responsibilities belong inside the new component versus in surrounding runtime/session code
- update focused documentation/comments where they would otherwise describe the old ownership model
- add or update only the focused tests needed to preserve or clarify behavior during extraction
- avoid broad test-strategy rewrites as part of this task

For this task, “prompt lifecycle orchestration” means the authoritative coordination of:
- request preparation
- prompt execution submission/control
- response/result recording
- terminal lifecycle finishing/reset handoff

For this task, “component extraction” means:
- there is one new authoritative component namespace/family that owns the lifecycle coordination API
- old callers are reduced to delegation/adaptation rather than retaining parallel lifecycle logic
- lifecycle phase ownership can be explained by file/namespace boundaries without relying on tribal knowledge

Out of scope:
- redesigning the prompt lifecycle semantics from scratch
- changing isolated workflow-runtime ownership boundaries unless required only at the interface to the extracted component
- broad prompt UX changes unrelated to component extraction
- unrelated adapter/UI refactors
- replacing existing working behavior just to force a cleaner namespace split
- moving every prompt-adjacent helper into the new component
- changing cache-breakpoint behavior beyond the minimal interface adjustments required for extraction
- converting any part of the prompt test area to a new testing architecture as part of this task

Acceptance:
- prompt lifecycle orchestration has a dedicated component-local home
- surrounding runtime/session layers call into that component instead of each owning partial lifecycle coordination
- lifecycle ownership is clearer for prepare, execute, record, and finish responsibilities
- no duplicate wrapper-local prompt lifecycle behavior remains in the moved area
- focused proof covers the extracted lifecycle well enough to preserve confidence during the move
- any test edits stay narrowly scoped to extraction safety and behavioral preservation
- docs/comments that describe lifecycle ownership are aligned with the extracted component boundary

Concrete done criteria:
- the task names the authoritative lifecycle component namespace(s) explicitly in task notes or implementation notes once chosen
- at least one existing higher-level caller that previously coordinated multiple lifecycle phases now delegates to the extracted component instead
- lifecycle control flow no longer requires re-reading persisted transcript/journal state just to recover the semantic prompt result when a direct execution result is already available
- any test changes made by this task are limited to the minimum needed to keep the extraction safe and understandable
- existing tests do not need style conversion merely because they touch prompt lifecycle behavior

Design constraints:
- preserve the current canonical prompt lifecycle unless a change is necessary to maintain coherence
- prefer extracting an obvious, singular ownership boundary over adding another compatibility seam
- keep execution semantics and persistence/history semantics clearly separated where the current architecture already intends that separation
- keep test changes narrowly tied to the extraction
- prefer preserving existing proof unless a targeted update is needed to reflect the new ownership boundary

Related work:
- `003-prompt-lifecycle-architectural-convergence` remains the broader ownership/convergence umbrella
- `006-agent-tool-skill-prelude-follow-on` remains the concrete skill-prelude/cache-breakpoint follow-on unless this extraction requires a small interface adjustment there

Planning decisions:
- Public API boundary: the extracted prompt lifecycle component will own the canonical prompt turn flow end to end — submit/start, prepare, execute, record, and continue/finish. Higher-level callers should call into this lifecycle component rather than coordinating multiple lifecycle phases themselves.
- Ownership move set: lifecycle-owned entrypoints and orchestration should move under the new component boundary, including prompt turn entry, prepared-request construction, prepared-request execution, result recording/classification, and continue/finish routing.
- Shared utility set: lower-level reusable mechanisms should remain outside the lifecycle owner and be consumed by it as dependencies, including provider auth/model lookup, generic conversation projection helpers, generic system-prompt contribution logic, prompt template / skill expansion primitives where reused elsewhere, raw stream/statechart helpers, persistence primitives, and generic session-state helpers.
- Testing stance: do not use this task to rewrite tests toward a new testing architecture. Only make the minimum focused test updates needed to keep the extraction safe, understandable, and behaviorally covered.

Concrete namespace decisions:
- Authoritative lifecycle owner: introduce a `psi.agent-session.prompt-lifecycle` namespace family as the singular owner of prompt turn orchestration.
- Recommended initial shape:
  - `psi.agent-session.prompt-lifecycle` — canonical public API and orchestration entrypoint for submit/start and execution-result submission
  - `psi.agent-session.prompt-lifecycle.prepare` — prepared-request construction and prompt-layer/request projection
  - `psi.agent-session.prompt-lifecycle.execute` — effectful prepared-request execution against the provider turn runtime
  - `psi.agent-session.prompt-lifecycle.record` — assistant-message classification, record-response shaping, and next-step decision logic
  - `psi.agent-session.prompt-lifecycle.handlers` — thin dispatch registration/adaptation only
- `psi.agent-session.prompt-control` should not remain a co-owner. Preferred direction: keep it only as a thin compatibility/public facade during migration, delegating to `psi.agent-session.prompt-lifecycle`.
- `psi.agent-session.dispatch-handlers.prompt-lifecycle` should be reduced to registration/adaptation or replaced by `psi.agent-session.prompt-lifecycle.handlers`; the real lifecycle logic should move out of the dispatch-handlers area.

Concrete keep-vs-move decisions:
- Move into the lifecycle family:
  - current `prompt_control` prompt turn entrypoints
  - current `prompt_request/build-prepared-request` and closely coupled request-preparation helpers
  - current `prompt_runtime/execute-prepared-request!` and closely coupled execution helpers
  - current `prompt_recording/classify-assistant-message` and `build-record-response`
  - current prompt lifecycle dispatch handler logic for prepare / record / continue / finish orchestration
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
- Rationale: these shared helpers are used as lower-level mechanisms or broader utilities. Moving them under the lifecycle owner would blur the boundary by turning the lifecycle component into the owner of infrastructure or general prompt-shaping primitives it merely consumes.

Migration guidance:
- first make `prompt-lifecycle` authoritative while keeping `prompt-control` as a thin delegating facade for compatibility
- then move request preparation, execution, and record logic under the lifecycle family
- finally thin or replace the current dispatch-handler namespace so it only wires events to the lifecycle owner

Ambiguity resolutions for this task:
- “new component” does not require a new deployable/package boundary; a coherent component-local namespace family inside the current repo is sufficient
- “preserve behavior” means preserve externally relevant prompt-lifecycle semantics and current acceptance surfaces; internal call structure may change freely
- test style is not being standardized by this task; existing tests may remain in their current style unless a narrow extraction-driven change is needed
- “clear boundary” means a future reader can identify one obvious lifecycle owner for prepare/execute/record/finish, and neighboring namespaces can be described as callers, adapters, or utilities instead of co-owners
