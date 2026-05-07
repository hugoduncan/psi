Approach:
- treat this as a structural extraction, not a semantic redesign
- create `components/turn-runtime/` with three authoritative namespaces: `psi.turn-runtime.stream`, `psi.turn-runtime.accumulator`, and `psi.turn-runtime.core`
- use `clj-surgeon` first for structural inspection (`:ls`, `:deps`) and use structural moves where helpful, but keep the slice small and comprehensible rather than maximizing tool usage for its own sake
- move lower-level stream and accumulator namespaces first, then split/move the executable core from `prompt_runtime`
- explicitly keep session journaling above the extracted boundary by relocating `execute-prepared-request-and-journal!` to `psi.turn` outside the new component
- update all direct consumers in the same slice so the new namespaces become authoritative immediately
- prefer no compatibility shim; introduce a temporary shim only if the edit sequence concretely requires it to keep the tree compiling during migration, and remove it before completion

Authoritative target namespaces:
- `components/turn-runtime/src/psi/turn_runtime/stream.clj` -> `psi.turn-runtime.stream`
- `components/turn-runtime/src/psi/turn_runtime/accumulator.clj` -> `psi.turn-runtime.accumulator`
- `components/turn-runtime/src/psi/turn_runtime/core.clj` -> `psi.turn-runtime.core`

Structural findings from `clj-surgeon`:
- `prompt_stream.clj` is already a coherent leaf namespace and can move almost as-is
- `turn_accumulator.clj` is already a coherent leaf namespace and can move almost as-is
- `prompt_runtime.clj` is nearly the extracted core already, but includes one mixed-ownership helper: `execute-prepared-request-and-journal!`
- the key split in this task is therefore: move the execution runtime below `agent-session`, but keep session-journal append behavior above the boundary

Implementation sequence:
1. create `components/turn-runtime/` directories and destination namespaces/files
2. update project configuration so the new component participates in normal source and test paths
   - root `deps.edn`
   - root `tests.edn`
   - `components/agent-session/deps.edn`
   - any additional explicit alias path lists only if required
3. move `prompt_stream.clj` implementation to `psi.turn-runtime.stream`
4. move `turn_accumulator.clj` implementation to `psi.turn-runtime.accumulator`
5. split `prompt_runtime.clj`
   - move `abort-active-turn-in!`, `capture-aware-ai-options`, `create-live-turn-context`, `make-provider-event-consumer`, `await-assistant-message!`, `execute-live-turn!`, and `execute-prepared-request!` into `psi.turn-runtime.core`
   - treat `execute-prepared-request!` as the preferred higher-level execution entrypoint in `psi.turn-runtime.core`
   - allow `execute-live-turn!` to remain public within the extracted component/test surface, but do not prefer it for higher-level production callers absent a concrete lower-level need
   - move `execute-prepared-request-and-journal!` above the extracted boundary into `psi.turn` as a thin wrapper over `psi.turn-runtime.core/execute-prepared-request!` plus canonical journal append
   - keep the abort split explicit: `psi.turn-runtime.stream/abort-turn!` is the raw turn-context primitive, while `psi.turn-runtime.core/abort-active-turn-in!` is the session-aware wrapper
   - do not keep duplicate long-term wrappers for low-level stream helpers; `psi.turn-runtime.stream` remains the authoritative owner and `psi.turn-runtime.core` should call it directly
6. update direct production consumers
   - `components/agent-session/src/psi/turn.clj`
   - `components/agent-session/src/psi/agent_session/context.clj`, while preserving `psi.turn` as the public callback boundary and not rebinding context directly to `psi.turn-runtime.*`
   - any remaining production namespaces requiring the old runtime/stream/accumulator namespaces
7. update direct test consumers and move clearly component-owned tests when that improves ownership clarity without widening scope
8. if any temporary compatibility shim was introduced, remove it before verification; otherwise remove the old source files in this slice rather than leaving inert duplicates
9. run focused verification for the new component plus at least one higher-level consuming path
10. record final ownership and migration notes in `implementation.md`

Consumer migration expectations:
- public turn API should call `psi.turn-runtime.core` rather than `psi.agent-session.prompt-runtime`
- steady-state production dependency slope should be:
  - `components/agent-session/src/psi/agent_session/context.clj` -> `psi.turn`
  - `psi.turn` -> `psi.turn-runtime.core`
  - `psi.turn-runtime.core` -> `psi.turn-runtime.stream`
  - `psi.turn-runtime.core` -> `psi.turn-runtime.accumulator`
- no other production namespace should require `psi.turn-runtime.stream` or `psi.turn-runtime.accumulator` directly unless the exception is recorded explicitly in `implementation.md`
- completion requires a final repo search confirming no remaining authoritative uses of:
  - `psi.agent-session.prompt-runtime`
  - `psi.agent-session.prompt-stream`
  - `psi.agent-session.turn-accumulator`

Configuration changes expected:
- `deps.edn`
  - add local component dep for `psi/turn-runtime`
  - add `components/turn-runtime/src` and `components/turn-runtime/test` where explicit path lists require them
- `tests.edn`
  - add `components/turn-runtime/src`
  - add `components/turn-runtime/test`
- `components/agent-session/deps.edn`
  - add local component dep on `../turn-runtime`
- `tests-component-isolated.edn`
  - update only if this slice adds a dedicated isolated component test run

Testing strategy:
- preserve existing proof where possible
- move only the tests that clearly belong to the extracted component boundary
- rename moved component-owned tests to `psi.turn-runtime.*-test` namespaces so namespace ownership matches component ownership
- avoid turning this task into a broad prompt-test architecture rewrite
- keep higher-level lifecycle tests where they are unless ownership clearly changes
- record in `implementation.md` which tests moved into `components/turn-runtime/test/psi/turn_runtime/` versus which stayed under `components/agent-session/test`, and why

Likely proof split:
- component-owned tests for stream/runtime/accumulator behavior move under `components/turn-runtime/test`
- higher-level integration/lifecycle tests remain under `components/agent-session/test`
- preferred movement rule:
  - move tests whose primary subject is stream waiting/cancel/abort behavior
  - move tests whose primary subject is accumulator behavior or `make-turn-actions`
  - move tests whose primary subject is live turn execution or `execute-prepared-request!`
  - keep tests whose primary subject is lifecycle orchestration, dispatch handlers, context wiring, journaling, scheduling, or session mutation behavior

Verification intent:
- focused tests from the new component location proving stream wait/cancel/runtime/accumulation behavior
- focused higher-level agent-session tests proving `psi.turn` and the surrounding lifecycle still execute through the extracted runtime unchanged in behavior
- no behavior changes beyond namespace/component ownership and the explicit journaling split

Representative focused verification surfaces after migration:
- moved component-owned tests under `components/turn-runtime/test/psi/turn_runtime/`
- higher-level consuming-path tests including:
  - `psi.agent-session.prompt-execution-test`
  - `psi.agent-session.prompt-lifecycle-test`
- exact focused commands depend on the final moved test namespaces and must be recorded in `implementation.md` during execution

Refactoring guardrails:
- minimize the namespace dependency tree
- maximize orthogonality
- do not leave wrapper-local duplicate behavior in the old namespaces
- do not pull session-owned journal append semantics down into the extracted runtime
- avoid opportunistic cleanup in prompt lifecycle orchestration, workflow code, or session handlers

Main risks:
- incomplete consumer migration
- ownership blur if `execute-prepared-request-and-journal!` ends up in the extracted component
- overusing compatibility shims and accidentally leaving them in place
- unnecessary test churn that obscures the structural change
