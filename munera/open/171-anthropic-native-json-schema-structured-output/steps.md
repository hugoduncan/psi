# Steps

- [ ] Extend structured-output schemas/helpers for `:anthropic/json-schema-output`.
- [ ] Assign built-in Anthropic model capabilities: JSON Schema native for documented 4.5+ catalog keys; forced-tool native for older Claude 3.5/current non-JSON-Schema catalog keys unless separately verified.
- [ ] Implement Anthropic JSON Schema native request shape and beta/header composition without adding synthetic forced-tool fields.
- [ ] Preserve forced-tool native request behavior as a separate mechanism.
- [ ] Add Anthropic Messages non-streaming `:execute` support returning top-level `:structured-output` for JSON Schema native responses.
- [ ] Extract Anthropic JSON Schema native non-streaming/streaming structured-output metadata and payload with source `:anthropic/json-schema-output`.
- [ ] Add focused tests for JSON Schema request shape, header, metadata, response extraction, streaming result, forced-tool separation, fallback, and unsupported/missing-schema behavior.
- [ ] Add guarded live Anthropic OAuth/API smoke coverage or an executable skip path with non-secret result recording.
- [ ] Update AI documentation and task 170 dependency wording for Anthropic JSON Schema native, forced-tool native, and prompted JSON fallback paths.
- [ ] Run focused structured-output/model tests and record results in implementation notes.