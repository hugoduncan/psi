# Plan

Implement Anthropic JSON Schema structured output as a separate native mechanism from synthetic forced tool use.

## Approach

1. Extend structured-output capability vocabulary to accept `:anthropic/json-schema-output`.
2. Update built-in Anthropic model capability assignment so only documented catalog keys use JSON Schema native output by default; older Claude 3.5 entries keep the forced-tool native compatibility path.
3. Update Anthropic request construction so selected mechanism controls request shape:
   - `:anthropic/json-schema-output` adds `output_format` and the structured-output beta header;
   - `:anthropic/forced-tool-use` keeps the existing synthetic tool plus `tool_choice` path;
   - `:prompted-json` keeps prompt injection only.
4. Update Anthropic streaming extraction so JSON Schema output text is accumulated and emitted as first-class `:structured-output-result` metadata with source `:anthropic/json-schema-output`.
5. Add or update focused provider/model tests before broad verification.
6. Update the named docs/task dependency targets from `design.md`: `components/ai/README.md`, `doc/custom-providers.md`, and `munera/open/170-workflow-provider-native-structured-output/design.md`.
7. Add a guarded live smoke helper/test that uses the Anthropic provider `:api-key`/`ANTHROPIC_API_KEY` seam, skips without credentials, treats OAuth only as a token supplied through that seam, and records only non-secret outcome metadata.

## Decisions

- JSON Schema native output is the preferred provider-native Anthropic mechanism when a model declares both JSON Schema output and forced-tool compatibility.
- Forced tool use remains available by selecting a forced-tool-only capability in model/user-model metadata; task 171 does not add a separate request override key.
- The adapter must not claim `:provider-native` unless the outbound request contains the selected native schema constraint.
- Local validation remains authoritative after provider-native response extraction.

## Risks

- Anthropic beta/header or request field names may change. Unit tests should pin the names used here, and live smoke results should record the observed behavior without secrets.
- Streaming JSON Schema output may arrive as ordinary text deltas; extraction must preserve text events while also emitting structured metadata at completion.
- Existing task 169 forced-tool tests must be deliberately retargeted to a forced-tool-only model fixture or older catalog key.

## Verification

Focused verification:

```sh
clojure -M:test --focus psi.ai.providers.anthropic-structured-output-test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test
```

Broader AI verification when focused tests pass:

```sh
clojure -M:test --focus psi.ai.providers.openai-structured-output-test --focus psi.ai.providers.anthropic-structured-output-test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test
```

Live smoke is opt-in and skipped unless an Anthropic credential is available through the provider `:api-key` option or `ANTHROPIC_API_KEY` environment seam. OAuth is not a separate task-171 resolver requirement; an OAuth bearer token may be tested only by supplying it through the same provider seam. The smoke must not print, persist, or commit tokens.