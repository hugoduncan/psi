# Implementation steps

- [ ] Extend workflow structured-output IR/schema validation for `:json-schema`, `:strategy-preference`, `:fallback`, and `:require-provider-native?`.
- [ ] Add workflow-runtime request-building helper for task-169 `:structured-output` options, including missing-json-schema and required-native/no-fallback errors.
- [ ] Pass structured-output request options through session-step turn execution.
- [ ] Pass structured-output request options through LLM-judge turn execution.
- [ ] Extend workflow structured-output envelopes with AI strategy metadata and raw/native payload mapping.
- [ ] Add focused workflow-runtime/agent-session tests for provider-native, fallback, unsupported/no-fallback, missing JSON Schema, invalid validation, downstream refs, and persisted metadata behavior.
- [ ] Update workflow authoring and IR documentation for policy keys, JSON Schema boundary, strategy selection, fallback, required-native behavior, and envelope metadata.
