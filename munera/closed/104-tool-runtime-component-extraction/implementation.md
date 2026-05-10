2026-05-07

Task created from follow-on extraction analysis after the turn-runtime thread (`101` and `103`) was treated as complete.

Initial design rationale:
- `101` and `103` are treated as complete and both left tool-related refactoring residue
- that residue appears to be a better fit for a dedicated `tool-runtime` component than for continued turn-runtime-local cleanup
- crucial boundary decision: the target component must sit structurally below `agent-session`
- extracted target chosen: `components/tool-runtime/` with authoritative namespaces `psi.tool-runtime.args`, `psi.tool-runtime.core`, and `psi.tool-runtime.batch`

Refactoring-skill guardrails adopted:
- aim for a clean refactor
- compatibility shims allowed only temporarily and must be removed before completion
- tests should reflect the refactored code
- minimize the namespace dependency tree
- maximize orthogonality

`clj-surgeon` findings used in the design:
- `clj-surgeon -op :deps -file components/turn-runtime/src/psi/turn_runtime/tool_args.clj`
  - showed a compact generic parser seam (`parse-args-strict`, `parse-args`)
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_execution.clj`
  - showed one coherent single-tool runtime namespace with execute/record public roots and helper-level content/lifecycle shaping
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_batch.clj`
  - showed one coherent batch-runtime namespace with executor access, file-key extraction, per-file locking, and ordered execution

Observed consumer surfaces from repo search at task creation time:
- production consumers include `prompt_turn.clj`, `conversation.clj`, and dispatch handlers in `session_mutations.clj`
- current ownership already crosses `agent-session` and `turn-runtime`, which strengthens the case for a dedicated `tool-runtime` component
- current `tool_execution.clj` also depends on `agent-session`-owned services such as dispatch/state/post-tool/tool-output, so the task must split mixed ownership rather than move the entire namespace wholesale if the result is to sit below `agent-session`
- revised seam decision: `psi.tool-runtime.*` must not depend on `psi.turn-runtime.*`; tool-runtime should instead deliver generic tool events upward through a first-cut `:on-event` callback seam, with turn-runtime adapting those generic events into turn accumulation/progress semantics above the boundary
- preferred upper-layer adapter decision: `prompt_turn.clj` is the primary adapter for turn-specific progress/accumulation concerns, while `session_mutations.clj` may still wrap lower-level helpers where session-owned mutation orchestration remains the surrounding concern
- first-cut task explicitly leaves `post_tool.clj` and `tool_output.clj` outside the extraction boundary to avoid widening into all tool-adjacent ownership at once

Implementation completed:
- created `components/tool-runtime/` with:
  - `components/tool-runtime/deps.edn`
  - `components/tool-runtime/src/psi/tool_runtime/args.clj`
  - `components/tool-runtime/src/psi/tool_runtime/core.clj`
  - `components/tool-runtime/src/psi/tool_runtime/batch.clj`
  - `components/tool-runtime/test/psi/tool_runtime/args_test.clj`
  - `components/tool-runtime/test/psi/tool_runtime/core_test.clj`
  - `components/tool-runtime/test/psi/tool_runtime/batch_test.clj`
- updated root configuration so the new component participates in source/test runs:
  - `deps.edn`
  - `tests.edn`
  - `components/agent-session/deps.edn`
  - `components/turn-runtime/deps.edn`
- moved generic tool arg parsing out of `turn-runtime`
  - removed `components/turn-runtime/src/psi/turn_runtime/tool_args.clj`
  - updated consumers to use `psi.tool-runtime.args`
- extracted lower-level generic execution mechanics into `psi.tool-runtime.core`
  - canonical content normalization
  - canonical lifecycle event shape
  - execute/record prepared phases
  - generic `:on-event` callback delivery
- extracted lower-level batch mechanics into `psi.tool-runtime.batch`
  - arg parsing handoff
  - per-file keying
  - per-file locking
  - ordered result collection over bounded parallel execution
- created `components/agent-session/src/psi/agent_session/tool_runtime_adapter.clj`
  - this is the session-owned adapter layer for dispatch, telemetry, post-tool integration, output accounting, and progress emission
  - lower-level runtime code remains below `agent-session`; session mutation orchestration remains above it
- removed the old authoritative wrapper namespaces entirely:
  - `components/agent-session/src/psi/agent_session/tool_execution.clj`
  - `components/agent-session/src/psi/agent_session/tool_batch.clj`
  - no compatibility shim remained at completion

Consumer migration completed:
- updated higher-level production consumers to use the extracted boundary:
  - `components/agent-session/src/psi/agent_session/conversation.clj` -> `psi.tool-runtime.args`
  - `components/agent-session/src/psi/agent_session/prompt_chain.clj` -> `psi.tool-runtime.args`
  - `components/agent-session/src/psi/agent_session/prompt_turn.clj` -> `psi.agent-session.tool-runtime-adapter`
  - `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj` -> `psi.agent-session.tool-runtime-adapter`
  - `components/turn-runtime/src/psi/turn_runtime/accumulator.clj` -> `psi.tool-runtime.args`
- intentional direct higher-level production dependencies on the extracted boundary now are:
  - `psi.agent-session.conversation` -> `psi.tool-runtime.args`
  - `psi.agent-session.prompt_chain` -> `psi.tool-runtime.args`
  - `psi.agent-session.tool-runtime-adapter` -> `psi.tool-runtime.args`, `psi.tool-runtime.core`, `psi.tool-runtime.batch`
  - `psi.turn-runtime.accumulator` -> `psi.tool-runtime.args`
- no `psi.tool-runtime.*` namespace depends on `psi.agent-session.*` or `psi.turn-runtime.*`

Notable adapter decision/fix during completion:
- the first cut briefly double-emitted `:tool-start` because the adapter called its own `start-tool-call!` and then delegated to `psi.tool-runtime.core/execute-tool-call-prepared!`, which already emits `:tool-start`
- final shape keeps canonical start-event emission inside `psi.tool-runtime.core`
- session-owned `:session/tool-agent-start` dispatch remains explicit in the adapter before executing the prepared lower-level phase
- this preserves one canonical lifecycle event while still keeping session-owned runtime-agent side effects visible at the dispatch layer

Test movement and ownership:
- moved into `components/tool-runtime/test/psi/tool_runtime/` because they prove component-owned lower-level behavior:
  - `psi.tool-runtime.args-test`
    - parser behavior only
  - `psi.tool-runtime.core-test`
    - content normalization, lifecycle event shape, `:on-event` delivery, generic execute/record shaping
  - `psi.tool-runtime.batch-test`
    - file-key extraction, ordered recording, bounded parallelism
- intentionally remained under higher-level components:
  - `components/agent-session/test/psi/agent_session/tool_execution_test.clj`
    - proves session-owned dispatch composition, telemetry capture, post-tool enrichment, and adapter/runtime side effects
  - `components/agent-session/test/psi/agent_session/tool_output_integration_test.clj`
    - proves tool-output telemetry/query integration owned above the extracted runtime
  - `components/turn-runtime/test/psi/turn_runtime/core_test.clj`
    - proves higher-level turn-loop/tool-batch consuming behavior

Turn-runtime test update:
- `psi.turn-runtime.core-test` previously stubbed `start-tool-call!`, `execute-tool-call!`, and `record-tool-call-result!`
- after extraction the authoritative batch path composes prepared execute + prepared record through dispatch
- updated the focused turn-runtime tests to stub `execute-tool-call-prepared!` and `record-tool-call-prepared-result!` instead so they match the new authoritative composition seam and no longer hang waiting on obsolete hooks

Final verification run:
- `clojure -M:test --focus psi.tool-runtime.args-test`
  - `2 tests, 6 assertions, 0 failures`
- `clojure -M:test --focus psi.tool-runtime.core-test`
  - `4 tests, 16 assertions, 0 failures`
- `clojure -M:test --focus psi.tool-runtime.batch-test`
  - `3 tests, 8 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.tool-execution-test`
  - `9 tests, 49 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.tool-output-integration-test`
  - `4 tests, 25 assertions, 0 failures`
- `clojure -M:test --focus psi.turn-runtime.core-test`
  - `13 tests, 37 assertions, 0 failures`

Follow-up verification run:
- `clojure -M:test --focus psi.tool-runtime.core-test`
  - `5 tests, 20 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.tool-execution-test`
  - `9 tests, 49 assertions, 0 failures`
- `clojure -M:test --focus psi.turn-runtime.core-test`
  - `13 tests, 37 assertions, 0 failures`

Code-shaper follow-up verification run:
- `clojure -M:test --focus psi.tool-runtime.core-test`
  - `5 tests, 20 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.tool-execution-test`
  - `9 tests, 49 assertions, 0 failures`

Coverage follow-up verification run:
- `clojure -M:test --focus psi.tool-runtime.core-test`
  - `6 tests, 26 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.tool-execution-test`
  - `9 tests, 49 assertions, 0 failures`

Final repo-search result:
- no remaining production/test requires of `psi.turn-runtime.tool-args`
- no remaining production requires of `psi.agent-session.tool-execution`
- no remaining production requires of `psi.agent-session.tool-batch`
- the remaining repo hit for `psi.agent-session.tool-execution` is only the higher-level test namespace name `psi.agent-session.tool-execution-test`, which intentionally remains as an `agent-session` integration test name rather than an authoritative code owner

Follow-up completion note:
- chose the design-promised failure contract rather than narrowing it: execution exceptions now emit canonical `:tool-error` before final `:tool-result` recording
- repo search confirmed no legitimate production use for adapter wrapper surfaces `tool-content->text`, `normalize-tool-content`, `tool-lifecycle-event`, `start-tool-call!`, and `run-tool-call-through-runtime-effect!`, so they were removed rather than justified
- shaped the remaining exception-path duplication in `psi.tool-runtime.core/execute-tool-call-prepared!` into two small local helpers: `emit-tool-error!` for canonical event emission and `exception-tool-result` for the shaped error return envelope; behavior remains unchanged

Open note:
- this task remains intentionally narrower than a general “tool component” extraction; it extracts runtime/execution mechanics only, not tool definitions, tool UI, or every tool-adjacent namespace

Review note:
- follow-up review items are now addressed: `psi.tool-runtime.core/execute-tool-call-prepared!` emits canonical `:tool-error` on execution exceptions with focused proof in `psi.tool-runtime.core-test`, and the apparently-unused adapter wrapper functions (`tool-content->text`, `normalize-tool-content`, `tool-lifecycle-event`, `start-tool-call!`, `run-tool-call-through-runtime-effect!`) were removed from `psi.agent-session.tool-runtime-adapter` after repo search confirmed no legitimate production use
- code-shaper follow-up is now addressed: the exception path in `psi.tool-runtime.core/execute-tool-call-prepared!` was shaped into two small local helpers, `emit-tool-error!` and `exception-tool-result`, which remove duplication while keeping the local failure contract explicit
- coverage/style review follow-up is now addressed: `psi.tool-runtime.core-test` now includes a component-owned end-to-end error lifecycle proof showing execute-phase `:tool-error` emission followed by record-phase final `:tool-result` emission for the same shaped error result