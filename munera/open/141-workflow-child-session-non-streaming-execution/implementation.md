# Implementation Notes

Created from investigation on 2026-05-11.

## Initial orientation

The workflow-scoped surface is clear and small:
- `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
- `components/agent-session/src/psi/agent_session/context.clj`
- `components/agent-session/src/psi/agent_session/child_session_state.clj`

The lower execution surface is broader:
- `components/turn-runtime/src/psi/turn_runtime/stream.clj`
- `components/turn-runtime/src/psi/turn_runtime/core.clj`
- `components/agent-session/src/psi/agent_session/turn.clj`
- `components/ai/src/psi/ai/core.clj`
- provider-specific transport/normalization under `components/ai/src/psi/ai/providers/openai/`

## Investigation result

Current execution is streaming-only at the lower turn-runtime seam:
- `psi.turn-runtime.stream/do-stream!` always calls `ai/stream-response-in` / `ai/stream-response`
- accumulator/runtime semantics are built around streamed provider events
- no general non-streaming execution API is presently exposed through `psi.ai.core`

Therefore this task should not be treated as “just add one more workflow child-session field”.
The workflow config addition is easy; the real work is adding a lower non-streaming execution path that preserves the canonical execution-result contract.

## Scope decision

This task is intentionally **not** a full session-wide streaming preference feature.
It is the smaller slice:
- workflow-authored child session can request `:response-mode :non-streaming`
- lower prompt execution honors that choice
- ordinary top-level interactive sessions remain on the current streaming path
