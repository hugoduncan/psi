# 060 — Steps

- [x] Define first-cut `:session` source-selection syntax for `:input` and `:reference`
- [x] Compile `:workflow-input`, `:workflow-original`, and prior-step accepted-result references to canonical `:input-bindings`
- [x] Reject malformed source specs, unknown step names, forward references by definition order, and unsupported `:session` keys for this task
- [x] Preserve current defaults when syntax is absent or only partially specified
- [x] Add compiler/loader tests for named prior-step non-adjacent source selection and partial-override behavior
- [x] Run focused workflow tests
- [x] Correct implementation notes to distinguish strict source-selection `:name` references from legacy unambiguous `:goto` compatibility
- [x] Add direct negative test coverage for malformed `:session :reference {}`
- [x] Add a short compiler comment documenting the source-selection vs routing compatibility boundary
