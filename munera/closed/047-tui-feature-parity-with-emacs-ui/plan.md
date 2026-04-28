# Plan — 047 TUI feature parity with Emacs UI

Treat this task as an umbrella/parity-orchestration task rather than a single-code-slice task.

Execution shape:
1. Define parity in terms of backend-owned workflows rather than adapter-identical rendering.
2. Split the parity thread into concrete workflow slices.
3. Land those slices against canonical app-runtime projections/actions.
4. Close the umbrella when the high-value practical parity slices are complete and the remaining gaps are either closed or intentionally deferred to separate tasks.

Closed parity slices now covering the intended scope:
- `048-tui-canonical-frontend-action-parity`
- `049-tui-discoverable-context-session-navigation-parity`
- `050-tui-live-operator-awareness-parity`
- `054-tui-thinking-and-tool-streaming-parity`

Judgment:
- the umbrella’s highest-value practical parity targets are now landed
- remaining TUI work, if any, should be represented as narrower follow-on tasks rather than keeping this umbrella open indefinitely
