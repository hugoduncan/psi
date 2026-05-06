Task created.

Initial framing:
- This task should stay subordinate to the broader prompt-lifecycle convergence thread rather than redefining prompt semantics.
- The extracted component is now explicitly the turn component (`psi.turn`), not another prompt-owned namespace.
- Testing scope should stay narrow: do not turn this task into a prompt test-architecture rewrite.
- Planning should inspect only what focused proof needs to change to keep the extraction safe and understandable.

Refinement notes:
- “component” here means a coherent authoritative namespace family and API boundary, not necessarily a new deployable/module boundary.
- The task should explicitly record which namespaces become the turn owner and which surrounding namespaces remain callers/adapters/utilities.
- The extraction is done when turn coordination ownership is singular and callers delegate, not merely when code is moved into a new file.
- Existing tests do not need style conversion as part of this task; only narrow extraction-driven updates should be considered.

Settled planning answers:
- Public API boundary: `psi.turn` should own the full canonical turn flow — submit/start, prepare, execute, record, and continue/finish.
- Higher-level callers should no longer coordinate multiple lifecycle phases directly; they should delegate to the turn owner.
- Move under turn ownership: turn entry/facade implementation, prepared-request building, prepared-request execution, result recording/classification, and continue/finish routing.
- Keep shared: generic conversation projection, provider auth/model lookup, generic system-prompt contribution logic, reusable template/skill expansion primitives, raw prompt stream helpers, raw turn-statechart helpers, persistence primitives, and generic session-state helpers.
- Public API not owned by `psi.turn` in this task unless needed only as a narrow migration seam: steering/follow-up queue policy, interrupt semantics, generic journaling utilities, system-prompt assembly rules, and template/skill discovery.
- Testing posture: do not use this task to convert tests to a different testing architecture. Keep or adjust tests only where needed to preserve confidence in the extraction.

Current likely concrete destination shape:
- one authoritative `psi.turn` namespace family should become the turn owner
- current `dispatch-handlers/prompt_lifecycle.clj` should become thin registration/adaptation rather than the real owner
- current `prompt-control` should remain only as a thin facade/compatibility wrapper over `psi.turn`
- `context` callback bindings for prepared-request building, prepared-request execution, and record-response building should be rewired to `psi.turn` before old prompt implementation ownership is considered migrated

Resolved concrete namespace/layout decisions:
- exact authoritative turn family: `psi.turn`
- recommended namespace split:
  - `psi.turn` — public orchestration API
  - `psi.turn.prepare` — prepared-request construction
  - `psi.turn.execute` — effectful prepared-request execution
  - `psi.turn.record` — assistant-message classification and record-response shaping
  - `psi.turn.handlers` — optional internal organization namespace only if needed; handler ownership for this task still ends with `dispatch-handlers.prompt-lifecycle` kept as a thin registration/adaptation layer
- `prompt-control` should survive only as a thin facade/compatibility wrapper during migration, not as a co-owner of turn logic.
- `dispatch-handlers/prompt_lifecycle.clj` should be thinned to registration/adaptation only once the extraction lands; turn logic should not remain owned there.
- naming rationale: the second namespace segment should match the component name directly; dropping `agent-session` keeps the component boundary distinct and avoids implying that turn ownership still fundamentally lives under agent-session.

Resolved helper-boundary decision:
- keep broadly reused lower-level helpers outside the turn owner, including skill/template expansion primitives, conversation projection helpers, system-prompt contribution helpers, provider auth/model lookup, raw stream helpers, raw turn-statechart helpers, persistence primitives, and generic session-state helpers.
- move only the helpers whose primary meaning is turn lifecycle ownership.
- rationale: if the turn component owns reusable infrastructure or generic shaping helpers, the boundary becomes larger but less clear.

Practical migration sequence chosen:
1. introduce `psi.turn` as the new authoritative owner
2. make `prompt-control` delegate to it
3. move request preparation / execution / record logic under the `psi.turn` family
4. rebind `context` callback bindings to `psi.turn`
5. thin the dispatch registration layer so it only adapts events into the turn owner
6. make only the minimal test adjustments needed as the moved seams stabilize, covering at least the canonical submit/start → prepare → execute → record → continue/finish flow

No unresolved planning questions remain at the task-design level; remaining work is implementation planning and execution.
