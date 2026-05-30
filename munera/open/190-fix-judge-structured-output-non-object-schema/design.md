# 190 — Fix judge structured-output validation for non-object JSON Schema outputs

## Intent

Workflow judge steps using a bare string-enum JSON Schema (e.g. `{"type":"string","enum":["REPEAT","DONE"]}`) always fail with `:invalid-structured-output`. Fix the root cause so any valid JSON value produced by the provider's native structured-output surface is preserved in `:payload` and reaches `structured-output-envelope` without loss.

Add a retry loop for the structured-output code path in the judge to match the resilience already present on the plain-text path.

## Context

`psi.ai.providers.anthropic.structured-output/structured-output-result` calls `psi.ai.structured-output/parse-json-object` to extract the native payload. `parse-json-object` only returns maps — any non-object JSON value (string, number, boolean, array) returns `nil`, so `:payload` is never set in the structured-output event metadata.

`psi.workflow-runtime.structured-output/validation-input` checks `(contains? ai-structured-output :payload)` first. Without `:payload`, it falls back to `parse-json-value` on the raw assistant text. The raw assistant text is the model's full prose response (reasoning, tool-call XML, etc.), not just the structured output value. This multi-paragraph text fails malli validation against `[:enum :REPEAT :DONE]`.

The same `parse-json-object` restriction also affects `psi.ai.providers.openai.chat-completions` (two call sites). Those sites are in scope for the same fix.

The `psi.agent-session.workflow-judge` structured-output branch immediately returns `{:action :fail :reason :invalid-structured-output}` on validation failure; the plain-text branch retries up to `max-judge-retries` times. This asymmetry means a transient model misbehavior on the structured path is fatal.

## Scope

### Primary fix — `parse-json-object` → `parse-json-value` at provider result sites

Replace every call to `parse-json-object` that extracts a native structured-output payload with `parse-json-value`, preserving the resulting `:payload` for any valid JSON type.

Affected files:
- `components/ai/src/psi/ai/providers/anthropic/structured_output.clj` — `structured-output-result` (one call)
- `components/ai/src/psi/ai/providers/openai/chat_completions.clj` — two call sites that build provider-native structured-output results

The `parse-json-object` helper itself is not removed; it is still used elsewhere for non-structured-output purposes.

### Secondary fix — retry loop for structured-output judge path

In `psi.agent-session.workflow-judge`, when `valid-output-result?` returns false and the attempt count is below `max-judge-retries`, retry the judge turn (same retry-feedback injection used by the plain-text path) instead of immediately returning `:invalid-structured-output`.

## Constraints

- `psi.workflow-runtime.structured-output/structured-output-envelope` and `validation-input` are **not changed** — they already handle `:payload` correctly for any JSON value.
- `parse-json-object` is not removed from `psi.ai.structured-output`.
- The fix is minimal: only the provider result-extraction sites change.
- Existing tests for the `structured-output-envelope` non-object JSON path (e.g. `structured-output-envelope-string-enum-json-test`, `structured-output-envelope-non-object-json-test`) must remain green.

## Acceptance criteria

1. `psi.ai.providers.anthropic.structured-output/structured-output-result` with a bare-string JSON payload (e.g. `"\"DONE\""`) produces a result with `:payload "DONE"` (not `:parse-error? true`).
2. `psi.workflow-runtime.structured-output/structured-output-envelope` receiving that result with a `[:enum :REPEAT :DONE]` schema produces `:status :valid` and `:value :DONE`.
3. The OpenAI chat-completions structured-output result sites produce `:payload` for string, number, boolean, and array JSON values.
4. The judge retry loop fires on structured-output validation failure when `attempt < max-judge-retries`, matching the plain-text retry behavior.
5. All existing structured-output and workflow-judge tests remain green.
6. `bb test` green.
