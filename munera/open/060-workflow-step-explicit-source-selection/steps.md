# 060 — Steps

- [x] Define first-cut `:session` source-selection syntax for `:input` and `:reference`
- [x] Compile `:workflow-input`, `:workflow-original`, and prior-step accepted-result references to canonical `:input-bindings`
- [x] Reject malformed source specs, unknown step names, forward references by definition order, and unsupported `:session` keys for this task
- [x] Preserve current defaults when syntax is absent or only partially specified
- [x] Add compiler/loader tests for named prior-step non-adjacent source selection and partial-override behavior
- [x] Run focused workflow tests
