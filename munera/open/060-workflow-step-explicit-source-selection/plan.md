# 060 — Plan

Implement the narrowest useful slice first.

1. Define the first-cut `:session` source-selection syntax.
2. Extend `workflow_file_compiler.clj` to compile that syntax to canonical `:input-bindings`.
3. Validate source forms and prior-step-only references.
4. Add compiler/loader tests proving branch-safe non-adjacent data flow.
5. Keep all existing workflow files working unchanged when the new syntax is absent.
