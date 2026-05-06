Task created.

Initial framing:
- This task should stay subordinate to the broader prompt-lifecycle convergence thread rather than redefining prompt semantics.
- Testing scope should stay narrow: do not turn this task into a prompt test-architecture rewrite.
- Planning should inspect only what focused proof needs to change to keep the extraction safe and understandable.

Refinement notes:
- “component” here means a coherent authoritative namespace family and API boundary, not necessarily a new deployable/module boundary.
- The task should explicitly record which namespaces become the lifecycle owner and which surrounding namespaces remain callers/adapters/utilities.
- The extraction is done when lifecycle coordination ownership is singular and callers delegate, not merely when code is moved into a new file.
- Existing tests do not need style conversion as part of this task; only narrow extraction-driven updates should be considered.

Settled planning answers:
- Public API boundary: the new lifecycle component should own the full canonical prompt turn flow — submit/start, prepare, execute, record, and continue/finish.
- Higher-level callers should no longer coordinate multiple lifecycle phases directly; they should delegate to the lifecycle owner.
- Move under lifecycle ownership: prompt turn entry/facade, prepared-request building, prepared-request execution, result recording/classification, and continue/finish routing.
- Keep shared: generic conversation projection, provider auth/model lookup, generic system-prompt contribution logic, reusable template/skill expansion primitives, raw prompt stream helpers, raw turn-statechart helpers, persistence primitives, and generic session-state helpers.
- Testing posture: do not use this task to convert tests to a different testing architecture. Keep or adjust tests only where needed to preserve confidence in the extraction.

Current likely concrete destination shape:
- one authoritative `prompt-lifecycle` namespace family should become the lifecycle owner
- current `dispatch-handlers/prompt_lifecycle.clj` should become thin registration/adaptation rather than the real owner
- current `prompt-control` should either become the public facade of that family or a thin compatibility wrapper over it

Resolved concrete namespace/layout decisions:
- exact authoritative lifecycle family: `psi.agent-session.prompt-lifecycle`
- recommended namespace split:
  - `psi.agent-session.prompt-lifecycle` — public orchestration API
  - `psi.agent-session.prompt-lifecycle.prepare` — prepared-request construction
  - `psi.agent-session.prompt-lifecycle.execute` — effectful prepared-request execution
  - `psi.agent-session.prompt-lifecycle.record` — assistant-message classification and record-response shaping
  - `psi.agent-session.prompt-lifecycle.handlers` — thin dispatch registration/adaptation
- `prompt-control` should survive only as a thin facade/compatibility wrapper during migration, not as a co-owner of lifecycle logic.
- `dispatch-handlers/prompt_lifecycle.clj` should be thinned substantially or replaced by the lifecycle-family handler namespace once the extraction lands.

Resolved helper-boundary decision:
- keep broadly reused lower-level helpers outside the lifecycle owner, including skill/template expansion primitives, conversation projection helpers, system-prompt contribution helpers, provider auth/model lookup, raw stream helpers, raw turn-statechart helpers, persistence primitives, and generic session-state helpers.
- move only the helpers whose primary meaning is prompt turn lifecycle ownership.
- rationale: if the lifecycle component owns reusable infrastructure or generic shaping helpers, the boundary becomes larger but less clear.

Practical migration sequence chosen:
1. introduce `prompt-lifecycle` as the new authoritative owner
2. make `prompt-control` delegate to it
3. move request preparation / execution / record logic under the lifecycle family
4. thin the dispatch registration layer so it only adapts events into the lifecycle owner
5. make only the minimal test adjustments needed as the moved seams stabilize

No unresolved planning questions remain at the task-design level; remaining work is implementation planning and execution.
