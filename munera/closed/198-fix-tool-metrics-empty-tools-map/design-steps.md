# Design Review Steps

- [x] Clarify double-dispatch on tool-plan path: does `execute-tool-plan-step-in!` emit
  `:tool-start`/`:tool-result` lifecycle events through `emit-tool-lifecycle!`? If yes,
  `dispatch-tool-call-in`/`dispatch-tool-result-in` in `tool_plan.clj` must be removed or
  guarded to avoid firing extension handlers twice on the plan path.

- [x] Clarify `wrap-tool-executor` status: is it dead code or live on any execution path?
  If live, confirm whether the new `emit-tool-lifecycle!` bridge creates double-dispatch
  there, and document the decision.

- [x] Clarify `extension-registry` nil guard: state explicitly whether absence of
  `:extension-registry` in `ctx` is a valid production scenario or test-only. If
  test-only, consider asserting its presence rather than silently skipping.

- [x] Fix scope of "all tool executions" claim in Fix section: currently says "all tool
  executions (interactive, batch, background) pass through `emit-tool-lifecycle!`" but
  Clarifications states the plan path is disjoint and does NOT route through it. Narrow
  the Fix section claim to "interactive/batch" (or "non-plan") to eliminate the
  contradiction.

- [x] Clarify blocking semantics on interactive path in Acceptance Criteria: the bridge
  calls `dispatch-in` directly (not `dispatch-tool-call-in`), so `{:block true}` handler
  returns are silently ignored. State explicitly that blocking is intentionally not
  enforced on the interactive/batch path, and update the acceptance criterion
  "no regressions on tool blocking/override" to make clear it covers the plan path only.
