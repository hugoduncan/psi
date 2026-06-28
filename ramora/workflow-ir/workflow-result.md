# Workflow IR — Workflow Result Composition

The workflow result is the yielded value of the step whose chosen transition reaches `:done`.

Rules:

- direct step -> `:done` means that step's yielded value becomes the workflow result
- judge-selected transition -> `:done` still returns the parent step's yielded value
- delegated steps normally return the delegated workflow's yielded value unchanged
