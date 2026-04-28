# 061 — Implementation notes

This task is Phase 2 extracted from umbrella task 059.

Key constraints:
- keep projection vocabulary small and declarative
- do not add transcript/message projection here
- do not introduce a transformation DSL

Implemented:
- extended `workflow_file_authoring_resolution.clj` so `:session :input` and `:session :reference` accept `:projection :text`, `:projection :full`, and `:projection {:path [...]}` layered on top of task-060 `{:from ...}` source selection
- preserved task-060 defaults/backward compatibility by keeping omitted `:projection` equivalent to the canonical text view
- compile-time projection validation now rejects unsupported operators, malformed `{:path ...}` values, and unexpected projection keys with clear compiler/load errors
- added focused compiler tests for projection compilation, malformed projection validation, and named prior-step non-adjacent structured extraction
- added focused loader tests proving projected workflow-file authoring loads/compiles and malformed projections surface as load errors
- focused test run green: `clojure -M:test --focus psi.agent-session.workflow-file-compiler-test --focus psi.agent-session.workflow-file-loader-test` → `17 tests, 128 assertions, 0 failures`
