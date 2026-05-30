# Steps

## Slice 1 — Provider JSON-value extraction and prompted-JSON instructions

- [x] Update `psi.ai.providers.anthropic.structured-output/structured-output-result` to use `parse-json-value` instead of `parse-json-object`.
- [x] In the Anthropic result helper, associate `:payload` whenever parsing succeeds, including when the parsed payload is `nil`.
- [x] In the Anthropic result helper, associate `:parse-error? true` only when `parse-json-value` returns no parse result.
- [x] Update the OpenAI chat-completions streaming structured-output result path to use `parse-json-value` instead of `parse-json-object`.
- [x] In the OpenAI chat-completions streaming result path, associate `:payload` whenever parsing succeeds, including when the parsed payload is `nil`.
- [x] In the OpenAI chat-completions streaming result path, keep `:raw-payload` as the raw JSON response text, not the parsed payload value.
- [x] Update the OpenAI chat-completions non-streaming structured-output result path to use `parse-json-value` instead of `parse-json-object`.
- [x] In the OpenAI chat-completions non-streaming result path, associate `:payload` whenever parsing succeeds, including when the parsed payload is `nil`.
- [x] In the OpenAI chat-completions non-streaming result path, keep `:raw-payload` as the raw JSON response text, not the parsed payload value.
- [x] Verify `psi.ai.providers.openai.codex-structured-output/structured-output-result` tests parse success rather than payload truthiness; adjusted the helper to test `:parsed?` so JSON `null` is preserved as a present `:payload nil`.
- [x] Update `psi.ai.structured-output/json-only-instruction` wording from “JSON object” to “JSON value” while preserving the no-prose/no-fences/no-extra-text constraints.
- [x] Confirm `parse-json-object` remains available and object-only.

## Slice 2 — Provider and envelope regression tests

- [x] Add or update Anthropic JSON Schema output result tests asserting raw payloads for string, number, boolean, array, object, and `null` yield the corresponding `:payload` values and no `:parse-error? true`.
- [x] Add or update Anthropic prompted-JSON fallback result tests asserting raw payloads for string, number, boolean, array, object, and `null` yield the corresponding `:payload` values and no `:parse-error? true`.
- [x] In Anthropic `null` tests for both JSON Schema output and prompted-JSON fallback, assert `(contains? structured-output :payload)` and `(nil? (:payload structured-output))`.
- [x] Update existing Anthropic parse-failure tests so valid non-object JSON values are expected to parse successfully; only invalid JSON remains a parse failure.
- [x] Add or update a structured-output envelope test asserting that the Anthropic string payload validates against `[:enum "REPEAT" "DONE"]` with `:status :valid` and `:value "DONE"`.
- [x] Add or update OpenAI chat-completions provider-native tests for string, number, boolean, array, object, and `null` payload extraction, with `:raw-payload` asserted as the raw JSON response text.
- [x] Ensure OpenAI chat-completions provider-native regression coverage exercises both streaming and non-streaming structured-output result paths, or records why an existing public seam covers both paths equivalently. Existing streaming public-seam tests cover object payload; new helper-level non-streaming matrix covers the shared parse/raw-payload behavior.
- [x] Add or update OpenAI chat-completions prompted-JSON tests for string, number, boolean, array, object, and `null` payload extraction, with `:raw-payload` asserted as the raw JSON response text.
- [x] Ensure OpenAI chat-completions prompted-JSON regression coverage exercises both streaming and non-streaming structured-output result paths, or records why an existing public seam covers both paths equivalently. Streaming and non-streaming share the same parse-json-value/raw-text semantics; non-streaming helper matrix directly covers prompted-JSON payload behavior.
- [x] In OpenAI `null` tests, assert `(contains? structured-output :payload)` and `(nil? (:payload structured-output))`.
- [x] Add or update a Codex structured-output result test asserting JSON `null` yields a present `:payload nil` and no parse error.
- [x] Add or update a prompted-JSON instruction test asserting the text says “JSON value” and does not require a top-level JSON object.
- [x] Run the focused AI/provider/structured-output test namespaces and fix any failures.

## Slice 3 — Structured-output judge retry implementation

- [x] In `psi.agent-session.workflow-judge`, identify the structured-output validation failure branch where `valid-output-result?` is false.
- [x] Add retry behavior for that branch when `attempt < max-judge-retries`.
- [x] Reuse `judge-retry-feedback` with the last invalid assistant output and expected signatures for structured-output retries.
- [x] When structured-output opts are present, call `execute-judge-turn!` on retry with the original opts/schema.
- [x] When structured-output opts are absent, keep the existing plain retry call shape.
- [x] Recur with incremented attempt, trimmed retry assistant text, and retry structured-output metadata.
- [x] Preserve immediate failure for `:unsupported-structured-output` without retrying.
- [x] Preserve final `:invalid-structured-output` failure when retries are exhausted.

## Slice 4 — Workflow-judge retry regression tests

- [x] Add or update a workflow-judge test where the first structured-output judge result fails validation and a later retry succeeds.
- [x] Assert the retry path returns the successful routing result instead of `:invalid-structured-output`.
- [x] Assert each structured-output retry call receives the original structured-output opts/schema.
- [x] Update `execute-judge-invalid-structured-output-fails-locally-test` so it no longer asserts immediate no-retry failure; retarget it to the exhausted structured-output retry case or replace it with equivalent exhausted-retry coverage.
- [x] Add or update a test for exhausted structured-output retries returning `:invalid-structured-output` after retry attempts are used.
- [x] Add or update a test proving `:unsupported-structured-output` still fails immediately without retry.
- [x] Run focused workflow-judge tests and fix any failures.

## Slice 5 — Final verification

- [x] Run targeted `clj-kondo` over the changed source and test paths.
- [x] Run all focused structured-output/provider/workflow-judge tests.
- [x] Run `bb test`.
- [x] Update `implementation.md` with notable decisions, verification commands, and results.
- [x] Re-read `design.md`, `plan.md`, and `steps.md` to ensure the implementation plan still matches the stable design.


## Implementation review follow-ups

- [x] Add direct OpenAI chat-completions streaming structured-output regression coverage for prompted-JSON and non-object/null payloads, or refactor streaming/non-streaming result construction through a shared helper so the existing payload matrix truly covers both paths. Refactored streaming and non-streaming Chat Completions structured-output result construction through a shared helper covered by the existing provider-native/prompted-JSON all-JSON-value payload matrix.
- [x] Update structured-output workflow docs (`doc/workflow-grammar.md` and `doc/workflow-ir.md`) so prompted-JSON and provider-native structured-output descriptions say a single JSON value matching the declared JSON Schema, not only a JSON object, while preserving guidance that map schemas are needed for multiple named fields/path references.
- [x] Reconcile `doc/workflow-ir.md` structured-output invalid-policy/default retry wording with the landed workflow-judge behavior: invalid structured-output judge turns retry by default up to the built-in judge retry limit, while unsupported structured output still fails immediately.

## Test review follow-ups

- [x] Add workflow-judge retry-exhaustion coverage asserting every structured-output retry call receives the original structured-output opts/schema, not just the first successful retry path.
