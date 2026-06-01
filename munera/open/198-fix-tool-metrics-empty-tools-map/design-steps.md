# Design Review Steps

- [ ] Clarify double-dispatch on tool-plan path: does `execute-tool-plan-step-in!` emit
  `:tool-start`/`:tool-result` lifecycle events through `emit-tool-lifecycle!`? If yes,
  `dispatch-tool-call-in`/`dispatch-tool-result-in` in `tool_plan.clj` must be removed or
  guarded to avoid firing extension handlers twice on the plan path.

- [ ] Clarify `wrap-tool-executor` status: is it dead code or live on any execution path?
  If live, confirm whether the new `emit-tool-lifecycle!` bridge creates double-dispatch
  there, and document the decision.

- [ ] Clarify `extension-registry` nil guard: state explicitly whether absence of
  `:extension-registry` in `ctx` is a valid production scenario or test-only. If
  test-only, consider asserting its presence rather than silently skipping.
