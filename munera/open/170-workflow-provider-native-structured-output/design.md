# 170 — Workflows use provider-native structured output

## Intent

Wire workflow structured-output declarations from task 168 into the LLM structured-output capability surface from task 169, so workflow steps and judges request provider-native schema enforcement when the selected model/transport supports it and fall back explicitly when it does not.

This task makes workflows use structured outputs appropriately; it should build on task 169 rather than duplicating provider-specific request logic inside workflow runtime code.

## Problem

Task 168 gave workflows a structured-output contract and runtime validation boundary. Task 169 is intended to teach model descriptions and LLM adapters how to expose provider-native structured-output support.

The remaining gap is orchestration:

- workflow session steps and judges declare structured `:outputs`;
- workflow runtime currently validates assistant text after generation;
- the LLM request path needs to receive the structured-output schema and strategy preference from the workflow step;
- workflow result state should record the actual strategy used;
- workflows should continue to fail fast when the final structured value is invalid.

Without this wiring, workflows cannot benefit from provider-native schema enforcement even after model adapters support it.

## Dependencies

This task depends on task 169 or equivalent capability work being complete enough to provide:

- model/provider descriptions with structured-output capability data;
- a request-level structured-output option for LLM generation;
- provider-native request construction for capable paths: OpenAI native JSON Schema from task 169, Anthropic forced-tool native support from task 169, and Anthropic JSON Schema native output from task 171 or equivalent;
- observable strategy metadata for the actual generation path used.

If task 169 is incomplete, this task should stop at design/plan refinement rather than reimplementing provider adapters locally.

## Scope

In scope:

- Pass workflow structured-output declarations from session steps to actor-turn LLM requests.
- Pass workflow judge structured-output declarations to judge-turn LLM requests.
- Choose provider-native strategy when model capabilities say it is supported.
- Use prompted JSON fallback when native support is unavailable and fallback is allowed by the workflow output declaration or default policy.
- Record the actual structured-output strategy used in workflow step/judge result envelopes.
- Preserve local parse/coerce/validate as the final runtime gate before exposing structured values downstream.
- Ensure invalid or unsupported structured-output behavior is explicit and does not silently drive control flow.
- Migrate at least one real workflow/judge path or example to exercise provider-native-capable structured output.
- Update workflow docs to explain strategy selection and fallback behavior from an author perspective.

## Explicitly out of scope

- Implementing provider-native OpenAI or Anthropic adapter support. That belongs to task 169/task 171 provider-adapter capability work.
- Making every workflow step structured.
- Removing text-mode workflow steps or prose summaries.
- Removing local validation after provider-native generation.
- Broad migration of all workflows.
- Treating ChatGPT/Codex backend as OpenAI public `/v1/responses` without capability evidence.

## Acceptance

1. A workflow session step with structured `:outputs` sends its schema contract into the LLM request path.
2. A workflow LLM judge with structured `:outputs` sends its schema contract into the judge LLM request path.
3. When the selected model/transport supports provider-native structured output, workflow execution uses that strategy and records `:provider-native` in the structured-output result metadata.
4. When provider-native support is unavailable but fallback is allowed, workflow execution uses prompted JSON fallback and records `:prompted-json`.
5. When provider-native support is required but unavailable, workflow execution fails clearly before or during the step rather than silently degrading to prose.
6. Local runtime validation still gates downstream structured values in all strategies.
7. Downstream source resolution behavior from task 168 remains unchanged: only valid structured `:value` fields are exposed.
8. Focused tests cover provider-native strategy selection, fallback strategy selection, unsupported/no-fallback failure, and invalid-output fail-fast behavior.
9. At least one representative workflow or workflow test fixture demonstrates a structured judge or session step using the provider-native-capable path.
10. Documentation explains how workflow authors opt into structured output, how strategy selection works, and when to require native support versus allow fallback.

## Design constraints

- Workflow runtime should not know provider-specific request field details. It should pass a provider-neutral structured-output contract to the turn execution layer.
- Keep task 168's result envelope semantics stable unless a small extension is required to store actual strategy metadata from the LLM request.
- Prefer capability-driven strategy selection over provider-name checks.
- Preserve replay/debuggability: persisted workflow results should say which strategy was used and include raw output or native payload information needed to inspect failures.
- Fallback policy must be explicit enough to avoid surprising downgrades for workflows that require strong schema enforcement.
- Judges should remain able to route by validated `:decision` from structured output. Invalid structured judge output should route to explicit failure, not no-match prose retry loops unless a structured retry policy is deliberately implemented.

## Proposed workflow authoring extension

The existing task 168 structured output declaration should gain or use a policy equivalent to:

```edn
:outputs
{:review {:source :judge/structured-output
          :schema-id :psi.workflow/judge-review-result
          :schema-version 1
          :schema [...]
          :strategy-preference :provider-native
          :fallback :prompted-json}}
```

Possible policy values:

- `:strategy-preference :provider-native` — prefer native when available, otherwise use declared fallback.
- `:require-provider-native? true` — fail if selected model/transport cannot enforce natively.
- `:fallback :prompted-json` — allow prompted JSON fallback.
- `:fallback :none` — no fallback; unsupported native capability is an error.

The exact keys may be adjusted to match current schema conventions, but the task must make the behavior unambiguous.

## Runtime flow

For a structured workflow step or judge:

1. Resolve the structured-output spec from the step/judge `:outputs`.
2. Convert or resolve the schema into the provider-neutral request contract expected by task 169.
3. Ask turn execution to generate with that structured-output contract.
4. Receive text or native structured payload plus actual strategy metadata.
5. Build the task 168 structured-output envelope using the actual strategy.
6. Validate locally.
7. Expose downstream structured value only if valid.
8. Block/fail explicitly on invalid output or unsupported required capability.

## Testing requirements

Focused tests should cover:

- session step passes structured-output contract into turn execution;
- judge step passes structured-output contract into judge turn execution;
- provider-native capable fake model records `:provider-native` in workflow output envelope;
- fallback-only fake model records `:prompted-json` when fallback is allowed;
- required-native fake model fails clearly when only fallback is available;
- invalid provider-native payload still fails local validation;
- downstream structured refs still work after provider-native generation;
- replay or persisted result handling does not re-run model generation merely to recover strategy metadata.

## Documentation requirements

Update workflow documentation to explain:

- task 168 schema declaration remains the author-facing contract;
- task 169 model capabilities decide whether native enforcement is possible;
- how to prefer native but allow fallback;
- how to require native enforcement;
- what strategy metadata appears in workflow run state;
- why local validation still applies.

## Risks

- Strategy metadata may currently be unavailable from turn execution. If so, this task should add the smallest explicit return field rather than inferring from model id.
- Some provider-native APIs return structured values outside normal assistant text. The workflow envelope may need to preserve both raw text and native payload while keeping downstream `:value` stable.
- Overusing required-native mode could make workflows less portable across models. Documentation should recommend requiring native only when the stronger guarantee matters.
