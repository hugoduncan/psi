# Implementation steps

- [ ] Add structured-output capability schemas and model registry support, including explicit `:supported?` semantics for native-capable, fallback-only, unsupported, and omitted-capability models; omitted data must normalize to effective unsupported while preserving load compatibility.
- [ ] Add request structured-output contract helpers and strategy selection that combines model capability with request fallback policy.
- [ ] Add prompted-JSON fallback request shaping that injects deterministic schema-guided JSON-only instructions when fallback is selected, avoids provider-native fields, and reports `:fallback-used? true`; unsupported/no-fallback requests must not inject fallback instructions.
- [ ] Implement OpenAI Chat Completions JSON Schema `response_format` request construction for explicitly capable models.
- [ ] Ensure OpenAI Codex Responses and other fallback-only paths never receive unverified provider-native schema fields.
- [ ] Implement Anthropic synthetic forced tool structured output composition and extraction, exposing synthetic input as structured-output payload and hiding it from ordinary tool calls.
- [ ] Add explicit structured-output strategy metadata and extracted-payload result surfaces for non-streaming results and first-class streaming `:structured-output-strategy` / `:structured-output-result` events in `psi.ai.schemas/StreamEventType`; provider-capture callbacks may duplicate diagnostics but are not the streaming caller contract.
- [ ] Preserve local validation authority after provider extraction; adapters must not expose extracted payloads as trusted validated workflow values or add an AI-level Malli validation seam.
- [ ] Update provider/model documentation.
- [ ] Add focused tests for model capability validation, OpenAI request shape, Anthropic request shape, fallback/unsupported behavior, strategy metadata, and extracted/raw payload handoff preservation for later workflow validation.
- [ ] Run focused verification commands and record results in `implementation.md`.
