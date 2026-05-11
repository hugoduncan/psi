Goal: Make workflow-authored `:model-query` session steps fall back across the ranked candidate models produced by shared model selection when the first resolved model cannot be executed, so workflows remain best-effort in environments where model metadata and live provider availability diverge.

## Why

The current workflow child-session path resolves a `:model-query` to a single concrete model before execution.
That means workflow session steps fail immediately when the top-ranked candidate is not runnable at execution time, even if lower-ranked candidates from the same ranked result would have satisfied the workflow intent.

The concrete failure motivating this task is a workflow such as `local-logprobs`:
- the workflow requests a local low-latency low/zero-cost text model via `:model-query`
- selection resolves a first-ranked local candidate
- execution fails with a runtime transport/provider error such as `Connection refused`
- the workflow does not currently try the next ranked candidate

Auto-session-name already has extension-local fallback across ranked helper candidates.
Workflow session-step execution needs the analogous resilience, but scoped to workflow-owned model-query execution rather than generalized to all session execution.

## Scope

This task is intentionally about **workflow session-step execution fallback for authored `:model-query` specs**.

It adds ordered fallback across ranked model candidates for workflow-owned session steps when execution of an earlier ranked candidate fails in a retry-worthy way.

This task does **not**:
- redesign global model selection semantics
- make `psi.ai.model-selection/resolve-selection` runtime-availability-aware
- introduce generic session-wide model fallback for ordinary interactive sessions
- change behaviour for explicit concrete workflow models except for preserving current no-fallback semantics
- broaden provider-health discovery beyond execution-time failure handling

## Desired behaviour

When a workflow session step authors `:model {:type :model-query ...}`:
- workflow session config resolution should preserve access to the ordered ranked candidates implied by that query
- execution should try candidates in ranked order
- if one candidate fails in a fallback-worthy execution/setup way, execution should retry the same step with the next ranked candidate
- execution should stop at the first successful candidate and preserve the existing canonical step result shape
- if all ranked candidates fail, the workflow step should fail with an error that reflects exhaustion of the ranked candidates rather than hiding the failures

When a workflow session step authors an explicit concrete model id or concrete model map:
- current single-model behaviour remains unchanged
- no ranked fallback is introduced

## Fallback-worthy failure class

This task should treat fallback as an **execution-time availability/transport recovery mechanism**, not as a generic semantic retry loop.

Fallback-worthy failures include narrow runtime/setup failures such as:
- connection refused
- provider unreachable / transport exception
- request-time provider execution failure that indicates the chosen candidate could not be run

Non-fallback cases should remain terminal for the candidate sequence, unless implementation review discovers an existing canonical retry/error classification seam that can express the distinction more precisely.
In particular, do not silently fallback on:
- normal successful model responses
- workflow-authored judge/result failures unrelated to model availability
- invalid workflow definitions
- deterministic local shaping/validation failures that are independent of which model candidate is chosen

The exact fallback predicate should be recorded explicitly in `implementation.md` before wiring broad retries.

## Architectural intent

Ranking remains owned by `psi.ai.model-selection`.
Fallback ownership should live in workflow execution, analogous to auto-session-name’s extension-local fallback, rather than being pushed into shared global model-selection or ordinary session execution.

The workflow path should:
- consume the ordered ranked candidates from `resolve-selection`
- preserve one authoritative ranked sequence for a given step attempt
- execute the same workflow step contract against successive concrete candidates
- keep higher workflow result semantics unchanged above the fallback seam

## Likely data/control flow

Desired flow for workflow session steps with `:model-query`:

```clojure
workflow definition
→ workflow target IR / step session spec retains query-shaped model intent
→ workflow session-config resolution obtains ranked candidate sequence from `resolve-selection`
→ step attempt execution chooses first candidate
→ child-session creation / prompt execution runs with concrete candidate
→ if execution succeeds: preserve existing result
→ if execution fails in a fallback-worthy way: retry with next ranked candidate
→ if candidates exhausted: fail step with ranked-fallback exhaustion error
```

A coherent implementation may choose either of these broad shapes:

1. **Resolution returns ranked candidate metadata**
   - workflow step-session config carries both the concrete first choice and the ranked candidate sequence for later fallback
   - execution owns iteration

2. **Execution re-resolves once and iterates ranked candidates**
   - workflow execution consumes the query-shaped model spec and ranking result closer to the execution seam
   - resolution remains mostly concrete for non-query cases

Preferred direction: keep the fallback sequence stable for a given step attempt and avoid re-resolving between candidate attempts unless a narrow compatibility reason requires it.

## Constraints

- Preserve current behaviour for explicit non-query workflow models.
- Do not broaden this into a global model fallback mechanism.
- Keep candidate order authoritative from shared model-selection ranking.
- Avoid re-ranking between attempts unless explicitly justified.
- Preserve the canonical workflow execution-result contract above the fallback seam.
- Keep fallback ownership in workflow execution/runtime, not in generic interactive session turns.

## Acceptance

- Workflow session steps authored with `:model-query` can fall back across ranked candidates when an earlier candidate fails in a fallback-worthy execution/setup way.
- Ranked candidate order matches the shared `psi.ai.model-selection/resolve-selection` ranking order.
- Successful execution stops fallback immediately and preserves existing workflow step result semantics.
- Explicit concrete workflow models continue to use current single-model behaviour without ranked fallback.
- Exhausting all ranked candidates yields a coherent terminal workflow step failure.
- Focused proof covers the motivating case: first-ranked local candidate fails with connection refused, second-ranked candidate succeeds, and the workflow step completes successfully.
- Focused proof also covers that non-query concrete models do not gain fallback behaviour.

## Likely owners

The task likely involves these surfaces:
- `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime/*`
- `components/agent-session/src/psi/agent_session/context.clj`
- any workflow execution seam that currently assumes a single resolved concrete model for a session step

## Explicit non-goals

- global model availability registry
- changing the scoring/ranking logic in `psi.ai.model-selection`
- fallback for ordinary top-level chat turns
- fallback for explicitly authored concrete workflow models
- semantic multi-model voting or answer-quality retries
