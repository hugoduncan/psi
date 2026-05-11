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

## Review note 2026-05-11

Found three actionable design ambiguities:
- workflow runtime IR `session-spec-schema` currently accepts only `:model`, `:tools`, `:skills`, and `:contributions`, so the artifacts do not yet say whether `:response-mode` must be added there for workflow-authored config to parse/validate
- default persistence semantics are underspecified: design says resolved workflow child-session config defaults to `:streaming`, but also says ordinary sessions may leave `:response-mode` absent, so task artifacts should choose whether workflow-owned child sessions persist explicit `:streaming` or only persist `:non-streaming`
- the lower non-streaming seam is still not concrete enough: turn-runtime is named as the branch point, but the new `psi.ai.core` / provider contract needed to execute non-streaming requests is not yet specified

## Design follow-up execution 2026-05-11

Resolved the three preloaded ambiguities in task artifacts only:
- chose that workflow IR `session-spec-schema` must explicitly accept optional `:response-mode`, because workflow-authored child-session config must validate before `resolve-step-session-config` can carry it coherently
- chose canonical persistence semantics: ordinary sessions may still omit `:response-mode`, but workflow-owned child sessions persist an explicit resolved value, including default `:streaming`, so turn-runtime branches on one stored field instead of absence heuristics
- chose the exact lower seam: branch in `psi.turn-runtime.core/execute-prepared-request!`; keep streaming on `psi.ai.core/stream-response{,-in}`; add sibling non-streaming `psi.ai.core/execute-response{,-in}` and a matching provider `:execute` contract, with OpenAI chat-completions implemented first

No code or `steps.md` execution performed in this pass; only design/plan clarification per request.

## Review note 2026-05-11b

Found one new actionable task-artifact inconsistency:
- design data flow says `:response-mode` travels through workflow attempt child-session creation opts into `:session/create-child`, but plan/steps only mention schema/state persistence generically and do not explicitly require widening the child-session creation control surface (`workflow-runtime` attempt opts, execution adapter handoff, mutation params, and child-session initializer) that currently whitelists fields before persistence

## Design follow-up execution 2026-05-11c

Resolved the propagation-surface inconsistency in task artifacts only:
- made the design data-flow explicit at the concrete whitelist owners: `psi.workflow-runtime.attempts/create-step-attempt-session!` → `psi.workflow-runtime.execution-adapter/create-child-session!` → `psi.agent-session.context/create-workflow-child-session!` / `:session/create-child` → `psi.agent-session.child-session-state/child-session-base-state`
- updated `plan.md` to name each surface that must widen for `:response-mode`
- expanded `steps.md` under the child-session propagation step so implementation work can close the surfaces one by one without ambiguity

Per request, did not execute `steps.md` implementation items in this pass; only completed the newly added `design-steps.md` artifact-clarification item.

## Implementation execution 2026-05-11d

Implemented the full workflow-scoped vertical slice.

### Workflow/session propagation landed
- added workflow IR `:response-mode` validation via `psi.workflow-runtime.ir/session-spec-schema`
- updated target-IR compilation to preserve `:response-mode` on session steps and llm-judge session configs
- `psi.workflow-step-session-config.core/resolve-step-session-config` now resolves `:response-mode` and defaults workflow child-session configs to `:streaming`
- widened workflow child-session propagation surfaces so workflow-owned child sessions persist explicit resolved `:response-mode`:
  - `psi.workflow-runtime.attempts/create-step-attempt-session!`
  - `psi.workflow-runtime.statechart-runtime`
  - `psi.agent-session.context/create-workflow-child-session!`
  - `psi.agent-session.dispatch-handlers.session-lifecycle/:session/create-child`
  - `psi.agent-session.mutations.session/create-child-session`
  - `psi.agent-session.child-session-state/child-session-base-state`
- added session-state schema support in `psi.session-state.model` and carried the value into prepared-request session snapshots for lower branching

### Lower non-streaming execution seam landed
- implemented branch point in `psi.turn-runtime.core/execute-prepared-request!`
- branch source is prepared-request/session snapshot `:response-mode`, falling back to persisted session data, default `:streaming`
- retained existing streaming path unchanged through `execute-live-turn!`
- added sibling non-streaming AI API:
  - `psi.ai.core/execute-response-in`
  - `psi.ai.core/execute-response`
  - provider contract key `:execute`
- added OpenAI chat-completions non-streaming transport and normalization:
  - `psi.ai.providers.openai.transport/execute-response`
  - `psi.ai.providers.openai.chat-completions/execute-openai`
  - provider dispatch wiring in `psi.ai.providers.openai`
- non-streaming OpenAI requests reuse the existing request builder, then rewrite the request body to `:stream false` and remove streaming-only `:stream_options`
- non-streaming normalization now returns the canonical higher result ingredients:
  - assistant text blocks
  - tool-call blocks
  - usage
  - stop reason
  - logprobs when available
  - error maps on provider/runtime failure

### Proof added
Focused tests added/updated for:
- workflow step session-config response-mode default + explicit propagation
- workflow/agent-session attempt child-session persistence
- child-session mutation persistence
- lower turn-runtime execution branching on `:response-mode`
- AI core non-streaming provider execution API

Focused verification run green:
- `bb clojure:test:unit -- --focus psi.ai.core-test --focus psi.workflow-step-session-config.core-test --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.child-session-state-test --focus psi.agent-session.child-session-mutation-test --focus psi.turn-runtime.response-mode-test`
- result: `1705 tests, 11926 assertions, 0 failures`

### Remaining cleanup note
- resolved: lint is now clean (`0 errors, 0 warnings`)

## Review note 2026-05-11e

Found one actionable implementation gap:
- non-streaming execution records provider request/response captures via `capture-aware-ai-options`, but `psi.turn-runtime.core/execute-prepared-request!` still returns hard-coded empty `:execution-result/provider-captures`, so the non-streaming path does not preserve the canonical execution-result capture surface expected from the streaming path

## Follow-up execution 2026-05-11f

Closed the remaining provider-capture gap for task 141:
- added lower `psi.turn-runtime.core/provider-captures-for-turn` to shape canonical per-turn captures from persisted turn-runtime telemetry
- `execute-prepared-request!` now returns per-turn `:execution-result/provider-captures` for both streaming and non-streaming paths instead of hard-coded empties
- extended `psi.turn-runtime.response-mode-test` so the non-streaming provider stub emits request/reply captures via the capture callbacks and proves the returned canonical capture surface includes both captures with the current `turn-id`

## Review note 2026-05-11g

Code-shaper pass found no new actionable simplicity, consistency, or robustness issues beyond the already recorded and addressed response-mode capture follow-up; current propagation, branching, and proof surfaces are aligned.

## Review note 2026-05-11h

Test-review pass found no new actionable test gaps after reading the task artifacts plus the focused response-mode, workflow session-config, workflow attempt, child-session state/mutation, and AI core proofs; current tests cover explicit propagation, default streaming fallback, non-streaming branch selection, child-session persistence, and provider-capture shaping without duplicating prior follow-up items.

