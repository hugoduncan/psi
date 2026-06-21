# Design follow-up steps

## Strict parser simplification (2026-06-21)

- [x] Adopted the strict/simple `:xml` contract to stop implementation-review non-convergence around ambiguous nested/literal tag markup. Parameter text may preserve ordinary text, newlines, and shell metacharacters, but not tag-looking textual-tool-call markup. Any `<tool_call>`, `</tool_call>`, `<function=...>`, `</function>`, `<parameter=...>`, or `</parameter>` substring inside parameter text makes the enclosing candidate malformed/no-op.
- [x] Clarified nested recovery semantics: a well-formed-looking `<tool_call>` inside another `<tool_call>` candidate span is ordinary text and must not be recovered/executed independently, even if the outer candidate is malformed. Later independent well-formed blocks after malformed text remain recoverable.
- [x] Updated acceptance/docs expectations to document the unsupported literal-tag case for custom/local models.
