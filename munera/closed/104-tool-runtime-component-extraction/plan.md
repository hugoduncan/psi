Approach:
- treat this as a structural extraction, not a semantic redesign
- create `components/tool-runtime/` with three authoritative namespaces: `psi.tool-runtime.args`, `psi.tool-runtime.core`, and `psi.tool-runtime.batch`
- use `clj-surgeon` first for structural inspection (`-op :deps`, `-op :ls`) and use structural moves where helpful, but keep the slice small and comprehensible rather than maximizing tool usage for its own sake
- move the generic arg parser first, then extract the lower-level single-tool runtime subset, then the lower-level batch subset, then update consumers in one slice so the new namespaces become authoritative immediately
- because the target component must sit below `agent-session`, split mixed ownership aggressively rather than moving whole mixed namespaces wholesale
- prefer no compatibility shim; introduce a temporary shim only if the edit sequence concretely requires it to keep the tree compiling during migration, and remove it before completion

Authoritative target namespaces:
- `components/tool-runtime/src/psi/tool_runtime/args.clj` -> `psi.tool-runtime.args`
- `components/tool-runtime/src/psi/tool_runtime/core.clj` -> `psi.tool-runtime.core`
- `components/tool-runtime/src/psi/tool_runtime/batch.clj` -> `psi.tool-runtime.batch`

Structural findings from `clj-surgeon`:
- `clj-surgeon -op :deps -file components/turn-runtime/src/psi/turn_runtime/tool_args.clj`
  - shows a compact, self-contained generic parsing seam with `parse-args-strict` and `parse-args`
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_execution.clj`
  - shows one central single-tool runtime cluster, but the implementation still needs a boundary split because the target component must sit below `agent-session`
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_batch.clj`
  - shows one central batch-runtime cluster, but only the lower-level subset should move when session-owned dispatch/state orchestration is mixed in
- repo search shows the current consumer gravity crosses `agent-session` and `turn-runtime`, which supports a new `tool-runtime` component rather than continued turn-runtime-local cleanup

Implementation sequence:
1. create `components/tool-runtime/` directories and destination namespaces/files
2. update project configuration so the new component participates in normal source and test paths
   - root `deps.edn`
   - root `tests.edn`
   - `components/agent-session/deps.edn`
   - `components/turn-runtime/deps.edn` only if needed during the move sequence
3. move `turn_runtime/tool_args.clj` implementation to `psi.tool-runtime.args`
4. split `tool_execution.clj` and extract the lower-level subset into `psi.tool-runtime.core`
5. split `tool_batch.clj` and extract the lower-level subset into `psi.tool-runtime.batch`
6. update direct production consumers according to the API they actually use
   - helper-level parser callers -> `psi.tool-runtime.args`
   - helper-level execution callers -> `psi.tool-runtime.core`
   - batch callers -> `psi.tool-runtime.batch`
7. update direct test consumers and move clearly component-owned tests when that improves ownership clarity without widening scope
8. if any temporary compatibility shim was introduced, remove it before verification; otherwise remove the old source files in this slice rather than leaving inert duplicates
9. run focused verification for the new component plus at least one higher-level consuming path
10. record final ownership and migration notes in `implementation.md`

Consumer migration expectations:
- current production consumers should depend on the extracted namespace that matches the helper-level API they actually use
- preferred steady-state production dependency slope should be:
  - `psi.turn-runtime.*` -> `psi.tool-runtime.*`
  - current known helper-level consumer: `psi.agent-session.conversation` -> `psi.tool-runtime.args`
  - `psi.agent-session.prompt-turn` is the preferred primary adapter from generic tool-runtime events into turn-specific progress/accumulation semantics
  - `psi.agent-session.dispatch_handlers.session-mutations` may depend on `psi.tool-runtime.*` only for lower-level tool runtime helpers where session-owned mutation orchestration still legitimately surrounds the call
- the extracted authoritative `psi.tool-runtime.*` namespaces must sit below `agent-session`, so they must not require `psi.agent-session.*` implementation namespaces directly at completion
- the extracted authoritative `psi.tool-runtime.*` namespaces must not require `psi.turn-runtime.*` implementation namespaces directly at completion
- first-cut API decision: tool-runtime delivers intermediate progress/lifecycle updates through an `:on-event` callback receiving generic tool event maps, while the final tool result remains a separate return value
- some higher-level production namespaces may still depend directly on `psi.tool-runtime.*` because this task is extracting helper/runtime ownership rather than introducing a new single public facade; each such case should be minimized and recorded in `implementation.md`
- completion requires a final repo search confirming no remaining authoritative uses of:
  - `psi.agent-session.tool-execution`
  - `psi.agent-session.tool-batch`
  - `psi.turn-runtime.tool-args`

Configuration changes expected:
- `deps.edn`
  - add local component dep for `psi/tool-runtime`
  - add `components/tool-runtime/src` and `components/tool-runtime/test` where explicit path lists require them
- `tests.edn`
  - add `components/tool-runtime/src`
  - add `components/tool-runtime/test`
- `components/agent-session/deps.edn`
  - add local component dep on `../tool-runtime`
- `components/turn-runtime/deps.edn`
  - remove dependency on the old local parser namespace if present; add `../tool-runtime` only if a temporary migration phase requires it
- `tests-component-isolated.edn`
  - update only if this slice adds a dedicated isolated component test run

Testing strategy:
- preserve existing proof where possible
- move only the tests that clearly belong to the extracted component boundary
- rename moved component-owned tests to `psi.tool-runtime.*-test` namespaces so namespace ownership matches component ownership
- avoid turning this task into a broad tool-output or post-tool test architecture rewrite
- keep higher-level integration/lifecycle tests where they are unless ownership clearly changes
- record in `implementation.md` which tests moved into `components/tool-runtime/test/psi/tool_runtime/` versus which stayed under `components/agent-session/test`, and why

Likely proof split:
- component-owned tests for arg parsing, lower-level execution shaping, generic tool event delivery, and batch behavior move under `components/tool-runtime/test`
- higher-level integration/lifecycle tests remain under `components/agent-session/test`
- preferred movement rule:
  - move whole focused test files whose primary subject is parser behavior, lower-level execution shaping, generic tool event delivery, or batch ordering/locking behavior
  - keep mixed higher-level integration files in place and update their requires/usages only
  - keep `tool_execution_test.clj` under `agent-session` when it is proving session-owned dispatch, telemetry, post-tool, tool-output integration, or turn-specific adaptation concerns
  - add a small new component-owned focused test file if clearer than splitting a mixed-purpose higher-level file
  - keep tests whose primary subject is prompt lifecycle, transcript semantics, tool-output telemetry integration, post-tool registry behavior, or service wiring under their current higher-level component

Verification intent:
- focused tests from the new component location proving parser, single-tool runtime, and batch behavior
- focused higher-level agent-session tests proving consuming paths still run tool calls through the extracted component unchanged in behavior
- no behavior changes beyond namespace/component ownership

Representative focused verification surfaces after migration:
- moved component-owned tests under `components/tool-runtime/test/psi/tool_runtime/`
- higher-level consuming-path tests including:
  - `psi.agent-session.tool-output-integration-test`
  - any focused prompt-turn/tool execution path tests retained under `agent-session`
- exact focused commands depend on the final moved test namespaces and must be recorded in `implementation.md` during execution

Refactoring guardrails:
- minimize the namespace dependency tree; aim for a tree rather than a broader graph
- maximize orthogonality
- do not leave wrapper-local duplicate behavior in the old namespaces
- do not pull prompt/turn semantics down into the extracted tool-runtime component
- avoid opportunistic cleanup in tool-output policy/storage, post-tool registry ownership, or prompt lifecycle orchestration

Main risks:
- incomplete consumer migration
- ownership blur if prompt/turn semantics get pulled into the extracted component
- overusing compatibility shims and accidentally leaving them in place
- unnecessary test churn that obscures the structural change
- leaving generic tool-arg parsing under `turn-runtime` would indicate the extraction boundary is still not complete
