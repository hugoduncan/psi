# 060 — Steps

- [ ] Define first-cut `:session` source-selection syntax for `:input` and `:reference`
- [ ] Compile `:workflow-input`, `:workflow-original`, and prior-step accepted-result references to canonical `:input-bindings`
- [ ] Reject malformed source specs, unknown step names, forward references by definition order, and unsupported `:session` keys for this task
- [ ] Preserve current defaults when syntax is absent or only partially specified
- [ ] Add compiler/loader tests for branch-safe non-adjacent data flow and partial-override behavior
- [ ] Run focused workflow tests
