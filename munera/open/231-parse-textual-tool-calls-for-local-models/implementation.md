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
