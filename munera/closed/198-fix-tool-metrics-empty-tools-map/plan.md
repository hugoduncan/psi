# Plan

Already implemented in commit `d16e90286` on branch `fix-tool-metrics`.

## What was done

1. Added `[psi.agent-session.extensions :as ext]` to the ns require in
   `components/agent-session/src/psi/agent_session/tool_runtime_adapter.clj`

2. Extended `emit-tool-lifecycle!` to call `ext/dispatch-in` for the two
   lifecycle event kinds that map to extension bus events:
   - `:tool-start` → `"tool_call"` with `{:type :tool-name :tool-call-id :input}`
   - `:tool-result` → `"tool_result"` with `{:type :tool-name :tool-call-id :content :details :is-error}`

3. Verified with focused tests:
   - `psi.agent-session.tool-execution-test`
   - `psi.agent-session.extensions-post-tool-api-test`
   - `psi.agent-session.tools-test`
   - `psi.agent-session.extensions-test`
   - `psi.tool-runtime.core-test`
   - `clj-kondo` clean

## Remaining work

- Close this task (implementation complete, no further steps needed)
