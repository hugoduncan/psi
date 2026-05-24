# 169 — Model structured output capabilities

## Intent

Make Psi's model descriptions and LLM API adapters explicitly represent and use provider-native structured-output capabilities, so callers can request schema-constrained output through the strongest supported mechanism instead of relying only on prompted JSON plus runtime validation.

This is the provider/model capability slice that should precede broader workflow adoption.

## Problem

Task 168 added workflow runtime structured-output validation, but the enforcement currently happens after normal LLM text generation. The runtime parses the returned text as JSON and validates it locally. This prevents invalid structured data from driving workflow control flow, but it does not ask the model API to enforce the schema during generation.

OpenAI and Anthropic have provider-level mechanisms that can improve reliability:

- OpenAI platform APIs support structured outputs through JSON Schema response formats and/or strict tool/function schemas on capable models/transports.
- Anthropic supports structured output most naturally through forced tool use with an `input_schema`.

Psi model descriptions do not yet expose a clear capability contract for this, and LLM request builders do not yet choose provider-native structured-output mechanisms based on model/transport capability.

Without a capability surface, workflows cannot know whether `:strategy :provider-native` is actually available, and adapters risk conflating distinct OpenAI transports such as public `/v1/responses`, public chat completions, and ChatGPT/Codex backend APIs.

## Scope

In scope:

- Add structured-output capability fields to model/provider descriptions.
- Represent support at the right granularity: provider, transport, model, and auth path where needed.
- Add a request-level structured-output option that can carry a schema contract from callers to LLM adapters.
- Implement provider-native structured-output support for capable OpenAI API paths.
- Implement provider-native structured-output support for capable Anthropic API paths.
- Preserve prompted JSON/runtime validation fallback for models or transports without native support.
- Record or return the actual strategy used for a request: `:provider-native`, `:prompted-json`, `:repair-parse`, or `:unsupported` as applicable.
- Add focused tests proving request construction and capability selection for OpenAI, Anthropic, and unsupported/fallback models.
- Document capability semantics and transport caveats.

## Explicitly out of scope

- Rewriting all OpenAI traffic to `/v1/responses` in this task unless it is the smallest required route for provider-native structured output on an already-supported platform OpenAI path.
- Assuming ChatGPT/Codex backend supports the same fields as public OpenAI `/v1/responses` without verification.
- Migrating workflows to request provider-native structured output. That is task 170.
- Removing local runtime validation. Provider-native enforcement supplements but does not replace local validation.
- Supporting every local model runtime's grammar/schema features.
- Building a user-facing schema registry UI.

## Acceptance

1. Model descriptions can state whether structured output is supported and through which mechanism.
2. Capability data distinguishes at least these conceptual mechanisms:
   - OpenAI JSON Schema response format or equivalent platform structured-output mechanism;
   - OpenAI strict tool/function schema if used as a structured-output mechanism;
   - Anthropic forced tool use with `input_schema`;
   - prompted JSON fallback only;
   - unsupported.
3. Capability selection is transport-aware. Public OpenAI platform APIs and ChatGPT/Codex backend APIs are not treated as interchangeable unless verified by tests or live capability checks.
4. LLM request options can include a structured-output contract containing at least schema identity/version and a JSON-Schema-compatible schema payload or converted schema.
5. OpenAI capable paths include the provider-native structured-output fields in the outbound request and report `:provider-native` as the used strategy.
6. Anthropic capable paths include a forced synthetic tool call or equivalent native mechanism using `input_schema`, and report `:provider-native` as the used strategy.
7. Unsupported paths do not pretend to use native enforcement. They either fall back to prompted JSON when allowed or report unsupported clearly.
8. Runtime-local validation remains in place after provider response handling.
9. Tests cover OpenAI request shape, Anthropic request shape, fallback request shape, and strategy reporting.
10. Documentation explains which model/provider descriptions are authoritative for structured-output support.

## Design constraints

- Prefer explicit capability data over provider-name heuristics.
- Do not mark a request as `:provider-native` unless the outbound API request actually carried a provider-native schema constraint.
- Keep model descriptions declarative. Adapter code should consume capabilities rather than encode large hard-coded provider conditionals wherever avoidable.
- Preserve current working ChatGPT OAuth/Codex behavior for `openai/gpt-5.5` unless native structured output support is verified for that backend.
- Convert Malli/domain schemas to provider-compatible JSON Schema at the API boundary. Keep the workflow/runtime schema authority in Psi domain terms.
- Always keep local parse/coerce/validate as the final authority before structured data is exposed downstream.
- Make the actual strategy used observable in request result metadata or an equivalent traceable surface.

## Proposed capability shape

The exact shape should align with current model registry conventions, but conceptually model descriptions should be able to express:

```edn
{:id "openai/gpt-4.1"
 :provider :openai
 :transport :openai-responses
 :auth :platform-api-key
 :capabilities
 {:structured-output
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :openai/response-format-json-schema}}}
```

For Anthropic:

```edn
{:id "anthropic/claude-sonnet-4"
 :provider :anthropic
 :transport :anthropic-messages
 :capabilities
 {:structured-output
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :anthropic/forced-tool-use}}}
```

For a fallback-only or unknown transport:

```edn
{:id "openai/gpt-5.5"
 :provider :openai
 :transport :openai-codex-responses
 :auth :chatgpt-oauth
 :capabilities
 {:structured-output
  {:supported? true
   :strategies [:prompted-json]
   :native-mechanism nil
   :notes "Do not assume public OpenAI Responses API fields are supported."}}}
```

## Request contract

The LLM request path should support a structured-output option equivalent to:

```edn
{:structured-output
 {:schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :schema [:map ...]
  :json-schema {...}
  :name "judge_review_result"
  :strict? true
  :fallback-allowed? true}}
```

Adapters may derive `:json-schema` from `:schema` if the conversion is part of this task. If conversion is too broad for the first cut, this task should support the subset of Malli schemas needed by `:psi.workflow/judge-review-result` and record unsupported schema forms clearly.

## Provider behavior

### OpenAI

Use provider-native schema fields only on verified capable OpenAI platform transports. Prefer public `/v1/responses` where that is already the selected or introduced platform transport for the target model. Do not assume ChatGPT/Codex backend compatibility.

The outbound request should carry a JSON Schema response constraint or strict tool/function schema, depending on the chosen OpenAI mechanism and adapter architecture.

### Anthropic

Use a synthetic forced tool call as the native structured-output mechanism. The tool should have a deterministic name derived from the schema or request, an `input_schema` derived from the requested output schema, and tool choice should require that tool. The adapter should extract the tool input as the structured output payload.

## Testing requirements

Focused tests should prove:

- model descriptions validate with structured-output capability fields;
- OpenAI capable model request construction includes native schema constraint fields;
- ChatGPT/Codex or unsupported OpenAI paths do not receive unverified public API schema fields;
- Anthropic capable request construction includes forced tool use with `input_schema`;
- fallback-only models report/use `:prompted-json` when fallback is allowed;
- unsupported/no-fallback requests fail clearly;
- local validation is still invoked after provider response extraction;
- strategy metadata distinguishes provider-native from prompted fallback.

## Documentation requirements

Update model/provider documentation to explain:

- how structured-output capability is declared;
- how provider-native and prompted fallback differ;
- why OpenAI public Responses API and ChatGPT/Codex backend must be treated as distinct transports;
- how Anthropic forced-tool structured output is represented;
- why local validation remains mandatory.

## Risks

- Provider APIs differ across model families and may change. Keep the capability model versionable and test request shapes closely.
- JSON Schema conversion from Malli may be partial. Unsupported schema forms should fail explicitly rather than silently weakening constraints.
- Native schema enforcement can still fail or produce refusal/error paths; callers must continue to handle invalid or absent structured outputs.
