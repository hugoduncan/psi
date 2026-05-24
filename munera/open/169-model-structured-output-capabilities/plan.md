# Plan

## Approach

Implement the provider/model capability slice in the AI component first, then expose the strategy result to callers without changing workflow behavior yet.

This task will not introduce the public OpenAI `/v1/responses` transport. The first OpenAI native mechanism is Chat Completions `response_format {:type "json_schema" ...}` on explicit `:openai-completions` model descriptions that declare that mechanism. `:openai-codex-responses` remains fallback-only until its backend schema contract is verified separately.

## Target files

Likely implementation targets:

- `components/ai/src/psi/ai/schemas.clj`
- `components/ai/src/psi/ai/models.clj`
- `components/ai/src/psi/ai/model_registry.clj`
- `components/ai/src/psi/ai/user_models.clj`
- `components/ai/src/psi/ai/providers/openai/chat_completions.clj`
- `components/ai/src/psi/ai/providers/openai/codex_responses.clj`
- `components/ai/src/psi/ai/providers/anthropic.clj`
- `components/ai/src/psi/ai/schemas.clj` (`StreamEventType` must include `:structured-output-strategy` and `:structured-output-result`)
- `components/ai/src/psi/ai/streaming.clj` or adjacent stream event helpers
- `components/ai/README.md`
- `doc/custom-providers.md`

Likely tests:

- `components/ai/test/psi/ai/model_registry_test.clj`
- `components/ai/test/psi/ai/user_models_test.clj`
- `components/ai/test/psi/ai/providers/openai_test.clj`
- `components/ai/test/psi/ai/providers/anthropic_test.clj`
- focused tests for any new structured-output helper namespace

## Sequence

1. Add declarative structured-output capability schemas and model/user-model validation.
2. Add request option validation/helpers for `:structured-output` and JSON-Schema-compatible payloads.
3. Add strategy selection helper that consumes model capability plus request fallback policy and returns one of `:provider-native`, `:prompted-json`, or `:unsupported` with a reason. Treat `:supported?` as "at least one declared structured-output path exists," not as provider-native support; fallback-only models are supported only when the request allows fallback, while `:supported? false` always selects `:unsupported`.
4. Add deterministic prompted-JSON fallback request shaping: when `:prompted-json` is selected, inject provider-neutral JSON-only/schema instructions at the AI adapter boundary, preserve caller text, avoid all provider-native schema fields, and report `:fallback-used? true`; when fallback is disallowed, report/fail `:unsupported` without injecting fallback instructions.
5. Wire OpenAI Chat Completions provider-native request construction via `response_format` JSON Schema only when model capability declares `:openai/chat-completions-json-schema-response-format`.
6. Preserve Codex Responses fallback-only behavior: no unverified public OpenAI schema fields on `:openai-codex-responses`.
7. Wire Anthropic forced synthetic tool use via `tools` + `tool_choice`, then extract the synthetic tool input as `[:structured-output :payload]` while excluding it from ordinary assistant tool calls.
8. Emit/store strategy metadata for non-streaming calls, and for streaming calls emit first-class `:structured-output-strategy` and `:structured-output-result` events added to `psi.ai.schemas/StreamEventType`; provider-capture callbacks may duplicate metadata for diagnostics but are not the caller contract.
9. Keep local parse/coerce/validate in the caller/runtime after provider extraction; AI adapters return raw/extracted payload metadata, not final trusted workflow values, and this task does not add an AI-level Malli validation invocation seam.
10. Update docs for capabilities, caveats, and fallback semantics.
11. Run focused tests, then broader AI component tests if focused changes pass.

## Verification commands

Focused first:

```bash
clojure -M:test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test
clojure -M:test --focus psi.ai.providers.openai-test --focus psi.ai.providers.anthropic-test
```

Then broader if needed:

```bash
bb clojure:test:unit
```

## Risks and decisions

- OpenAI public Responses API support is intentionally deferred; adding a fourth API enum is larger than needed for this capability slice and would risk transport churn.
- ChatGPT/Codex backend compatibility is unknown; it must not receive public Chat Completions/Responses schema fields.
- Anthropic synthetic tool names must avoid user-tool collisions deterministically.
- Strategy metadata must be explicit; callers must not infer provider-native use from provider/model names or outbound request shape. Streaming callers read first-class `:structured-output-strategy` and `:structured-output-result` events, not provider-capture callbacks.
- Provider-native enforcement does not replace local validation; provider adapters extract and report payloads, while workflow/runtime validation remains the final authority. Task 169 tests the handoff metadata/payload preservation, not workflow validation invocation.
