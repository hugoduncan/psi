# Steps

## Slice 1 — Provider JSON-value extraction and prompted-JSON instructions

- [ ] Update `psi.ai.providers.anthropic.structured-output/structured-output-result` to use `parse-json-value` instead of `parse-json-object`.
- [ ] In the Anthropic result helper, associate `:payload` whenever parsing succeeds, including when the parsed payload is `nil`.
- [ ] In the Anthropic result helper, associate `:parse-error? true` only when `parse-json-value` returns no parse result.
- [ ] Update the OpenAI chat-completions streaming structured-output result path to use `parse-json-value` instead of `parse-json-object`.
- [ ] In the OpenAI chat-completions streaming result path, associate `:payload` whenever parsing succeeds, including when the parsed payload is `nil`.
- [ ] In the OpenAI chat-completions streaming result path, keep `:raw-payload` as the raw JSON response text, not the parsed payload value.
- [ ] Update the OpenAI chat-completions non-streaming structured-output result path to use `parse-json-value` instead of `parse-json-object`.
- [ ] In the OpenAI chat-completions non-streaming result path, associate `:payload` whenever parsing succeeds, including when the parsed payload is `nil`.
- [ ] In the OpenAI chat-completions non-streaming result path, keep `:raw-payload` as the raw JSON response text, not the parsed payload value.
- [ ] Verify `psi.ai.providers.openai.codex-structured-output/structured-output-result` tests parse success rather than payload truthiness; adjust only if it fails to preserve a present `:payload nil`.
- [ ] Update `psi.ai.structured-output/json-only-instruction` wording from “JSON object” to “JSON value” while preserving the no-prose/no-fences/no-extra-text constraints.
- [ ] Confirm `parse-json-object` remains available and object-only.

## Slice 2 — Provider and envelope regression tests

- [ ] Add or update Anthropic structured-output result tests asserting raw payloads for string, number, boolean, array, object, and `null` yield the corresponding `:payload` values and no `:parse-error? true`.
- [ ] In Anthropic `null` tests, assert `(contains? structured-output :payload)` and `(nil? (:payload structured-output))`.
- [ ] Update existing Anthropic parse-failure tests so valid non-object JSON values are expected to parse successfully; only invalid JSON remains a parse failure.
- [ ] Add or update a structured-output envelope test asserting that the Anthropic string payload validates against `[:enum "REPEAT" "DONE"]` with `:status :valid` and `:value "DONE"`.
- [ ] Add or update OpenAI chat-completions provider-native tests for string, number, boolean, array, object, and `null` payload extraction, with `:raw-payload` asserted as the raw JSON response text.
- [ ] Ensure OpenAI chat-completions provider-native regression coverage exercises both streaming and non-streaming structured-output result paths, or records why an existing public seam covers both paths equivalently.
- [ ] Add or update OpenAI chat-completions prompted-JSON tests for string, number, boolean, array, object, and `null` payload extraction, with `:raw-payload` asserted as the raw JSON response text.
- [ ] Ensure OpenAI chat-completions prompted-JSON regression coverage exercises both streaming and non-streaming structured-output result paths, or records why an existing public seam covers both paths equivalently.
- [ ] In OpenAI `null` tests, assert `(contains? structured-output :payload)` and `(nil? (:payload structured-output))`.
- [ ] Add or update a Codex structured-output result test asserting JSON `null` yields a present `:payload nil` and no parse error.
- [ ] Add or update a prompted-JSON instruction test asserting the text says “JSON value” and does not require a top-level JSON object.
- [ ] Run the focused AI/provider/structured-output test namespaces and fix any failures.

## Slice 3 — Structured-output judge retry implementation

- [ ] In `psi.agent-session.workflow-judge`, identify the structured-output validation failure branch where `valid-output-result?` is false.
- [ ] Add retry behavior for that branch when `attempt < max-judge-retries`.
- [ ] Reuse `judge-retry-feedback` with the last invalid assistant output and expected signatures for structured-output retries.
- [ ] When structured-output opts are present, call `execute-judge-turn!` on retry with the original opts/schema.
- [ ] When structured-output opts are absent, keep the existing plain retry call shape.
- [ ] Recur with incremented attempt, trimmed retry assistant text, and retry structured-output metadata.
- [ ] Preserve immediate failure for `:unsupported-structured-output` without retrying.
- [ ] Preserve final `:invalid-structured-output` failure when retries are exhausted.

## Slice 4 — Workflow-judge retry regression tests

- [ ] Add or update a workflow-judge test where the first structured-output judge result fails validation and a later retry succeeds.
- [ ] Assert the retry path returns the successful routing result instead of `:invalid-structured-output`.
- [ ] Assert each structured-output retry call receives the original structured-output opts/schema.
- [ ] Add or update a test for exhausted structured-output retries returning `:invalid-structured-output`.
- [ ] Add or update a test proving `:unsupported-structured-output` still fails immediately without retry.
- [ ] Run focused workflow-judge tests and fix any failures.

## Slice 5 — Final verification

- [ ] Run targeted `clj-kondo` over the changed source and test paths.
- [ ] Run all focused structured-output/provider/workflow-judge tests.
- [ ] Run `bb test`.
- [ ] Update `implementation.md` with notable decisions, verification commands, and results.
- [ ] Re-read `design.md`, `plan.md`, and `steps.md` to ensure the implementation plan still matches the stable design.
