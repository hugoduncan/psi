# Contributions

Contributions are the building blocks of inline session conversation assembly.

There are two contribution forms:

- `:type :source`
- `:type :template`

## Source Contributions

A source contribution injects sourced material into the child-session conversation.

It reuses the workflow source/projection model and preserves author order.

## Template Contributions

A template contribution is authored text plus explicit variable bindings.

It is the grammar's textual rendering mechanism. It makes templating explicit rather than implicit.

A delegate step's `:prompt-string` may also use this same template shape before rendering to a final string.

## Templating

Templating is modeled as:

- `:text`
- `:vars`

A template contribution does not invent a separate data source model; it binds vars through the same source-spec mechanism used elsewhere.

Template variable names are strings in the grammar. The placeholder `{{issues}}` therefore binds to the key `"issues"` in `:vars`.

This keeps textual rendering aligned with workflow data flow.
