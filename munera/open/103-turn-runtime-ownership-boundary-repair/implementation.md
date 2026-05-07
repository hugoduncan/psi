2026-05-07
- Implemented the ownership repair by moving lower turn-runtime state readers/writers into `components/turn-runtime/src/psi/turn_runtime/state.clj`.
- New lower-owned state surface now owns:
  - live turn context read/write
  - tool-call-attempt telemetry append
  - provider request capture append
  - provider reply capture append
- Chose direct root-state updates through `psi.session-state.state/apply-root-state-update-in!` rather than dispatch-routed `:session/...` mutation names.
  - This removes the upward dependency on `psi.agent-session.state-accessors`.
  - No callback seam was introduced.
- Moved generic tool-argument parsing into `components/turn-runtime/src/psi/turn_runtime/tool_args.clj`.
  - `psi.turn-runtime.accumulator` now uses `psi.turn-runtime.tool-args/parse-args-strict`.
  - higher callers in `agent-session` (`conversation`, `prompt_chain`, `tool_batch`, `tool_execution`) now use `psi.turn-runtime.tool-args/parse-args`.
- Removed `psi/agent-session` from `components/turn-runtime/deps.edn`, breaking the direct component cycle.
- `psi.turn-runtime.core` no longer requires `psi.agent-session.state-accessors`.
- `psi.turn-runtime.accumulator` no longer requires `psi.agent-session.state-accessors` or `psi.agent-session.conversation`.
- Journal append ownership was left above `turn-runtime`; no journaling logic moved.
- Tool-output accounting remains outside `turn-runtime`.
  - `record-tool-output-stat` is still owned above the boundary in `agent-session`.
  - This is an explicit partial deferral toward a future tool-domain home rather than turn-runtime ownership.

Verification commands run:
- `clj-surgeon :op :ls :file components/turn-runtime/src/psi/turn_runtime/accumulator.clj`
- `clj-surgeon :op :ls :file components/turn-runtime/src/psi/turn_runtime/core.clj`
- `clj-surgeon :op :ls :file components/turn-runtime/src/psi/turn_runtime/state.clj`
- `clj-surgeon :op :ls :file components/turn-runtime/src/psi/turn_runtime/tool_args.clj`
- `clj-kondo --lint components/turn-runtime/src components/agent-session/src components/turn-runtime/test components/agent-session/test`
- `clojure -M:test --focus psi.turn-runtime.core-test`
- `clojure -M:test --focus psi.turn-runtime.accumulator-test`
- `clojure -M:test --focus psi.agent-session.prompt-lifecycle-test`
- `rg -n "psi\.agent-session\." components/turn-runtime/src -g'*.clj'`

Verification results:
- `clj-kondo` reported `errors: 0, warnings: 0`.
- `psi.turn-runtime.core-test` green: `13 tests, 38 assertions, 0 failures`.
- `psi.turn-runtime.accumulator-test` green: `10 tests, 29 assertions, 0 failures`.
- `psi.agent-session.prompt-lifecycle-test` green: `17 tests, 82 assertions, 0 failures`.
- repo search over `components/turn-runtime/src` returned no `psi.agent-session.*` requires.
- `components/turn-runtime/deps.edn` no longer depends on `psi/agent-session`, so the direct `agent-session <-> turn-runtime` component cycle is removed.
