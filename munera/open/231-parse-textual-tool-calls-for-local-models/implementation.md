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
