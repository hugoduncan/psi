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

1. Add declarative structured-output capability schemas, model/user-model validation, normalization for omitted capability data, and built-in capability declarations. Missing `[:capabilities :structured-output]` remains valid for compatibility but normalizes to effective unsupported, not fallback-only. Built-in declarations follow the design's concrete assignment: current Anthropic Messages models are forced-tool native-capable, named modern OpenAI Chat Completions models are JSON Schema response-format native-capable, OpenAI Codex Responses models are prompted-JSON fallback-only, and unverified OpenAI Chat Completions entries such as `:o1-preview`/`:codex-mini-latest` remain omitted/unsupported unless verified during implementation.
2. Ensure runtime model resolution produces the authoritative effective structured-output capability for the final transport/auth path. When `resolve-runtime-model` changes OAuth-backed `openai/gpt-5.5` to `:openai-codex-responses`, it must clear or replace any catalog platform-native capability so Codex strategy selection is fallback-only or unsupported, never inherited Chat Completions native support. Do not add a runtime-only `:auth` key to the closed model schema; auth-path-specific capability decisions are derived inside the resolver and materialized as the resolved model's final `:api`, `:base-url`, and capability map.
3. Add request option validation/helpers for `:structured-output` and JSON-Schema-compatible payloads. Task 169 requires request `[:structured-output :json-schema]` as the provider-bound schema source; `:schema` may be retained as metadata but is not converted by AI adapters in this slice. Schema-only requests select/report `:unsupported` with a clear `:missing-json-schema`-style reason.
4. Add strategy selection helper that consumes the resolved runtime model's normalized capability plus request fallback policy and returns one of `:provider-native`, `:prompted-json`, or `:unsupported` with a reason. Treat `:supported?` as "at least one declared structured-output path exists," not as provider-native support; fallback-only models are supported only when the request allows fallback, while `:supported? false`, omitted/defaulted capability data, or a missing `:json-schema` always selects `:unsupported`.
5. Add deterministic prompted-JSON fallback request shaping: when `:prompted-json` is selected, inject provider-neutral JSON-only/schema instructions at the AI adapter boundary, preserve caller text, avoid all provider-native schema fields, and report `:fallback-used? true`; when fallback is disallowed, report/fail `:unsupported` without injecting fallback instructions.
6. Wire OpenAI Chat Completions provider-native request construction via `response_format` JSON Schema only when the resolved runtime model capability declares `:openai/chat-completions-json-schema-response-format`.
7. Preserve Codex Responses fallback-only behavior: no unverified public OpenAI schema fields on `:openai-codex-responses`.
8. Wire Anthropic forced synthetic tool use via `tools` + `tool_choice`, then extract the synthetic tool input as `[:structured-output :payload]` while excluding it from ordinary assistant tool calls.
9. Emit/store strategy metadata for non-streaming calls as a top-level `:structured-output` key in the provider result map, sibling to existing `:assistant-message`/`:logprobs` entries, and for streaming calls emit first-class `:structured-output-strategy` and `:structured-output-result` events added to `psi.ai.schemas/StreamEventType`; provider-capture callbacks may duplicate metadata for diagnostics but are not the caller contract.
10. Keep local parse/coerce/validate in the caller/runtime after provider extraction; AI adapters return raw/extracted payload metadata, not final trusted workflow values, and this task does not add an AI-level Malli validation invocation seam.
11. Update docs for capabilities, caveats, and fallback semantics.
12. Run focused tests, then broader AI component tests if focused changes pass.

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
- Strategy metadata must be explicit; callers must not infer provider-native use from provider/model names or outbound request shape. Non-streaming callers read top-level result `:structured-output`; streaming callers read first-class `:structured-output-strategy` and `:structured-output-result` events, not provider-capture callbacks.
- Provider-native enforcement does not replace local validation; provider adapters extract and report payloads, while workflow/runtime validation remains the final authority. Task 169 tests the handoff metadata/payload preservation, not workflow validation invocation.
- Malli-to-JSON-Schema conversion is deferred. Provider request-shape tests should supply explicit `:json-schema`, and negative tests should prove schema-only structured-output requests fail/report `:unsupported` without native fields or fallback prompt injection.
- Existing built-in and custom model descriptions may omit structured-output capability data. The task preserves load compatibility by defaulting omitted effective capabilities to unsupported; prompted fallback remains explicit opt-in so legacy models do not receive schema-prompt injection unexpectedly.
