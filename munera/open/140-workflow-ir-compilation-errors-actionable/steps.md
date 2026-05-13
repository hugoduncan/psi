# Steps

- [x] Intent: commit refined task intent (issue #73 branch)
- [x] Design: refine design.md until unambiguous — complete
- [ ] Implement Phase 2 enrichment: add `compile-step-with-context` in `target-ir-compiler.clj`; thread step name + index into compile exceptions
- [ ] Implement formatter: add `format-compilation-errors` in `psi.workflow-runtime.ir` (or `error-format.clj`); cover all semantic error types and Malli structural error rendering
- [ ] Wire formatter into `compile-definition-to-ir!` in `core.clj`
- [ ] Test formatter: focused unit tests for each semantic error type and structural error rendering
- [ ] Test integration: confirm compile-exception step-context appears in `create-run` error messages for unsupported step type, bad source ref, judge-without-routing
- [ ] Regression: all existing `target_ir_compiler_test.clj`, `ir_runtime_adoption_test.clj`, `compiler_test.clj` tests green
- [ ] Review: verify all acceptance criteria; close task
