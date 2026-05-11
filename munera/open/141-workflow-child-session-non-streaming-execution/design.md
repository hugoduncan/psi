Goal: Allow workflow-authored child session setup to request non-streaming turn execution for workflow-owned child sessions, so workflows can run against providers that reject combinations such as `tools + stream + logprobs` while preserving the existing canonical execution-result contract.

## Context

- Task 140 added a per-session logprob flag for OpenAI chat-completions-compatible providers.
- Some OpenAI-compatible providers reject `logprobs` when combined with `stream: true` and tool support, returning errors such as `logprobs is not supported with tools + stream`.
- Workflow execution already has a dedicated child-session config seam in `psi.workflow-step-session-config.core/resolve-step-session-config` and a narrow execution seam through the workflow execution adapter and the turn execution contract.
- The current turn runtime is streaming-only at execution time:
  - `psi.turn-runtime.stream/do-stream!` always calls `ai/stream-response-in` / `ai/stream-response`
  - turn accumulation and provider consumption are structured around streamed events (`:text-delta`, `:toolcall-*`, `:done`, `:error`)
- `:is-streaming` on session state is lifecycle state (`currently in streaming phase`), not a configurable transport preference. This task must not repurpose that field.

## Scope

This task is intentionally **workflow-scoped**.

It adds a workflow-authored child-session execution preference and teaches the prompt execution seam to honor it for workflow-owned child sessions.

This task does **not** introduce a general user-facing session streaming toggle, slash command, project config, or scheduler-wide session execution-mode feature.

## Desired behaviour

Workflow step session config can specify execution mode for the child session. A workflow-owned child session may request:

- `:response-mode :streaming` — current behaviour
- `:response-mode :non-streaming` — execute the provider request without SSE streaming, then normalize the final provider response into the same canonical execution-result shape used today

When omitted, the default remains `:streaming`.

## Control surface

The new field lives in workflow session config and child session data as `:response-mode`.

Rationale:
- names the execution mode directly
- avoids overloading lifecycle field `:is-streaming`
- leaves room for future execution modes without boolean drift

Accepted values for this task:
- `:streaming`
- `:non-streaming`

Default:
- absent in ordinary sessions
- workflow child-session resolution defaults to `:streaming`

## Data flow

Workflow-authored step/session config:

```clojure
:session {:model ...
          :tools ...
          :skills ...
          :thinking-level ...
          :prompt-component-selection ...
          :response-mode :non-streaming}
```

Flow:

```clojure
workflow definition
→ workflow target IR / effective step session spec
→ resolve-step-session-config
→ workflow attempt child-session creation opts
→ :session/create-child
→ child session data stores :response-mode
→ prompt execution seam reads :response-mode for that child session
→ choose streaming or non-streaming provider execution path
→ normalize into the existing canonical execution-result shape
```

## Architectural choice

The smallest coherent change is to add execution-mode branching at the **turn execution seam**, not in workflow runtime itself.

Why:
- workflow runtime should continue to request `prompt-execution-result!` and receive canonical results
- provider transport details belong below workflow runtime
- the same canonical execution-result map should be produced regardless of execution mode

So the workflow-specific part is:
- child session config and propagation of `:response-mode`

And the lower execution part is:
- prompt/turn execution can branch to streaming or non-streaming transport while preserving canonical result shape

## Non-streaming execution contract

Non-streaming execution must still produce the same higher-level output contract as streaming execution:

- assistant message content
- tool calls
- usage
- stop reason
- error message / http status when present
- logprobs when available

The caller above the seam should not need to know whether the result came from streaming or non-streaming transport.

## Constraints

- Do not rename or overload existing lifecycle `:is-streaming` semantics.
- Do not widen this task into a general end-user `/streaming` command or project/session config feature.
- Do not make workflow runtime itself branch on provider transport details.
- Preserve existing behaviour when `:response-mode` is absent.
- Keep ordinary interactive sessions on the current streaming path unless explicitly extended by a later task.

## Likely implementation slices

1. **Workflow/session config propagation**
   - add `:response-mode` to workflow step session shaping
   - pass it through child-session creation
   - store it on child session data/schema

2. **Turn execution mode branching**
   - add a non-streaming execution path below the prompt execution seam
   - branch on child session `:response-mode`
   - preserve canonical execution-result shaping

3. **Provider support**
   - implement OpenAI chat-completions non-streaming request/response handling first
   - preserve existing streaming provider path unchanged
   - other providers may remain unsupported unless already straightforward to normalize behind the same seam

4. **Proof**
   - workflow step config propagation proof
   - child-session state proof
   - prompt execution proof for `:response-mode :non-streaming`
   - regression proof that absent `:response-mode` stays on streaming behaviour

## Acceptance

- Workflow step/session config accepts `:response-mode :non-streaming`.
- `resolve-step-session-config` includes `:response-mode` in the resolved child session config.
- Workflow child-session creation persists `:response-mode` on the child session data.
- When a workflow-owned child session has `:response-mode :non-streaming`, prompt execution uses a non-streaming provider execution path.
- The non-streaming path returns the same canonical execution-result shape expected by existing workflow/runtime callers.
- When `:response-mode` is absent or `:streaming`, existing streaming behaviour is preserved.
- At least one proof covers the motivating case: a workflow-owned child session can avoid provider rejection of `tools + stream + logprobs` by using `:response-mode :non-streaming`.

## Explicit non-goals

- user-facing `/streaming on|off` command
- project/session global execution-mode preference
- scheduler-wide or top-level runtime execution-mode support
- provider capability auto-detection / auto-fallback beyond the explicit workflow-authored choice
- reworking UI progress semantics for ordinary interactive sessions
