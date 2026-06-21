# 231 — Implementation Notes

Created from request: local model runners sometimes leave tool-call markup in assistant text instead of converting it to canonical tool-call events. The task should add a model-capability-gated compatibility parser that recovers the observed XML-like textual tool-call format and routes parsed calls through the existing tool execution machinery.

User decisions captured after initial design:

- Capability shape: `{:capabilities {:textual-tool-calls #{:xml}}}`.
- Multiple well-formed textual tool calls in one assistant response are supported.
- Malformed markup is a no-op, not an error surface.
- Tool-call id generation can follow the existing canonical path.
- Parameter values remain strings for this slice.
- Remove exact parsed `<tool_call>...</tool_call>` blocks; preserve surrounding prose.
- Custom/local model docs are the right documentation target.

No implementation work has been done yet.

2026-06-19 architecture review:
- architectural review added 2 new design steps: keep capability/parser placement acyclic (`turn-runtime` must not depend on `agent-session`), and apply normalization once across both streaming and non-streaming turn paths.

2026-06-19 ambiguity review:
- ambiguity review added 3 new design steps: mixed valid/malformed response semantics, exact tag grammar/name rules, and canonical argument representation.

2026-06-19 inconsistency review:
- no inconsistency review feedback.

2026-06-19 design follow-up execution:
- Completed all unchecked non-scope design follow-ups in `design-steps.md`; no `SCOPE_QUESTION:` items were present.
- Useful implementation orientation: `turn-runtime` already receives the resolved `ai-model` in `components/turn-runtime/src/psi/turn_runtime/core.clj` and `accumulator.clj`, while `runtime-active-model` currently lives in `components/agent-session/src/psi/agent_session/model_capabilities.clj`; avoid adding a `turn-runtime` dependency on `agent-session`.
- Existing canonical tool-call validation expects `:arguments` to be a JSON object string (`psi.tool-runtime.args/parse-args-strict`, used by `psi.turn-runtime.accumulator/invalid-tool-call`).

2026-06-20 architecture review (shared design-review first turn):
- no architectural review feedback.

2026-06-20 ambiguity review (shared design-review second turn):
- ambiguity review added 2 new design steps: duplicate parameter-name semantics, and exact per-call function/parameter cardinality/nesting.

2026-06-20 inconsistency review (shared design-review third turn):
- no inconsistency review feedback.

2026-06-20 design follow-up execution:
- Completed the two unchecked non-scope ambiguity follow-ups in `design-steps.md`; no `SCOPE_QUESTION:` items were present.
- Implementation should treat duplicate parameter names, multiple/missing function blocks, parameter blocks outside the function, and function blocks with zero parameters as malformed block-level no-ops.

2026-06-20 plan ambiguity review (shared plan-review first turn):
- ambiguity review added 1 new design step: canonical ordering/content-index/id allocation when residual text, provider tool calls, and recovered textual calls coexist.

2026-06-20 plan inconsistency review (shared plan-review second turn):
- no inconsistency review feedback.

2026-06-20 design follow-up execution:
- Completed the remaining ordering/id ambiguity follow-up in `design-steps.md`; no `SCOPE_QUESTION:` items were present.
- Existing streaming assembly currently creates canonical ids in `components/turn-runtime/src/psi/turn_runtime/accumulator.clj` via `canonical-tool-call-id` / `complete-tool-calls`, then drops content-index metadata in `build-final-content`.
- Existing `build-final-content` orders blocks as thinking, one text block, errors, then tool calls; implementing provider/recovered/text interleaving may require changing that final assembly shape, with tests guarding provider id/index preservation and recovered id non-collision.

2026-06-20 plan ambiguity review (shared plan-review first turn rerun):
- no new ambiguity review feedback.

2026-06-20 plan inconsistency review (shared plan-review second turn rerun):
- no new inconsistency review feedback.

2026-06-20 implementation slice 1/2:
- Added lower-level `psi.ai.textual-tool-calls` so `turn-runtime` can later require capability/parser helpers without depending on `agent-session`.
- `schemas/ModelCapabilities` now accepts optional `:textual-tool-calls #{:xml}`; omitted/empty capability remains disabled via `supports-format?`.
- Parser currently returns only successfully parsed calls with exact source spans/source text/name/string arguments; malformed blocks are omitted so the next normalizer slice can remove only successful spans and leave malformed markup as text.
- Parser is intentionally regex/narrow, not XML: lowercase tags only, `[A-Za-z0-9_-]+` identifiers, duplicate params/cardinality/nesting failures are block-level no-ops.
- Verified `bb clojure:test:scry --ns psi.ai.textual-tool-calls-test` and focused `clj-kondo` on changed Clojure files.

2026-06-20 implementation slice 3/4:
- Added `psi.ai.textual-tool-calls/normalize-assistant-message` as the shared pure boundary for canonical recovery. It is model-capability gated, converts parsed calls to canonical `:tool-call` blocks with JSON object string arguments, removes only exact parsed spans, preserves malformed markup, and skips existing `turn-id/toolcall/N` ids when generating recovered ids.
- Wired streaming final assembly and non-streaming responses through the same normalizer using the already-resolved `ai-model`; `turn-runtime` now stores `:ai-model` in turn data for accumulator finalization and still avoids any `agent-session` dependency.
- Focused normalizer tests cover disabled models, JSON args, multiple calls, malformed interleaving, provider/recovered/text ordering, and id non-collision. Existing accumulator tests remain green; focused clj-kondo reports only pre-existing unresolved `ai/execute-response[-in]` warnings in `turn-runtime/core.clj`.

2026-06-20 implementation slice 5/6:
- Added focused mock-free session coverage in `psi.agent-session.textual-tool-call-execution-test` using a nullable stub AI provider and real prompt-chain/tool dispatch: capability-enabled textual `bash` executes through the existing tool path and records an ordinary `toolResult`; recovered unknown tools surface the same error-shaped `toolResult`; default/frontier opt-out preserves markup as text and dispatches no tools.
- Documented `{:capabilities {:textual-tool-calls #{:xml}}}` in `doc/custom-providers.md`, including the strict XML-like shape, malformed-markup no-op behavior, and warning not to enable it for provider-native/frontier models.
- Added the user-visible changelog entry under `[Unreleased]`.

2026-06-20 implementation review:
- added 3 steps to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 3 review steps: streaming content-index ordering, provider content-index-aware recovered id allocation, and literal `<function=...>` parameter text parsing.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure files.
2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: streaming text now accumulates per provider content index, preserving `text(0) → provider tool(1) → text/recovered call(2)` order while retaining the legacy aggregate `:text-buffer` for compatibility.
- verified focused turn-runtime/textual-tool-call Scry suites and focused clj-kondo on changed Clojure files.
2026-06-20 implementation review:
- added 2 steps to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 2 review steps: overlapping malformed textual-tool-call prefixes no longer block later valid recovery, and recovered ids now advance by final content position after preceding provider/text blocks.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: streaming final assembly now preserves content-index metadata through textual-tool-call normalization, then strips it only from the final assistant content; regressions now expect recovered ids after provider/text positions.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: literal `<tool_call>` / `</tool_call>` text inside parameter values is preserved while malformed overlapping prefixes still defer to later valid recovery.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.
2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: fully replaced textual-tool-call source text blocks no longer reserve their removed source index, so recovered ids can reuse that source content index while residual-text cases still allocate after the retained text block.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: recovered textual tool calls now skip existing canonical `turn-id/toolcall/N` ids when reusing a fully replaced text block source index.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 unaddressed follow-up step.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: recovered textual tool-call id allocation now advances across preceding unindexed provider/content blocks in final content order.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 2 unaddressed follow-up steps.

2026-06-20 implementation review follow-up execution:
- addressed 2 review steps: canonical unindexed provider ids now occupy their parsed generated index without adding a hidden position; unrelated diff areas were confirmed as pre-existing commits before task 231 implementation rather than new task-scope changes.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 2 steps to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 2 review steps: literal `<parameter=...>` / `</parameter>` text inside parameter values is preserved, and fully replaced text blocks containing multiple adjacent recovered calls can reuse the source index for the first recovered id.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 new step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: multiple `<function=...>` blocks in one textual `<tool_call>` are rejected even when separated by whitespace or parameter-looking text, while literal function tags inside parameter values remain preserved.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: literal adjacent `<tool_call><function=...>` text inside parameter values is preserved while nested valid tool-call markup inside a malformed prefix remains recoverable.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: complete nested well-formed `<tool_call>...</tool_call>` text inside parameter values is preserved as literal parameter text while incomplete nested starts still allow later valid recovery.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside malformed duplicate-parameter outer blocks remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: outer textual tool calls with multiple function blocks are rejected even when the first parameter contains literal nested well-formed tool-call text.
- verified focused textual-tool-call/turn-runtime/session Scry suites; focused clj-kondo reports only the pre-existing `psi.ai.core/execute-response-in` unresolved-var warning in `response_mode_test.clj`.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside malformed no-parameter outer blocks remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 additional step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside malformed outer blocks with parameter markup outside the function remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside malformed outer blocks with invalid function-name grammar remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 invalid-parameter-name follow-up step.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside malformed outer blocks with invalid parameter-name grammar remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.


2026-06-20 implementation review follow-up execution:
- addressed 1 review step: incomplete quoted nested textual tool-call starts inside parameter values now remain literal parameter text instead of being recovered as inner calls.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: adjacent quote characters outside an exact well-formed textual tool-call span are now preserved as surrounding text instead of suppressing recovery.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: unquoted incomplete nested textual tool-call starts inside parameter values now remain literal parameter text instead of being recovered as inner calls.
- verified focused textual-tool-call/turn-runtime/session Scry suites; focused clj-kondo reports only the pre-existing `psi.ai.core/execute-response-in` unresolved-var warning in `response_mode_test.clj`.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside later function blocks of malformed multi-function outer blocks remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: nested well-formed textual tool calls inside unterminated outer textual tool-call parameters remain ordinary text and are not recovered/executed.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: prefixed unterminated outer textual tool-call parameters now suppress nested valid textual calls instead of executing literal nested markup.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-20 implementation review:
- added 1 step to be addressed.

2026-06-20 implementation review follow-up execution:
- addressed 1 review step: textual tool-call candidate expansion is now bounded per open marker and per candidate span, with a malformed many-marker stress regression to prevent quadratic-plus normalization cost.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: valid textual tool-call blocks now tolerate more than eight literal `</tool_call>` substrings inside parameter values before the real outer close while retaining a bounded per-open candidate cap.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: later well-formed textual tool-call blocks after malformed outer blocks with nested tool-call-like text now recover instead of being suppressed by the malformed-enclosure guard.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: removed the fixed per-open textual-tool-call close-candidate cap so valid parameter text with more than 64 literal `</tool_call>` substrings is preserved; span-length bounding still limits malformed-input work.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: documented the 65,536-character maximum supported textual tool-call candidate block length in custom/local model docs; oversized blocks remain ordinary assistant text to preserve bounded malformed-input behavior.

2026-06-21 design simplification decision:
- User chose the strict/simple parser contract to stop review non-convergence around ambiguous nested/literal tag markup.
- Updated `design.md`: parameter text must not contain tag-looking textual-tool-call markup (`<tool_call>`, `</tool_call>`, `<function=...>`, `</function>`, `<parameter=...>`, `</parameter>`). Such text makes the enclosing candidate malformed/no-op.
- Nested recovery is now explicitly unsupported: a well-formed-looking call inside another candidate span remains ordinary text and must not execute, even if the outer candidate is malformed. Later independent valid blocks remain recoverable.
- Next implementation pass should simplify/reconcile parser/tests/docs to this stricter contract, deleting permissive literal-tag preservation behavior where necessary.

2026-06-21 strict parser simplification implementation:
- Reconciled `psi.ai.textual-tool-calls` with the strict/simple `:xml` contract: parameter values containing textual-tool-call tag-looking markup are now malformed/no-op instead of preserved as literal arguments.
- Simplified the parser substantially by removing permissive nested/literal-tag recovery heuristics; candidates now use the first bounded `</tool_call>` close and nested well-formed-looking calls inside earlier candidate spans are not recovered independently.
- Updated parser/normalizer tests and custom-provider docs to match the unsupported literal-tag/nested-recovery contract.
- Verified focused textual-tool-call, turn-runtime streaming/non-streaming, and agent-session execution Scry suites. Focused clj-kondo has only pre-existing unresolved `ai/execute-response[-in]` warnings outside this slice.

2026-06-21 implementation review:
- added 1 parser-bound step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: textual tool-call candidate scanning now precomputes open/close marker positions and advances through closes monotonically, with a many-unclosed-open-marker stress regression for far lone closes.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 tag-grammar step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: strict parameter text rejection now treats any `<function=...>` / `<parameter=...>` tag-looking substring ending at `>` as malformed, including invalid embedded names such as `x.y` or whitespace variants.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 oversized-nesting step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: oversized textual tool-call candidates now suppress nested recovery within their skipped span, so inner well-formed-looking calls remain ordinary text when enclosed by oversized markup.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.
2026-06-21 implementation review:
- added 1 content-order step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: leading recovered textual tool calls now reuse the source content index before trailing residual text reserves a later hidden position.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- added 1 content-order step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: later recovered textual tool-call ids now allocate after intervening residual text when a provider-indexed text block begins with a recovered call.
- verified focused textual-tool-call/turn-runtime/session Scry suites and focused clj-kondo on changed Clojure/test files.
2026-06-21 implementation review:
- added 1 normalizer performance step to be addressed.

2026-06-21 implementation review follow-up execution:
- addressed 1 review step: normalization now parses each source text block once and threads cached parsed-call metadata through id allocation/content rewriting.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 implementation review:
- no new actionable issues.

2026-06-21 test review:
- added 2 test-review steps to be addressed.

2026-06-21 test review follow-up execution:
- addressed 2 review steps: added parser coverage for uppercase identifier preservation and disallowed quote/namespace/slash/entity-encoded identifier forms.
- verified focused textual-tool-call Scry suite and focused clj-kondo on changed Clojure/test files.

2026-06-21 test review:
- added 3 test-review steps to be addressed.

2026-06-21 test review follow-up execution:
- addressed 3 review steps: disabled-capability normalization now proves the XML parser is not invoked; recovered known-tool unavailable execution is covered through the ordinary tool-result error path; non-streaming turn-runtime recovery now uses an injected provider seam instead of redefining AI execution.
- verified focused textual-tool-call/turn-runtime/session Scry suites; focused clj-kondo reports only the pre-existing `psi.ai.core/execute-response-in` unresolved-var warning in `response_mode_test.clj`.

2026-06-21 test review:
- added 1 test-review step to be addressed.

2026-06-21 test review follow-up execution:
- addressed 1 review step: replaced the unavailable-tool executor stub with a registered extension tool whose real execute path returns the standard error-shaped toolResult.
- verified focused session textual-tool-call Scry suite and focused clj-kondo on the changed test file.

2026-06-21 test review:
- added 2 test-review steps to be addressed.

2026-06-21 test review follow-up execution:
- addressed 2 review steps: streaming textual-tool-call turn-runtime tests now use an injected AI provider context instead of redefining `do-stream!`, and canonical provider-emitted baseline comparisons now prove recovered unknown/known-unavailable tool calls produce the same tool-result error policy shape.
- verified focused turn-runtime/session textual-tool-call Scry suites and focused clj-kondo on changed test files.
