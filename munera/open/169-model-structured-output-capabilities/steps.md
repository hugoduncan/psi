# Implementation steps

- [ ] Add structured-output capability schemas and model registry support.
- [ ] Add request structured-output contract helpers and strategy selection.
- [ ] Implement OpenAI Chat Completions JSON Schema `response_format` request construction for explicitly capable models.
- [ ] Ensure OpenAI Codex Responses and other fallback-only paths never receive unverified provider-native schema fields.
- [ ] Implement Anthropic synthetic forced tool structured output composition and extraction.
- [ ] Add explicit structured-output strategy metadata for non-streaming results and streaming events.
- [ ] Preserve local validation authority after provider extraction.
- [ ] Update provider/model documentation.
- [ ] Add focused tests for model capability validation, OpenAI request shape, Anthropic request shape, fallback/unsupported behavior, and strategy metadata.
- [ ] Run focused verification commands and record results in `implementation.md`.
