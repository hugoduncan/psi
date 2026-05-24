# Implementation steps

- [ ] Extend workflow structured-output IR/schema validation for `:json-schema`, `:strategy-preference`, `:fallback`, and `:require-provider-native?`.
- [ ] Add workflow-runtime request-building helper for task-169 `:structured-output` options, including missing-json-schema errors and required-native fallback-forbidden request encoding.
- [ ] Extend turn execution so `:execution-result/structured-output` is populated from non-streaming provider `:structured-output` and streaming `:structured-output-strategy` / `:structured-output-result` events, and so bounded actor/judge turn results expose top-level `:structured-output` copied from that execution-result key.
- [ ] Pass structured-output request options through session-step turn execution and read AI metadata/payload only from the bounded turn result `:structured-output` seam.
- [ ] Pass structured-output request options through LLM-judge turn execution and read AI metadata/payload only from the bounded turn result `:structured-output` seam.
- [ ] Extend workflow structured-output envelopes with AI strategy metadata plus `:payload` parsed/native data and `:raw-payload` raw provider diagnostics mapping.
- [ ] Add focused workflow-runtime/agent-session tests for provider-native, fallback, unsupported fallback-forbidden behavior (`:require-provider-native? true` and `:fallback :none` both yielding `:unsupported-structured-output`), missing JSON Schema, invalid validation, downstream refs, persisted metadata behavior, bounded turn-result `:structured-output` seam usage, session-step blocked failure envelopes, and LLM-judge `:routing-result {:action :fail}` failure envelopes.
- [ ] Update workflow authoring and IR documentation for policy keys, JSON Schema boundary, strategy selection, fallback, required-native behavior, and envelope metadata.
