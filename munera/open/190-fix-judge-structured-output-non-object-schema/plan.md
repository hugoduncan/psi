# Plan

## Approach

Implement the task as two narrow root-cause fixes plus focused regression coverage.

1. Preserve all valid JSON values at provider structured-output result seams.
   - Change the Anthropic structured-output result helper to parse with `parse-json-value` and use parse success, not payload truthiness, to decide whether `:payload` is present.
   - Change the two OpenAI chat-completions structured-output result sites to parse with `parse-json-value` for both provider-native and prompted-JSON strategies.
   - Preserve OpenAI chat-completions `:raw-payload` as the raw JSON response text, matching Anthropic and Codex, while storing the parsed JSON value only in `:payload`. This applies to scalar, array, object, boolean, number, and `null` outputs.
   - Preserve JSON `null` as a present `:payload nil`; do not treat it as parse failure.
   - Keep `parse-json-object` as an object-only helper.
   - Verify the existing OpenAI Codex helper preserves `:payload nil`; adjust only if implementation proves it gates success on payload truthiness.

2. Align prompted-JSON fallback instructions with non-object schemas.
   - Update `json-only-instruction` to require exactly one JSON value matching the supplied JSON Schema instead of exactly one JSON object.
   - Preserve the existing prohibition on Markdown fences, prose, and extra top-level text.

3. Add structured-output judge retry behavior.
   - In `psi.agent-session.workflow-judge`, when structured-output validation fails and `attempt < max-judge-retries`, retry using the same retry feedback shape as the plain-text path.
   - If the judge request had structured-output opts/schema, pass those same opts to every retry call to `execute-judge-turn!`; only non-structured-output judge retries use the plain call.
   - Preserve existing unsupported-structured-output failure behavior.

4. Add focused regression tests, then run broader verification.
   - Cover Anthropic JSON Schema output payload extraction for string, number, boolean, array, object, and `null`, including present `:payload nil`; update existing Anthropic parse-failure expectations so only invalid JSON, not valid non-object JSON, is treated as parse failure.
   - Cover Anthropic prompted-JSON fallback payload extraction for string, number, boolean, array, object, and `null`, including present `:payload nil`, because it uses the same `structured-output-result` extraction seam and the design requires equivalent non-object preservation for both provider-native and prompted-JSON results.
   - Cover Anthropic bare string envelope validation against `[:enum "REPEAT" "DONE"]`.
   - Cover OpenAI chat-completions provider-native and prompted-JSON payload preservation for string, number, boolean, array, object, and `null`, including present `:payload nil`.
   - Cover Codex `null` preservation if no existing test already protects it.
   - Cover prompted-JSON instruction wording.
   - Cover structured-output judge retry on validation failure and preservation of structured-output opts/schema across retries.

## Risks

- JSON `null` is easy to regress because ordinary truthiness checks make `nil` indistinguishable from absence; tests must assert key presence with `contains?`.
- OpenAI chat-completions has both streaming and non-streaming structured-output result paths; both must preserve the same payload semantics.
- The judge retry change must not accidentally retry unsupported provider-native structured-output cases, which should remain immediate failures.
- Retrying structured-output judge turns without preserving opts/schema would silently downgrade the retry to plain text and fail the design intent.
- Some provider helper functions may be private; tests may need to drive public streaming/non-streaming seams or use existing test helpers rather than broadening production visibility.

## Slice order

1. Provider JSON-value extraction and prompted-JSON instruction wording.
2. Provider regression tests for Anthropic, OpenAI chat-completions, Codex, and structured-output envelope validation.
3. Structured-output judge retry implementation.
4. Workflow-judge retry regression tests.
5. Final verification with focused tests, lint for changed namespaces, and `bb test`.
