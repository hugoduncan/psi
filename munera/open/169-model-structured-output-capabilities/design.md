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
8. Runtime-local validation remains the caller/workflow responsibility after provider response handling; this task proves AI results preserve extracted/raw payload metadata for that later validation rather than adding a new AI-level validation seam.
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

The exact shape should align with current model registry conventions. For this first implementation slice the transport identity is the existing `:api` enum, not a new `:transport` key. Add a nested `:capabilities :structured-output` map to model descriptions and user model definitions.

For OpenAI, do not introduce a new public `/v1/responses` API enum in this task. The first provider-native OpenAI mechanism is Chat Completions JSON Schema response format on explicitly capable `:openai-completions` models:

```edn
{:id "openai/gpt-4.1"
 :provider :openai
 :api :openai-completions
 :capabilities
 {:structured-output
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :openai/chat-completions-json-schema-response-format}}}
```

A future task may add `:openai-responses` after the transport is implemented and tested; until then the design's public `/v1/responses` preference is satisfied only by deferral, not by pretending the current adapters are equivalent.

For Anthropic:

```edn
{:id "anthropic/claude-sonnet-4"
 :provider :anthropic
 :api :anthropic-messages
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
 :api :openai-codex-responses
 :capabilities
 {:structured-output
  {:supported? true
   :strategies [:prompted-json]
   :native-mechanism nil
   :notes "Do not assume public OpenAI Responses API fields are supported."}}}
```

If the same user-visible model id can run through different auth paths/transports, each resolved runtime model has its own effective structured-output capability. The auth path is resolver context that determines the resolved model's final transport and capability; it is not stored as a new `:auth` key on the model map in this task. For example, a platform-auth `openai/gpt-5.5` catalog entry may later declare Chat Completions native support, but the OAuth-backed runtime override to `:openai-codex-responses` must clear or replace that native capability with the Codex fallback-only/unsupported capability shown above.

For a model or transport that should not accept structured-output requests at all:

```edn
{:capabilities
 {:structured-output
  {:supported? false
   :strategies []
   :native-mechanism nil
   :notes "Structured-output requests are not supported for this model/transport."}}}
```

## Capability semantics

`:supported?` means the model description declares at least one structured-output request path that Psi may intentionally use for this model/transport. It is not synonymous with provider-native enforcement. Provider-native support is present only when `:strategies` contains `:provider-native` and `:native-mechanism` names the concrete provider mechanism.

Fallback-only models set `:supported? true`, `:strategies [:prompted-json]`, and `:native-mechanism nil`. They can satisfy a structured-output request only when the request permits fallback. If `:fallback-allowed? false` is requested against a fallback-only model, request-time strategy selection returns `:unsupported` with a clear reason; the model description itself remains fallback-capable rather than globally unsupported.

Unsupported models set `:supported? false`, `:strategies []`, and `:native-mechanism nil`. Strategy selection returns `:unsupported` regardless of fallback policy, because the model/provider description does not declare any acceptable structured-output path.

Absent structured-output capability data is a distinct compatibility state for existing built-in and user/custom model descriptions that omit `[:capabilities :structured-output]` entirely. Omission is valid input for this task so existing configurations do not break at load time. Registry/user-model normalization should default the effective structured-output capability to unsupported:

```edn
{:supported? false
 :strategies []
 :native-mechanism nil
 :defaulted? true
 :notes "No structured-output capability was declared for this model/transport."}
```

The original persisted/user description does not need to be rewritten by this task, but all strategy-selection code must consume a normalized/effective capability map so missing data is treated exactly like unsupported for request-time behavior. In particular, a structured-output request against an omitted-capability model returns `:unsupported` even when `:fallback-allowed? true`; fallback-only behavior requires an explicit declaration of `:strategies [:prompted-json]`. This keeps prompted fallback opt-in rather than silently mutating prompts for every legacy/custom model. Documentation should tell users to add an explicit fallback-only capability when they want a custom model to accept structured-output requests through prompted JSON.

Request-time support is therefore the combination of the normalized model capability declaration and the request fallback policy:

- native-capable + native request => `:provider-native`;
- native-capable + fallback allowed but native unavailable for the concrete transport/schema => `:prompted-json` only when listed in `:strategies`;
- fallback-only + fallback allowed => `:prompted-json`;
- fallback-only + fallback disallowed => `:unsupported`;
- unsupported capability, including omitted capability data normalized to unsupported => `:unsupported`.

Runtime auth-path or transport resolution happens before structured-output strategy selection. The effective capability used by strategy selection must be derived from the resolved runtime model, including its final `:api`, `:base-url`, and any capability replacement/defaulting performed by the resolver. Auth-path-specific decisions, such as ChatGPT OAuth routing, are applied by the resolver while producing that model; this task does not add or require a runtime-only `:auth` marker in the model schema. If a resolver changes a model from a public OpenAI platform transport to `:openai-codex-responses`, as `resolve-runtime-model` does for OAuth-backed `openai/gpt-5.5`, the resolved model must not retain platform Chat Completions native structured-output capability. It should either replace `[:capabilities :structured-output]` with an explicit Codex-safe fallback-only capability when prompted fallback is intentionally supported, or leave/normalize it to unsupported. Strategy selection must consume the resolved capability map rather than infer native capability from the catalog entry that existed before runtime auth/transport override or from an `:auth` field.

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

## Prompted JSON fallback behavior

When strategy selection chooses `:prompted-json`, the AI adapter owns a deterministic fallback request mutation. It must add schema-guided JSON-only instructions to the outbound provider request rather than merely reporting fallback metadata or relying on callers to have already written a JSON prompt. Caller/runtime prompts may add domain context, but the adapter-owned fallback instruction is the minimum contract that makes a structured-output request observable and testable.

Fallback instruction composition:

- append a provider-neutral instruction block to the request messages/content at the AI adapter boundary, preserving the caller's original prompt text;
- include the structured-output `:name`, `:schema-id`, `:schema-version`, and JSON-Schema-compatible schema;
- require a single JSON object matching the schema, with no Markdown fences, prose wrapper, or extra top-level text;
- mention that local runtime validation remains authoritative;
- do not add provider-native fields such as OpenAI `response_format`, Responses `text.format`, strict tool schemas, or Anthropic forced `tool_choice`.

The exact insertion point follows each provider adapter's existing message-building conventions. For chat/message APIs, prefer an appended user-visible instruction segment on the final user/request content, or the nearest existing provider-neutral prompt augmentation seam. Do not silently replace caller text.

Fallback request-shape tests should assert both sides of the contract: the outbound request contains the adapter-owned JSON-only/schema instruction, and it contains no native schema-enforcement field. Strategy metadata reports `:prompted-json` with `:fallback-used? true`. If fallback is disallowed, the adapter must fail/report `:unsupported` clearly and must not inject fallback instructions.

## Provider behavior

### OpenAI

Use provider-native schema fields only on verified capable OpenAI platform transports. In this task that means existing `:openai-completions` only, using Chat Completions `response_format` JSON Schema. The outbound body shape is:

```edn
{:response_format
 {:type "json_schema"
  :json_schema
  {:name "judge_review_result"
   :strict true
   :schema {...}}}}
```

The schema source is request `[:structured-output :json-schema]`, or a supported Malli-to-JSON-Schema conversion if implemented for the requested schema subset.

Do not add public `/v1/responses` in this task. Do not send `response_format`, Responses-style `text.format`, or strict function schema fields to `:openai-codex-responses` unless a separate verification proves that backend accepts them. Codex/ChatGPT OAuth models such as `openai/gpt-5.5` remain `:prompted-json` fallback-only for this slice.

OpenAI strict tool/function schema is represented as a possible capability mechanism but is not the first implemented native mechanism unless implementation discovers it is simpler and updates this design before coding.

### Anthropic

Use a synthetic forced tool call as the native structured-output mechanism. The adapter appends one synthetic tool definition to the ordinary user tools:

```edn
{:name "psi_structured_output__judge_review_result"
 :description "Return the requested structured output."
 :input_schema {...}}
```

Tool naming rules:

- derive the base name from request `:name` or `:schema-id`;
- sanitize to Anthropic tool-name characters;
- prefix with `psi_structured_output__`;
- if a user tool already has that name, append a deterministic numeric suffix based on the occupied names in the current request.

When provider-native structured output is selected, set `:tool_choice {:type "tool" :name synthetic-name}` so Anthropic must call the synthetic tool. Ordinary user tools may remain in `:tools` for context, but they cannot be chosen for the final response while the synthetic forced choice is active.

Response handling must separate the synthetic tool from normal assistant tool calls: a returned `tool_use` block whose name matches the synthetic name is extracted as the structured-output payload and is not surfaced downstream as an ordinary assistant tool call. Any other returned tool call remains a normal assistant tool call or provider anomaly according to existing handling.


## Strategy metadata surface

The actual structured-output strategy is observable through an explicit metadata map, never inferred from model/provider names.

For non-streaming calls, provider execution returns or associates:

```edn
{:structured-output
 {:strategy :provider-native ;; or :prompted-json, :repair-parse, :unsupported
  :native-mechanism :openai/chat-completions-json-schema-response-format
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :fallback-used? false}}
```

For streaming calls, extend the AI stream event schema with a first-class structured-output strategy event:

```edn
{:type :structured-output-strategy
 :structured-output {...}}
```

This task must add `:structured-output-strategy` to `psi.ai.schemas/StreamEventType` and provider streaming tests must assert that capable/fallback structured-output streaming requests emit this event. The event is emitted by the provider adapter after strategy selection and request construction, before provider content/tool deltas are exposed to callers. It is not represented only as a provider-capture callback. Provider request/response captures may include the same metadata for diagnostics, but captures are secondary and not the caller contract.

`:provider-native` is emitted only after the outbound request body actually includes the native schema constraint. Fallback-only requests emit `:prompted-json` when fallback is allowed. No-fallback unsupported requests emit or return `:unsupported` with a reason and must fail clearly rather than silently weakening the contract. `:repair-parse` is reserved for a later repair layer and should not be reported unless that layer actually ran.

## Structured payload result surface

Provider adapters expose provider-extracted structured payloads separately from ordinary assistant text and tool calls. The payload surface is AI-owned extraction metadata, not the final validated workflow output.

For non-streaming calls, the provider result includes or is associated with structured-output metadata shaped as:

```edn
{:structured-output
 {:strategy :provider-native
  :native-mechanism :anthropic/forced-tool-use
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :fallback-used? false
  :payload {:ok? true ...}        ;; provider-extracted structured value when available
  :raw-payload {:ok true ...}     ;; optional uncoerced provider value when distinct
  :source :anthropic/tool-use}}    ;; e.g. :openai/message-json or :prompted-json/text
```

For OpenAI Chat Completions JSON Schema response format, the payload is parsed from the assistant message content generated under the provider-native response format and attached at `[:structured-output :payload]` when extraction succeeds. The ordinary assistant text remains available for diagnostics/raw transcript needs, but downstream structured consumers read the extracted payload field rather than reparsing provider-specific message shapes.

For Anthropic forced tool use, the matching synthetic structured-output `tool_use` block's `input` becomes `[:structured-output :payload]`, with `:source :anthropic/tool-use`. That synthetic tool use is removed from the ordinary assistant tool-call surface. Only non-synthetic tool calls remain visible as assistant tool calls; a response that lacks the forced synthetic tool, or returns some other selected tool while forced choice was requested, is reported as a provider anomaly/unsupported structured-output extraction according to existing error handling.

For streaming calls, extend the AI stream event schema with a first-class structured-output result event emitted when the extraction result is known:

```edn
{:type :structured-output-result
 :structured-output
 {:strategy :provider-native
  :native-mechanism :anthropic/forced-tool-use
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :payload {...}
  :source :anthropic/tool-use}}
```

This task must add `:structured-output-result` to `psi.ai.schemas/StreamEventType`. The result event carries the extracted payload and may arrive near completion because Anthropic tool blocks and OpenAI message content are only complete at the end of the provider response. The earlier `:structured-output-strategy` event remains the request/strategy contract and must precede ordinary content/tool deltas when a structured-output request is accepted. Provider-capture callbacks may record equivalent diagnostic metadata, but callers consume the stream events as the authoritative streaming surface.

## AI/workflow validation boundary

This task makes AI adapters responsible for request construction, native strategy selection, provider-specific extraction, synthetic-tool filtering, and explicit strategy/result metadata. It does not make AI adapters the final schema authority.

AI adapters may parse JSON and may perform minimal shape checks needed to extract provider payloads safely, but they should not expose provider-native output as trusted workflow data merely because the provider accepted a schema. Malli coercion and final validation remain runtime/workflow responsibilities, completed in task 168 and wired to provider-native extraction in task 170.

If an adapter implements a small Malli-to-JSON-Schema conversion or extraction-time parse helper in this slice, failures are reported as extraction/request errors or `:unsupported` strategy reasons. Successful extraction records raw/extracted payload plus strategy metadata; validated/coerced domain values are produced only by the caller/runtime validation layer before downstream workflow references can read them. Task 169 verification is therefore limited to preserving the payload/metadata handoff contract; task 170 or existing workflow-runtime tests prove final Malli validation is invoked when workflows consume that handoff.

## Testing requirements

Focused tests should prove:

- model descriptions validate with structured-output capability fields;
- OpenAI capable model request construction includes native schema constraint fields;
- ChatGPT/Codex or unsupported OpenAI paths do not receive unverified public API schema fields;
- Anthropic capable request construction includes forced tool use with `input_schema`;
- fallback-only models report/use `:prompted-json` when fallback is allowed;
- unsupported/no-fallback requests fail clearly;
- AI provider tests prove extracted/raw payloads and strategy metadata are preserved for the existing caller/workflow validation layer; task 169 does not add or test a new AI-level Malli validation invocation seam;
- strategy metadata distinguishes provider-native from prompted fallback;
- streaming provider tests assert `:structured-output-strategy` and `:structured-output-result` events are valid `psi.ai.schemas/StreamEventType` values and are emitted on the streaming surface rather than only through provider-capture callbacks.

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
