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
8. Focused tests cover provider-native strategy selection, fallback strategy selection, unsupported/no-fallback failure, missing `:json-schema` failure, and invalid-output fail-fast behavior.
9. At least one representative workflow or workflow test fixture demonstrates a structured judge or session step using the provider-native-capable path.
10. Documentation explains how workflow authors opt into structured output, how strategy selection works, and when to require native support versus allow fallback.

## Design constraints

- Workflow runtime should not know provider-specific request field details. It should pass a provider-neutral structured-output contract to the turn execution layer.
- Keep task 168's result envelope semantics stable unless a small extension is required to store actual strategy metadata from the LLM request.
- Prefer capability-driven strategy selection over provider-name checks.
- Preserve replay/debuggability: persisted workflow results should say which strategy was used and include raw output or native payload information needed to inspect failures.
- Fallback policy must be explicit enough to avoid surprising downgrades for workflows that require strong schema enforcement.
- Judges should remain able to route by validated `:decision` from structured output. Invalid structured judge output should route to explicit failure, not no-match prose retry loops unless a structured retry policy is deliberately implemented.

## Workflow structured-output policy and request contract

Task 170 uses these exact normalized structured-output spec keys for session-step and LLM-judge `:outputs` entries:

```edn
:outputs
{:review {:source :judge/structured-output
          :mode :structured
          :schema-id :psi.workflow/judge-review-result
          :schema-version 1
          :schema [...]
          :json-schema {:type "object"
                        :additionalProperties false
                        :required ["decision" "issues" "confidence"]
                        :properties {"decision" {:type "string"
                                                   :enum ["clear" "needs-work" "unclear"]}
                                     "issues" {:type "array" :items {...}}
                                     "confidence" {:type "number"}}}
          :strategy-preference :provider-native
          :fallback :prompted-json
          :require-provider-native? false}}
```

Canonical policy keys:

- `:json-schema` — required for provider-native and prompted-JSON AI request shaping. It is the JSON Schema map passed into the AI request as `[:structured-output :json-schema]`.
- `:strategy-preference` — optional enum. `:provider-native` means prefer provider-native enforcement when the resolved model/transport supports it. Omitted defaults to `:provider-native` for structured workflow outputs.
- `:fallback` — optional enum. `:prompted-json` allows adapter-owned prompted JSON fallback; `:none` forbids fallback. Omitted defaults to `:prompted-json`.
- `:require-provider-native?` — optional boolean. When true, fallback is forbidden regardless of `:fallback`, and unsupported native capability is a clear workflow failure. Omitted defaults to false.

The workflow runtime converts this to the provider-neutral task-169 AI request shape:

```edn
{:structured-output
 {:schema-id schema-id
  :schema-version schema-version
  :json-schema json-schema
  :strategy-preference strategy-preference
  :fallback-allowed? (and (not require-provider-native?) (= :prompted-json fallback))
  :strict? true}}
```

The request-building helper is intentionally capability-blind. It may fail immediately for local contract errors such as missing `:json-schema`; for `:require-provider-native? true`, it encodes `:fallback-allowed? false` and passes the request onward. The clear unsupported-native failure belongs at the turn execution / AI structured-output strategy-selection boundary after the model, transport, auth path, and effective structured-output capability are resolved. Workflow execution must propagate that AI failure as a workflow step/judge failure before downstream routing; it must not reinterpret it as prose output or retry via prompted JSON.

Workflow runtime and docs should not introduce provider-specific request keys such as OpenAI `:response_format`, Anthropic `:output_format`, or forced tool definitions. Provider adapters own those translations.

## Runtime flow

For a structured workflow step or judge:

1. Resolve the single structured-output spec from the step/judge `:outputs`.
2. Require the spec to include both the Malli `:schema` for workflow-local validation/coercion and `:json-schema` for AI request shaping.
3. Build the provider-neutral task-169 request under `:structured-output` using the canonical keys above.
4. Ask turn execution to generate with that structured-output contract.
5. Receive text or native structured payload plus actual AI structured-output metadata.
6. Build the workflow structured-output envelope using the actual strategy and metadata.
7. Validate locally against the Malli schema.
8. Expose downstream structured value only if valid.
9. Block/fail explicitly on invalid output or unsupported required capability.

## JSON Schema source boundary

Task 170 does **not** convert arbitrary Malli schemas to JSON Schema. Workflow structured-output specs must provide an explicit authored or registry-resolved `:json-schema` alongside the Malli `:schema`.

Boundary rules:

- `:schema` remains the workflow-runtime validation and coercion contract from task 168.
- `:json-schema` is the provider/request contract required by task 169.
- Reusable schema registries may return a paired Malli schema and JSON Schema for known `[:schema-id :schema-version]` pairs, but task 170 must not infer JSON Schema from Malli at the AI request boundary.
- If a structured-output spec reaches execution without `:json-schema`, workflow execution fails clearly before model generation with an error equivalent to `:missing-json-schema`.
- Tests should use paired schemas in fixtures so provider-native and fallback request shaping both prove the explicit JSON Schema handoff.

## Workflow envelope metadata mapping

The workflow structured-output envelope keeps `:value` as the only downstream data surface, but persists enough metadata for replay/debugging. For both session steps and LLM judges, map AI structured-output metadata as follows:

- `[:structured-output :strategy]` is the actual AI strategy: `:provider-native`, `:prompted-json`, `:repair-parse`, or `:unsupported`.
- `[:structured-output :native-mechanism]` is copied when the AI result reports one, such as `:openai/chat-completions-json-schema-response-format`, `:anthropic/forced-tool-use`, or `:anthropic/json-schema-output`.
- `[:structured-output :source]` is copied when present, such as `:openai/message-content`, `:anthropic/output-format`, `:anthropic/tool-input`, or `:prompted-json/text`.
- `[:structured-output :fallback-used?]` is copied when present; otherwise derive true only for `:strategy :prompted-json`.
- `[:structured-output :payload]` stores the parsed/native structured value before Malli coercion. Copy it from AI metadata `[:structured-output :payload]` when present; otherwise use the parsed prompted-JSON object produced from raw text. This is the authoritative validation input before local coercion.
- `[:structured-output :raw-payload]` stores raw provider payload text/bytes/maps only when AI metadata reports `:raw-payload`; it must not be populated with the parsed/native object merely for convenience. This preserves task-169/171 meaning: `:payload` is parsed/native data, `:raw-payload` is raw provider text or raw provider payload.
- top-level `:raw-output` remains the raw assistant text when available. If a native provider returns only structured payload and no text, `:raw-output` may be nil and `:payload` is authoritative for validation input.
- Provider-specific diagnostic maps may be kept under `[:structured-output :provider-metadata]`, but downstream source resolution must ignore them.

Local validation still determines `[:structured-output :status]` and `[:structured-output :value]`. Downstream `{:step ... :output ...}` resolution continues to read only valid `:value`; it must not read `:payload`, `:raw-payload`, `:parsed-value`, `:provider-metadata`, or provider text.


## Structured-output failure surfaces

Structured-output request-shaping failures and AI strategy-selection failures must use the existing workflow failure surfaces, with stable machine-readable reasons.

### Session-step failures

For a session step, local request-shaping failures before model generation, invalid structured output after generation, and AI structured-output strategy-selection failures all record a pending actor result with the blocked surface rather than a generic thrown failure:

```edn
{:kind :blocked
 :step-id step-id
 :attempt-id attempt-id
 :payload
 {:outcome :blocked
  :blocked {:reason reason
            :message message
            :details details}
  :outputs outputs}}
```

Reasons are:

- `:missing-json-schema` when a structured-output spec has a Malli `:schema` but no explicit `:json-schema`. This is detected before model generation. `:details` includes at least `:output-key`, `:schema-id`, and `:schema-version` when available.
- `:unsupported-structured-output` when the AI layer resolves the model/transport/auth capability and cannot satisfy a fallback-forbidden `:require-provider-native? true` request. `:details` includes `:output-key`, requested strategy/fallback policy, and any AI error data such as resolved model/provider/native mechanism candidates.
- `:invalid-structured-output` when generation occurred but local Malli parse/coerce/validation rejects the payload. This keeps the task-168 surface and includes the invalid structured-output envelope in `[:blocked :details :structured-output]`.

All three are `:blocked`, not `:failed`, because the workflow cannot safely continue or route, but the run state should preserve an inspectable step result and blocking reason. Runtime exceptions unrelated to the structured-output contract may still use the existing `:failure` path.

### LLM-judge failures

For an LLM judge, request-shaping failures before model generation, invalid structured judge output, and AI structured-output strategy-selection failures return a judge result with `:routing-result {:action :fail ...}`. They do not enter prose no-match retry loops.

```edn
{:judge-session-id judge-session-id
 :judge-output {output-key structured-result-or-error-envelope}
 :judge-event nil
 :routing-result {:action :fail
                  :reason reason
                  :output-key output-key
                  :details details}}
```

Reasons mirror session steps: `:missing-json-schema`, `:unsupported-structured-output`, and `:invalid-structured-output`. Invalid structured judge output keeps the existing `:invalid-structured-output` action/fail behavior and adds details sufficient to inspect the structured-output envelope. Missing JSON Schema and unsupported required-native failures are terminal judge failures for the current workflow run; the statechart records them through the existing failed terminal outcome with the judge output and reason, not as `:no-match`.

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
