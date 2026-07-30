# Model Selection

The grammar uses a single `:model` field for session steps.

That field may contain either:

- a concrete model id
- a query-shaped model selection specification

The model-selection grammar is defined separately in `doc/model-selection-grammar.md`.

In the workflow grammar, this appears as the nonterminal `model-selection-spec`, which is intentionally defined externally rather than re-specified inside the workflow grammar.

This keeps model choice in one semantic slot while allowing both direct and query-driven selection without redefining the broader model-selection language inside the workflow grammar.
