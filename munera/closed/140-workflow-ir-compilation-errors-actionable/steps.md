# Steps

- [x] Intent: commit refined task intent (issue #73 branch)
- [x] Design: refine design.md until unambiguous — complete
- [x] Implement Phase 2 enrichment: add `compile-step-with-context` in `target-ir-compiler.clj`; thread step name + index into compile exceptions
- [x] Change `compile-and-validate-workflow-definition` catch block: store `{:message (ex-message e) :data (ex-data e)}` as `:compile-error` (was bare string); update docstring
- [x] Implement formatter: add `format-compilation-errors` in `psi.workflow-runtime.ir`; covers all semantic error types and Malli structural error rendering; accepts `compile-error` as map `{:message … :data …}` or nil
- [x] Wire formatter into `compile-definition-to-ir!` in `core.clj`
- [x] Update existing tests in `target_ir_compiler_test.clj` that compare `:compile-error` to a bare string — changed to `(get-in result [:compile-error :message])`
- [x] Test formatter: focused unit tests for each semantic error type and structural error rendering (`compilation_error_format_test.clj`)
- [x] Test integration: confirm compile-exception step-context appears in `create-run` error messages for unsupported step type, bad source ref, judge-without-routing
- [x] Regression: all existing `target_ir_compiler_test.clj`, `ir_runtime_adoption_test.clj`, `compiler_test.clj` tests green (1756 tests, 0 failures)
- [x] Review: verify all acceptance criteria; close task — all 4 AC and 8 verification expectations met; 1757 tests, 0 failures (4 pre-existing tmux flakes unrelated); task closed
- [x] Add test for `format-structural-error` `"invalid value"` fallback: hand-craft a Malli error entry with no `:message` and no `:type` key; assert output contains `"invalid value"` and does not end with a bare `":"` — added `testing` block in `format-structural-errors-test`; 16 tests, 63 assertions, 0 failures
- [x] Fix `format-structural-error` to fall back to `(:type error-entry)` (e.g. `(name type)`) when `:message` is nil — real Malli explain-data errors lack `:message`; current output is `"Structural error at [...]: "` (blank description)
- [x] Update `format-structural-errors-test` to include a case using real Malli explain-data (via `explain-workflow-ir`) asserting the path and a non-blank description appear in output
- [x] Add a `create-run` integration test case for the structural error path in `create-run-surfaces-step-contextual-message-test`: use a `:workflow-runtime` source ref (or similar) to trigger a structural error and assert the message contains a path segment and no raw Malli schema dump
- [x] Add focused unit test for `:skills-without-read-tool` in `compilation_error_format_test.clj` — `format-semantic-error` handles this branch but no formatter-level test covers it (acceptance criterion #7)
