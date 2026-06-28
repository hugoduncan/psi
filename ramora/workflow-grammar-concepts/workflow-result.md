# Workflow Result Composition

A workflow's result is the yielded value of the step that transitions execution to `:done`.

If a step reaches `:done` directly, that step's yielded value becomes the workflow result.

If a step uses a judge and the judge outcome selects a transition whose `:goto` is `:done`, the workflow result is still the parent step's yielded value, not the judge's routing value.

An invoke step yields a deterministic result-oriented value.

A session step yields a text-oriented value derived from the final LLM reply.

A delegate step yields the called workflow's yielded value unchanged.

This makes delegation compositional: the delegated workflow's resulting value becomes the delegating step's resulting value.
