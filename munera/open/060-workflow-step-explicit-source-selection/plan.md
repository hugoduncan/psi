# 060 — Plan

Implement the narrowest useful slice first.

1. Define the first-cut `:session` source-selection syntax for `:input` and `:reference`.
2. Define the task-local default source meanings clearly:
   - workflow input -> canonical `[:input]`
   - workflow original -> canonical `[:original]`
   - selected prior-step accepted result -> current accepted-result text path by default
3. Extend `workflow_file_compiler.clj` to compile that syntax to canonical `:input-bindings`.
4. Validate malformed source forms, unknown step names, and forward references by definition order.
5. Add compiler/loader tests proving named prior-step non-adjacent source selection and partial-override behavior.
6. Keep all existing workflow files working unchanged when the new syntax is absent.
