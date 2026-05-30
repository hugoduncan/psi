# 190 — Fix judge structured-output validation for non-object JSON Schema outputs

## Intent

Workflow judge steps using a bare string-enum JSON Schema (e.g. `{"type":"string","enum":["REPEAT","DONE"]}`) always fail with `:invalid-structured-output`. Fix the root cause so any valid JSON value produced by the provider structured-output result surface is preserved in `:payload` and reaches `structured-output-envelope` without loss. This applies at the shared provider extraction sites for both provider-native structured-output and prompted-JSON structured-output results. JSON `null` is in scope: a successfully parsed structured-output payload of `null` must be represented as a present `:payload nil` value and must not be marked `:parse-error? true`.

Add a retry loop for the structured-output code path in the judge to match the resilience already present on the plain-text path. Structured-output retries remain structured-output retries: every retry turn must call `execute-judge-turn!` with the same structured-output opts/schema that were used for the initial judge turn.

## Context

`psi.ai.providers.anthropic.structured-output/structured-output-result` calls `psi.ai.structured-output/parse-json-object` to extract the structured-output payload. `parse-json-object` only returns maps — any non-object JSON value (string, number, boolean, array) returns `nil`, so `:payload` is never set in the structured-output event metadata.

`psi.workflow-runtime.structured-output/validation-input` checks `(contains? ai-structured-output :payload)` first. Without `:payload`, it falls back to `parse-json-value` on the raw assistant text. The raw assistant text is the model's full prose response (reasoning, tool-call XML, etc.), not just the structured output value. This multi-paragraph text fails malli validation against the judge routing schema, `[:enum "REPEAT" "DONE"]`.

The same `parse-json-object` restriction also affects `psi.ai.providers.openai.chat-completions` (two call sites). Those sites are in scope for the same fix.

The `psi.agent-session.workflow-judge` structured-output branch immediately returns `{:action :fail :reason :invalid-structured-output}` on validation failure; the plain-text branch retries up to `max-judge-retries` times. This asymmetry means a transient model misbehavior on the structured path is fatal.

## Scope

### Primary fix — JSON value extraction at provider result sites and prompted-JSON instructions

Replace every call to `parse-json-object` that extracts a structured-output payload at the provider result sites with `parse-json-value`, preserving the resulting `:payload` for any valid JSON type, including a successfully parsed JSON `null` value. Provider result code must test parse success via `:parsed?`/map presence rather than payload truthiness, so `:payload nil` is retained and not treated as absent or invalid.

These extraction sites are shared by provider-native and prompted-JSON structured-output emission. Non-object payload preservation is required for both strategies wherever the same site emits `:structured-output` metadata; source labels and strategy metadata continue to distinguish provider-native from prompted-JSON results.

Prompted-JSON fallback instructions must describe the required response as exactly one JSON value matching the supplied JSON Schema, not exactly one JSON object. The instruction still forbids Markdown fences, prose, and extra top-level text; it must allow scalar, array, object, boolean, number, string, and `null` outputs whenever the schema allows them.

Affected files:
- `components/ai/src/psi/ai/providers/anthropic/structured_output.clj` — `structured-output-result` (one call, currently used by Anthropic structured-output result emission)
- `components/ai/src/psi/ai/providers/openai/chat_completions.clj` — two call sites that build provider structured-output results for provider-native and prompted-JSON strategies
- `components/ai/src/psi/ai/structured_output.clj` — `json-only-instruction` wording for prompted-JSON fallback must say JSON value rather than JSON object

The `parse-json-object` helper itself is not removed. After the three provider result-extraction call sites move to `parse-json-value`, no non-helper call sites are expected to remain; the helper is retained as the object-only parsing API for future callers and to avoid broad API cleanup in this slice.

### Secondary fix — retry loop for structured-output judge path

In `psi.agent-session.workflow-judge`, when `valid-output-result?` returns false and the attempt count is below `max-judge-retries`, retry the judge turn (same retry-feedback injection used by the plain-text path) instead of immediately returning `:invalid-structured-output`. If the judge step has a structured-output request, the retry call to `execute-judge-turn!` must include the original structured-output opts so provider-native schema enforcement and result capture are preserved on every attempt; only steps without structured-output opts use the plain two-argument retry call.

## Constraints

- `psi.workflow-runtime.structured-output/structured-output-envelope` and `validation-input` are **not changed** — they already handle `:payload` correctly for any JSON value.
- `parse-json-object` is not removed from `psi.ai.structured-output`.
- The fix is minimal across the two root causes: provider result-extraction changes are limited to the three shared structured-output payload extraction sites (covering both provider-native and prompted-JSON emission at those sites) plus the prompted-JSON instruction wording that feeds those sites, and workflow-judge changes are limited to the structured-output validation-failure retry path.
- Existing tests for the `structured-output-envelope` non-object JSON path (e.g. `structured-output-envelope-string-enum-json-test`, `structured-output-envelope-non-object-json-test`) must remain green.

## Acceptance criteria

1. `psi.ai.providers.anthropic.structured-output/structured-output-result` with a bare-string JSON payload (e.g. `"\"DONE\""`) produces a result with `:payload "DONE"` (not `:parse-error? true`).
2. `psi.workflow-runtime.structured-output/structured-output-envelope` receiving that result with the judge routing schema (`[:enum "REPEAT" "DONE"]`) produces `:status :valid` and `:value "DONE"`.
3. The OpenAI chat-completions structured-output result sites produce `:payload` for string, number, boolean, array, and `null` JSON values for both provider-native and prompted-JSON strategies emitted by those sites; `null` is represented by a present `:payload nil` and not by `:parse-error? true`.
4. The judge retry loop fires on structured-output validation failure when `attempt < max-judge-retries`, matching the plain-text retry behavior, and each structured-output retry preserves the initial structured-output opts/schema when calling `execute-judge-turn!`.
5. Prompted-JSON fallback instructions request exactly one JSON value matching the supplied JSON Schema and do not require the top-level response to be a JSON object.
6. All existing structured-output and workflow-judge tests remain green.
7. `bb test` green.
