# Steps

- [x] Extend structured-output schemas/helpers for `:anthropic/json-schema-output`.
- [x] Assign built-in Anthropic model capabilities: JSON Schema native for documented 4.5+ catalog keys; forced-tool native for older Claude 3.5/current non-JSON-Schema catalog keys unless separately verified.
- [x] Implement Anthropic JSON Schema native request shape and beta/header composition without adding synthetic forced-tool fields.
- [x] Preserve forced-tool native request behavior as a separate mechanism.
- [x] Add Anthropic Messages non-streaming `:execute` support returning top-level `:structured-output` for JSON Schema native responses.
- [x] Extract Anthropic JSON Schema native non-streaming/streaming structured-output metadata and payload with source `:anthropic/json-schema-output`.
- [x] Add focused tests for JSON Schema request shape, header, metadata, response extraction, streaming result, forced-tool separation, fallback, and unsupported/missing-schema behavior.
- [x] Add guarded live Anthropic OAuth/API smoke coverage or an executable skip path with non-secret result recording.
- [x] Update AI documentation and task 170 dependency wording for Anthropic JSON Schema native, forced-tool native, and prompted JSON fallback paths.
- [x] Run focused structured-output/model tests and record results in implementation notes.
- [x] Add focused Anthropic JSON Schema native parse-failure tests for non-streaming and streaming invalid/non-object output, asserting `:parse-error? true`, preserved `:raw-payload`, and no `:payload`.
- [ ] Add focused Anthropic JSON Schema native beta-header composition coverage proving `structured-outputs-2025-11-13` composes with OAuth, prompt-caching, and thinking beta tokens without dropping or duplicating existing betas.
