# Design follow-up steps

- [ ] Decide and record the defaulting rule for workflow-authored `:top-logprobs` when `:logprobs` is false or absent, because design/plan currently allow "explicitly set by workflow resolution rules" without saying whether that means preserve, drop, or reject the value.
- [ ] Enumerate every workflow propagation owner that must carry logprob controls, including `target_ir_compiler`, `statechart_runtime`, child-session creation seams, and `create-child-session` mutation params, because the plan/steps mention only `resolve-step-session-config` and task 141's path but the current code has multiple additional hops.
- [ ] Decide whether removing the general `/logprobs` command also requires removing or restricting the public `create-child-session` mutation/session-creation surface for interactive callers, since the design forbids a general session-level replacement toggle but current non-workflow child-session creation surfaces already accept execution controls.
