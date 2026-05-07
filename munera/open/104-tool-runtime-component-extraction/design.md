# 104 — Tool runtime component extraction

## Goal

Extract a lower-level tool execution runtime into a separate component that sits structurally below `agent-session`, so the generic machinery for executing, batching, shaping, and recording tool calls no longer lives under `agent-session` or leaks across the `turn-runtime` boundary.

## Why

Tasks `101-turn-runtime-component-extraction` and `103-turn-runtime-ownership-boundary-repair` are treated as complete.

Those tasks clarified the turn-runtime boundary, but both left a coherent residue of tool-oriented machinery split across namespaces that no longer fit cleanly as `agent-session` ownership and should not be pushed back down into `turn-runtime` either.

The strongest current signals are:

- `components/agent-session/src/psi/agent_session/tool_execution.clj`
- `components/agent-session/src/psi/agent_session/tool_batch.clj`
- `components/turn-runtime/src/psi/turn_runtime/tool_args.clj`

Together these own lower-level concerns around:

- tool-argument parsing
- tool-call start/execute/record orchestration
- tool lifecycle event shaping
- tool result content normalization
- tool batch ordering / concurrency / per-file serialization

This machinery is lower than session lifecycle orchestration and adjacent to, but distinct from, turn-runtime ownership. Extracting it creates the next structural step toward cleaner separation among:

- turn preparation
- turn runtime
- tool runtime
- session orchestration

## Problem

The current tool-runtime layer is split across `agent-session` and `turn-runtime` namespaces:

- `components/agent-session/src/psi/agent_session/tool_execution.clj`
- `components/agent-session/src/psi/agent_session/tool_batch.clj`
- `components/turn-runtime/src/psi/turn_runtime/tool_args.clj`

That creates four ownership problems:

- it makes `agent-session` appear to own generic tool execution mechanics
- it leaves `turn-runtime` owning tool-argument parsing that is not intrinsically turn-specific
- it blurs the boundary between lower-level tool execution and higher-level prompt/session orchestration
- it leaves follow-on tool refactoring work scattered across namespaces completed tasks already clarified structurally

This task is meant to remove that ownership blur without redesigning tool semantics.

## Intent

Create one explicit lower-level component for tool runtime mechanics.

That component should own:

- tool-argument parsing helpers
- tool content normalization helpers
- tool lifecycle event shaping helpers
- single-tool execute/record runtime helpers
- tool batch execution ordering / concurrency / per-file locking helpers
- the compatibility start→execute→record runtime-effect helper, if still needed after migration

That component should not own:

- session dispatch orchestration
- prompt submission/start/prepare/record/continue/finish control flow
- turn accumulation semantics
- assistant-message / transcript ordering semantics
- extension registration APIs
- post-tool registry ownership
- tool definition catalogs
- adapter or UI behavior

Architectural requirement:

- this component must sit below `agent-session`
- therefore its authoritative runtime namespaces must not depend on `psi.agent-session.dispatch`, `psi.agent-session.post-tool`, `psi.agent-session.tool-output`, `psi.agent-session.state-accessors`, or other `psi.agent-session.*` implementation namespaces at completion
- if current `tool_execution.clj` mixes lower-level tool-runtime mechanics with session-owned service dependencies, this task must split that mixed ownership rather than move the entire mixed namespace wholesale

## Refactoring findings

Using `clj-surgeon` to inspect the current extraction candidates showed a coherent tool-runtime cluster.

`clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_execution.clj` showed one central execution namespace with these public roots:

- `tool-content->text`
- `normalize-tool-content`
- `tool-lifecycle-event`
- `start-tool-call!`
- `execute-tool-call!`
- `record-tool-call-result!`
- `execute-tool-call-prepared!`
- `record-tool-call-prepared-result!`
- `run-tool-call-through-runtime-effect!`

`clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_batch.clj` showed one coherent batch-execution namespace with these roots:

- `run-tool-call!`
- `run-tool-calls!`

and internal support for:

- executor access
- file-key extraction
- per-file locking
- batch task construction

`clj-surgeon -op :deps -file components/turn-runtime/src/psi/turn_runtime/tool_args.clj` showed a compact, self-contained parsing seam:

- `parse-args-strict`
- `parse-args`

Repo search also showed real current consumer gravity around these surfaces:

- `psi.agent-session.prompt-turn`
- `psi.agent-session.conversation`
- `psi.agent-session.tool-execution-test`
- `psi.agent-session.tool-output-integration-test`
- `psi.turn-runtime.tool-args` currently required from non-turn namespaces

This is the extraction boundary for this task.

## Scope

In scope:

- create a new `tool-runtime` component
- move the authoritative tool-argument parsing namespace out of `turn-runtime`
- move the authoritative tool execution/runtime namespace below `agent-session`
- move the authoritative tool batch/runtime namespace below `agent-session`
- update direct consumers to depend on the extracted component
- keep behavior unchanged
- keep the dependency slope one-way: higher-level session/turn orchestration depends on extracted tool runtime machinery
- move or update focused tests so the extracted ownership is explicit and still proven
- rename moved component-owned tests to `psi.tool-runtime.*-test` namespaces so namespace ownership matches component ownership
- record, at completion, which focused tests moved into `components/tool-runtime/test/psi/tool_runtime/` and which intentionally remained under `components/agent-session/test`, with a brief reason for each class of test

Out of scope:

- redesigning tool semantics
- redesigning prompt lifecycle orchestration
- changing post-tool processor semantics
- changing tool-output truncation policy ownership unless directly required by the extraction boundary
- changing transcript/tool block ordering semantics
- changing extension registration APIs
- changing tool definitions/catalog modeling
- broad cleanup of session handlers unrelated to this extraction

## Boundary

This task is only about the lower-level runtime that executes one or more tool calls.

### In the new component

The extracted component should own the authoritative implementation of:

- current `turn_runtime/tool_args` responsibilities
- the lower-level subset of current `tool_execution` responsibilities
- the lower-level subset of current `tool_batch` responsibilities
- tool-call argument parsing and parse-validity reporting
- tool result content normalization
- tool lifecycle event shape construction
- single-tool execution shaping helpers that do not require `agent-session` ownership
- batch execution ordering, parallelism, and per-file serialization helpers that do not require `agent-session` ownership

Important split decision for this task:

- if current `tool_execution.clj` or `tool_batch.clj` mixes lower-level tool-runtime mechanics with `agent-session`-owned dispatch/state/post-tool/tool-output concerns, this task must separate those concerns
- the extracted component becomes the authoritative owner only of the lower-level tool runtime mechanics
- any session-owned orchestration, state mutation, telemetry recording, or post-tool service integration that cannot be expressed as a lower-level dependency must remain above the boundary in `agent-session`

### Above the new component

The following responsibilities must remain outside the new component:

- `psi.turn` as the public turn lifecycle API
- prompt-turn orchestration that decides when tool batches run
- dispatch handler registration in `dispatch_handlers/session_mutations.clj`
- post-tool processor registry ownership in `post_tool.clj`
- tool-output policy/storage helpers in `tool_output.clj` unless a later task decides otherwise
- context callback and service wiring ownership in `context.clj`
- adapter and UI rendering behavior

Boundary clarifications:

- the extraction should preserve the current dispatch-owned execution boundary rather than introducing a new callback surface
- `agent-session` may continue to own state mutation handlers and service wiring while depending on `psi.tool-runtime.*` for the lower-level tool runtime mechanics
- `tool_output.clj` and `post_tool.clj` remain outside the first-cut extracted component; this task is about execution/runtime mechanics, not every tool-related namespace
- because the new component must sit below `agent-session`, lower-level tool-runtime code must receive any needed higher-level services via data/callback/protocol inputs or remain above the boundary; it must not require `psi.agent-session.*` implementation namespaces directly at completion
- explicit decision for this slice: `psi.tool-runtime.*` must not depend on `psi.turn-runtime.*`
- lower-level tool-runtime code must deliver progress and lifecycle updates through a generic event/callback sink or equivalent generic returned event data, rather than calling turn-runtime accumulation helpers directly
- `turn-runtime` and/or higher-level session orchestration may adapt those generic tool-runtime events into turn-specific accumulation, progress queues, or transcript updates above the boundary

## Target shape

Chosen target for this task:

- component path: `components/tool-runtime/`
- namespace family: `psi.tool-runtime.*`

First-cut authoritative namespaces:

- `psi.tool-runtime.args`
  - source file: `components/tool-runtime/src/psi/tool_runtime/args.clj`
- `psi.tool-runtime.core`
  - source file: `components/tool-runtime/src/psi/tool_runtime/core.clj`
- `psi.tool-runtime.batch`
  - source file: `components/tool-runtime/src/psi/tool_runtime/batch.clj`

Expected ownership split:

- `psi.tool-runtime.args`
  - current `turn_runtime/tool_args.clj` implementation
  - authoritative `parse-args-strict`
  - authoritative `parse-args`

- `psi.tool-runtime.core`
  - lower-level extracted subset of current `tool_execution.clj`
  - authoritative `tool-content->text`
  - authoritative `normalize-tool-content`
  - authoritative `tool-lifecycle-event`
  - authoritative single-tool execution shaping helpers that are independent of `agent-session`

- `psi.tool-runtime.batch`
  - lower-level extracted subset of current `tool_batch.clj`
  - authoritative batch execution helpers
  - authoritative per-file serialization helpers

API-surface clarifications:

- `execute-tool-call-prepared!` and `record-tool-call-prepared-result!` remain valid lower-level phases only if they no longer depend on `agent-session` implementation namespaces and deliver intermediate progress through a generic event/callback sink rather than turn-runtime-specific helpers
- `run-tool-call-through-runtime-effect!` may remain as a compatibility/public composition helper within the extracted component only if it preserves the below-`agent-session`, non-`turn-runtime` boundary; otherwise that composition should remain above the boundary
- `psi.tool-runtime.args` is the one authoritative home for generic tool-argument parsing; `turn-runtime` must not remain the owner of that surface at completion

Ownership clarifications:

- preferred steady-state production dependency slope should be:
  - `psi.turn` -> `psi.turn-runtime.*`
  - `psi.turn-runtime.*` -> `psi.tool-runtime.*`
  - `psi.agent-session.prompt-turn` and/or `psi.agent-session.dispatch_handlers.session-mutations` may depend on `psi.tool-runtime.*` only where higher-level session orchestration still owns the surrounding concerns
  - `psi.agent-session.conversation` -> `psi.tool-runtime.args`
- `psi.tool-runtime.*` must be structurally below `agent-session`; completion therefore requires that the extracted authoritative namespaces do not require `psi.agent-session.*` implementation namespaces directly
- `psi.tool-runtime.*` must not depend on `psi.turn-runtime.*`; tool-runtime emits generic tool events/data, and turn-runtime consumes/adapts them
- some existing higher-level agent-session production namespaces may depend directly on `psi.tool-runtime.*` because they use helper-level APIs rather than a single public facade; those cases are expected and should be kept minimal and recorded explicitly in `implementation.md`
- completion requires one obvious owner per function surface; duplicate long-term wrappers across `psi.tool-runtime.*`, `psi.turn-runtime.*`, and legacy `agent-session` namespaces are not allowed

Compatibility-shim preference:

- default expectation is no compatibility shim unless the edit sequence concretely requires one to keep the tree compiling during migration
- if a temporary shim is introduced, it must be removed in the same slice before final verification
- the old `psi.agent-session.tool-execution`, `psi.agent-session.tool-batch`, and `psi.turn-runtime.tool-args` namespaces must not remain authoritative owners at completion

## Consumer migration set

Authoritative source namespaces being moved or split in this slice:

- `components/turn-runtime/src/psi/turn_runtime/tool_args.clj`
- `components/agent-session/src/psi/agent_session/tool_execution.clj`
- `components/agent-session/src/psi/agent_session/tool_batch.clj`

Known direct production consumers that must move or be evaluated in this slice:

- `components/agent-session/src/psi/agent_session/prompt_turn.clj`
- `components/agent-session/src/psi/agent_session/conversation.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`
- any remaining production/test namespaces requiring:
  - `psi.agent-session.tool-execution`
  - `psi.agent-session.tool-batch`
  - `psi.turn-runtime.tool-args`

Known direct test surface affected by current ownership:

- `components/agent-session/test/psi/agent_session/tool_execution_test.clj`
- `components/agent-session/test/psi/agent_session/tool_output_integration_test.clj`
- any tests requiring `psi.turn-runtime.tool-args`
- any remaining direct consumers found by repo search

Test-movement clarification:

- `tool_execution_test.clj` may remain under `agent-session` if it primarily proves session-owned integration semantics such as dispatch, telemetry recording, post-tool processing, or tool-output policy interaction
- move only the clearly lower-level parser/batch/execution-shaping tests into the new component boundary
- if needed, add a small new component-owned focused test rather than forcing an integration-heavy file to move wholesale

Completion requires a final repo search confirming that authoritative usage has moved off the old namespaces.

## Acceptance

- a separate `tool-runtime` component exists
- the authoritative tool-runtime implementation no longer resides under `components/agent-session/` or `components/turn-runtime/`
- the authoritative namespace names match the new component ownership
- no new component cycle is introduced
- direct consumers compile against the extracted namespaces
- focused tool-runtime verification is green from the new component boundary
- at least one higher-level consuming path still works unchanged in behavior
- no prompt lifecycle or transcript semantics are pulled down into the extracted component
- any compatibility shim is used only temporarily during migration and removed before completion

## Concrete done criteria

- the task records the chosen component path explicitly as `components/tool-runtime/`
- the task records the authoritative namespace family explicitly as `psi.tool-runtime.*`
- the authoritative argument parser implementation lives in `psi.tool-runtime.args`
- the authoritative single-tool execution implementation lives in `psi.tool-runtime.core`
- the authoritative tool batch implementation lives in `psi.tool-runtime.batch`
- the extracted authoritative `psi.tool-runtime.*` namespaces do not require `psi.agent-session.*` or `psi.turn-runtime.*` implementation namespaces directly at completion
- `psi.agent-session.tool-execution` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- `psi.agent-session.tool-batch` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- `psi.turn-runtime.tool-args` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- when no shim is used for those old namespaces, the old source files are removed in this slice rather than left in place as inert duplicates
- all existing direct production consumers are updated in this slice
- focused proofs move with or explicitly target the new component boundary and pass
- no tool semantic changes are required beyond ownership/import adjustments

## Constraints

- prefer the smallest viable extraction slice
- preserve behavior and public tool-execution shapes
- do not widen the task into prompt lifecycle redesign
- do not widen the task into transcript/tool-block redesign
- do not widen the task into post-tool registry extraction
- do not widen the task into tool-output policy/storage extraction
- keep the result easy to explain: lower-level tool execution machinery is below `agent-session` and separate from `turn-runtime`; higher-level turn/session orchestration stays above it
- maximize orthogonality and keep the namespace dependency tree as close to one-way as possible

## Suggested migration sequence

1. create `components/tool-runtime/` and add repo/component deps
2. move `turn_runtime/tool_args.clj` into `psi.tool-runtime.args`
3. split `tool_execution.clj`, extracting the lower-level subset into `psi.tool-runtime.core`
4. split `tool_batch.clj`, extracting the lower-level subset into `psi.tool-runtime.batch`
5. update direct production consumers to require the extracted namespaces
6. update focused tests and move any clearly component-owned tests
7. remove any temporary compatibility shims
8. run focused verification and record final ownership in task notes

## Verification intent

Focused verification should cover both the extracted component and at least one higher-level consumer.

Minimum verification intent:

- extracted component-focused tests for arg parsing, single-tool execution, and batch execution behavior
- focused higher-level tests proving prompt-turn or equivalent consuming paths still run tool calls through the extracted tool runtime
- final repo search confirming no lingering authoritative requires/usages of the old namespaces remain

Representative focused verification surfaces after migration:

- moved component-owned tests under `components/tool-runtime/test/psi/tool_runtime/`
- existing higher-level consuming-path tests such as:
  - `psi.agent-session.tool-output-integration-test`
  - any focused prompt-turn / tool execution path tests that remain under `agent-session`

Minimum focused proof for completion:

- at least one moved args-focused or single-tool-runtime-focused test namespace under `components/tool-runtime/test/psi/tool_runtime/`
- at least one moved batch-focused test namespace under `components/tool-runtime/test/psi/tool_runtime/`
- at least one higher-level consuming-path test outside the component boundary

Representative focused commands after migration must name concrete test namespaces from those surfaces; `implementation.md` must record the exact commands used once the final moved test namespaces are known.

## Risks

- incomplete consumer migration is the main risk
- accidental extraction of prompt/turn semantics would blur the boundary immediately
- opportunistic cleanup of tool-output or post-tool registry ownership would create scope creep
- test churn could expand unnecessarily unless proof updates stay tightly aligned with the ownership move
- accidentally preserving `turn-runtime` ownership of generic tool-arg parsing would leave the boundary still conceptually wrong

## Related work

- task `100-turn-statechart-component-extraction` extracted lower turn statechart machinery
- task `101-turn-runtime-component-extraction` extracted the impure live turn execution substrate
- task `102-turn-preparation-component-extraction` targets the pure preparation/recording layer above runtime
- task `103-turn-runtime-ownership-boundary-repair` completed follow-on ownership cleanup in the turn-runtime area
- this task is the next narrow follow-on: extract the lower-level tool runtime seam that remained visible after the turn-runtime work settled
- a later follow-on may revisit tool-output or post-tool registry ownership, but that is explicitly not part of this slice
