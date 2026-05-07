# 101 — Turn runtime component extraction

## Goal

Extract the live turn-execution runtime into a separate component so the lower-level machinery for executing one prepared turn no longer lives under `agent-session`.

## Why

Task `100-turn-statechart-component-extraction` moved the per-turn stream statechart below `agent-session`.

The next clean extraction seam is the turn execution engine that currently still lives in `agent-session`:

- stream invocation/wait/cancel helpers
- stream event accumulation into turn state
- live turn context construction
- provider-event consumption
- prepared-request execution to execution-result
- active-turn abort

This layer is lower than session lifecycle orchestration and lower than the public `psi.turn` API. Extracting it creates the next structural step toward a future standalone `psi.turn` component by removing the current direct dependency:

- `psi.turn` → `psi.agent-session.prompt-runtime`

After this task, `psi.turn` should depend on a lower turn-owned execution substrate rather than on `agent-session` ownership.

## Problem

The current live turn execution layer is split across three `agent-session` namespaces:

- `components/agent-session/src/psi/agent_session/prompt_runtime.clj`
- `components/agent-session/src/psi/agent_session/prompt_stream.clj`
- `components/agent-session/src/psi/agent_session/turn_accumulator.clj`

That creates three ownership problems:

- it makes `agent-session` appear to own generic turn-execution mechanics
- it keeps `psi.turn` coupled downward to `agent-session`
- it blurs the boundary between lower-level turn execution and higher-level session/prompt lifecycle orchestration

This task is meant to remove that ownership blur without redesigning turn behavior.

## Intent

Create one explicit lower-level component for turn execution mechanics.

That component should own:

- stream transport helpers
- stream wait/timeout/abort helpers
- turn accumulation logic
- live turn execution against a prepared request
- production of the canonical execution-result map

That component should not own:

- session dispatch orchestration
- prompt submission/start/prepare/record/continue/finish control flow
- journal append semantics
- extension dispatch
- workflow orchestration
- adapter or UI behavior

## Refactoring findings

Using `clj-surgeon` to inspect the current extraction candidates showed a coherent three-namespace cluster:

- `prompt_runtime.clj`
  - central exported surface:
    - `execute-prepared-request!`
    - `abort-active-turn-in!`
    - `create-live-turn-context`
    - `make-provider-event-consumer`
    - `await-assistant-message!`
    - `execute-live-turn!`
  - one clearly session-specific helper remains mixed in:
    - `execute-prepared-request-and-journal!`
- `prompt_stream.clj`
  - owns transport/wait/cancel/abort helpers
- `turn_accumulator.clj`
  - owns streaming assembly and `make-turn-actions`

This is the extraction boundary for this task.

## Scope

In scope:

- create a new `turn-runtime` component
- move the authoritative live turn execution runtime below `agent-session`
- move the authoritative stream helper namespace below `agent-session`
- move the authoritative turn accumulator namespace below `agent-session`
- update `psi.turn` and any remaining direct consumers to depend on the extracted component
- keep behavior unchanged
- keep the dependency slope one-way: higher-level session/turn orchestration depends on extracted runtime machinery
- split mixed ownership where needed so session-specific journaling stays above the new component boundary
- move or update focused tests so the extracted ownership is explicit and still proven
- rename moved component-owned tests to `psi.turn-runtime.*-test` namespaces so namespace ownership matches component ownership
- record, at completion, which focused tests moved into `components/turn-runtime/test/psi/turn_runtime/` and which intentionally remained under `components/agent-session/test`, with a brief reason for each class of test

Out of scope:

- extracting `psi.turn` itself into a separate component
- redesigning prompt lifecycle orchestration
- changing prepared-request construction
- changing execution-result semantics
- changing tool-call / thinking / text streaming semantics
- changing journal persistence semantics
- changing workflow runtime behavior
- broad cleanup of prompt/session handler code unrelated to this extraction

## Boundary

This task is only about the lower-level runtime that executes one prepared turn.

### In the new component

The extracted component should own the authoritative implementation of:

- current `prompt_stream` responsibilities
- current `turn_accumulator` responsibilities
- current `prompt_runtime` responsibilities up to and including `execute-prepared-request!`
- active-turn abort support
- live turn context creation
- provider stream event consumption
- execution-result construction from the assistant message

### Above the new component

The following responsibilities must remain outside the new component:

- `psi.turn` as the public turn lifecycle API
- dispatch handlers for submit/prepare/record/continue/finish
- context callback binding ownership in `components/agent-session/src/psi/agent_session/context.clj`
- session journaling helpers
- any helper whose main job is to append directly to the canonical session journal

Context-boundary clarification:

- the extraction must preserve the current public callback boundary owned by `context.clj`
- `context.clj` should continue to bind higher-level callbacks to `psi.turn` functions rather than binding directly to low-level `psi.turn-runtime.*` internals
- `psi.turn-runtime.*` is a lower implementation dependency of `psi.turn`, not a new public callback surface for context ownership

Important split decisions for this task:

- `execute-prepared-request-and-journal!` is not part of the extracted runtime boundary because journal append semantics are session-domain behavior, not generic turn-runtime behavior
- after extraction, the canonical home of `execute-prepared-request-and-journal!` is `psi.turn` as a thin wrapper over `psi.turn-runtime.core/execute-prepared-request!` plus canonical session journal append
- no separate `agent-session`-owned runtime wrapper namespace should remain for this responsibility at completion

## Target shape

Chosen target for this task:

- component path: `components/turn-runtime/`
- namespace family: `psi.turn-runtime.*`

First-cut authoritative namespaces:

- `psi.turn-runtime.stream`
  - source file: `components/turn-runtime/src/psi/turn_runtime/stream.clj`
- `psi.turn-runtime.accumulator`
  - source file: `components/turn-runtime/src/psi/turn_runtime/accumulator.clj`
- `psi.turn-runtime.core`
  - source file: `components/turn-runtime/src/psi/turn_runtime/core.clj`

Expected ownership split:

- `psi.turn-runtime.stream`
  - `llm-stream-idle-timeout-ms`
  - `llm-stream-wait-poll-ms`
  - `now-ms`
  - `do-stream!`
  - `chain-callbacks`
  - `wait-for-turn-result`
  - `cancelled-stream-handle?`
  - `cancel-stream-handle!`
  - `mark-turn-stream-handle!`
  - `abort-turn!`

- `psi.turn-runtime.accumulator`
  - current `turn_accumulator.clj` implementation
  - authoritative `make-turn-actions`
  - current streaming content/text/tool/thinking assembly helpers

- `psi.turn-runtime.core`
  - `capture-aware-ai-options`
  - `create-live-turn-context`
  - `make-provider-event-consumer`
  - `await-assistant-message!`
  - `execute-live-turn!`
  - `execute-prepared-request!`
  - `abort-active-turn-in!`

API-surface clarifications:

- `execute-prepared-request!` is the preferred higher-level execution entrypoint exposed by `psi.turn-runtime.core`
- `execute-live-turn!` may remain public within the extracted component/test surface, but higher-level production callers should not prefer it over `execute-prepared-request!` unless there is a concrete lower-level need already proven in code
- `psi.turn-runtime.stream/abort-turn!` is the raw turn-context stream abort primitive
- `psi.turn-runtime.core/abort-active-turn-in!` is the session-aware wrapper that resolves the active turn context from session state and then delegates to the lower stream abort primitive

Ownership clarifications:

- steady-state production dependency slope should be:
  - `components/agent-session/src/psi/agent_session/context.clj` -> `psi.turn`
  - `psi.turn` -> `psi.turn-runtime.core`
  - `psi.turn-runtime.core` -> `psi.turn-runtime.stream`
  - `psi.turn-runtime.core` -> `psi.turn-runtime.accumulator`
- no other production namespace should require `psi.turn-runtime.stream` or `psi.turn-runtime.accumulator` directly unless the exception is recorded explicitly in `implementation.md`
- `psi.turn-runtime.stream` is the one authoritative owner of low-level stream helpers; `psi.turn-runtime.core` should call that namespace directly rather than re-exporting duplicate wrappers unless a narrowly justified compatibility seam is required during editing
- `psi.turn` is the one authoritative public home for the journal-appending wrapper `execute-prepared-request-and-journal!`
- completion requires one obvious owner per function surface; duplicate long-term wrappers across `psi.turn-runtime.core`, `psi.turn`, and legacy `agent-session` namespaces are not allowed

Compatibility-shim preference:

- default expectation is no compatibility shim unless the edit sequence concretely requires one to keep the tree compiling during migration
- if a temporary shim is introduced, it must be removed in the same slice before final verification
- the old `psi.agent-session.prompt-runtime`, `psi.agent-session.prompt-stream`, and `psi.agent-session.turn-accumulator` namespaces must not remain authoritative owners at completion

## Consumer migration set

Known direct production consumers that must move in this slice:

- `components/agent-session/src/psi/turn.clj`
- `components/agent-session/src/psi/agent_session/context.clj` callback wiring, while preserving `psi.turn` as the public callback boundary
- any remaining production/test namespaces requiring:
  - `psi.agent-session.prompt-runtime`
  - `psi.agent-session.prompt-stream`
  - `psi.agent-session.turn-accumulator`

Known direct test surface affected by current ownership:

- `components/agent-session/test/psi/agent_session/turn_accumulator_test.clj`
- `components/agent-session/test/psi/agent_session/prompt_execution_test.clj`
- `components/agent-session/test/psi/agent_session/prompt_lifecycle_test.clj`
- `components/agent-session/test/psi/agent_session/runtime_test.clj`
- `components/agent-session/test/psi/agent_session/scheduler_lifecycle_test.clj`
- `components/agent-session/test/psi/agent_session/session_lifecycle_test.clj`
- `components/agent-session/test/psi/agent_session/child_session_mutation_test.clj`

Completion requires a final repo search confirming that authoritative usage has moved off the old `agent-session` turn-runtime namespaces.

## Acceptance

- a separate `turn-runtime` component exists
- the authoritative live turn execution runtime no longer resides under `components/agent-session/`
- the authoritative namespace names match the new component ownership
- no new component cycle is introduced
- `psi.turn` depends on the extracted turn-runtime component rather than on `psi.agent-session.prompt-runtime`
- all direct consumers compile against the extracted namespaces
- focused turn-runtime verification is green from the new component boundary
- at least one higher-level consuming path still works unchanged in behavior
- no session-owned journaling semantics are pulled down into the extracted component
- any compatibility shim is used only temporarily during migration and removed before completion

## Concrete done criteria

- the task records the chosen component path explicitly as `components/turn-runtime/`
- the task records the authoritative namespace family explicitly as `psi.turn-runtime.*`
- the authoritative stream helper implementation lives in `psi.turn-runtime.stream`
- the authoritative accumulator implementation lives in `psi.turn-runtime.accumulator`
- the authoritative execution implementation lives in `psi.turn-runtime.core`
- `psi.agent-session.prompt-runtime` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- `psi.agent-session.prompt-stream` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- `psi.agent-session.turn-accumulator` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- when no shim is used for those old namespaces, the old source files are removed in this slice rather than left in place as inert duplicates
- `execute-prepared-request-and-journal!` does not become part of the extracted component; session journaling remains above the boundary and the canonical post-extraction home of that wrapper is `psi.turn`
- all existing direct production consumers are updated in this slice
- focused proofs move with or explicitly target the new component boundary and pass
- no prompt lifecycle or turn semantic changes are required beyond ownership/import adjustments and the one explicit mixed-ownership split for journal append

## Constraints

- prefer the smallest viable extraction slice
- preserve behavior and public execution-result shape
- do not widen the task into `psi.turn` extraction
- do not widen the task into prompt lifecycle redesign
- do not widen the task into journal component redesign
- keep the result easy to explain: lower-level turn execution machinery is below `agent-session`; turn lifecycle orchestration stays above it
- maximize orthogonality and keep the namespace dependency tree as close to one-way as possible

## Suggested migration sequence

1. create `components/turn-runtime/` and add repo/component deps
2. move `prompt_stream` into `psi.turn-runtime.stream`
3. move `turn_accumulator` into `psi.turn-runtime.accumulator`
4. split `prompt_runtime` so session-owned journaling stays above the boundary
5. move the extracted execution runtime into `psi.turn-runtime.core`
6. update `psi.turn` to require the new runtime namespaces and to become the canonical home of `execute-prepared-request-and-journal!`
7. update context callback wiring and any remaining direct consumers
8. update focused tests and move any clearly component-owned tests
9. remove any temporary compatibility shims
10. run focused verification and record final ownership in task notes

## Verification intent

Focused verification should cover both the extracted component and at least one higher-level consumer.

Minimum verification intent:

- extracted component-focused tests for stream/runtime/accumulator behavior
- focused higher-level tests proving `psi.turn` still executes prepared requests through the extracted runtime
- final repo search confirming no lingering authoritative requires/usages of the old namespaces remain

Representative focused verification surfaces after migration:

- moved component-owned stream/runtime/accumulator tests under `components/turn-runtime/test/psi/turn_runtime/`
- existing higher-level consuming-path tests such as:
  - `psi.agent-session.prompt-execution-test`
  - `psi.agent-session.prompt-lifecycle-test`

Minimum focused proof for completion:

- at least one moved stream-focused or stream-runtime-focused test namespace under `components/turn-runtime/test/psi/turn_runtime/`
- at least one moved accumulator/runtime execution test namespace under `components/turn-runtime/test/psi/turn_runtime/`
- `psi.agent-session.prompt-execution-test`
- `psi.agent-session.prompt-lifecycle-test`

Representative focused commands after migration must name concrete test namespaces from those surfaces; `implementation.md` must record the exact commands used once the final moved test namespaces are known.

## Risks

- incomplete consumer migration is the main risk
- leaving `execute-prepared-request-and-journal!` mixed into the extracted component would blur the boundary immediately
- opportunistic prompt-lifecycle cleanup would create scope creep
- test churn could expand unnecessarily unless proof updates stay tightly aligned with the ownership move

## Related work

- task `094-prompt-lifecycle-component-extraction` established `psi.turn` as the public turn owner
- task `095-abstract-state-kernel-extraction-from-agent-session` established lower generic dispatch/runtime substrate below `agent-session`
- task `097-session-state-component-extraction-from-agent-session` extracted lower session-state substrate
- task `100-turn-statechart-component-extraction` extracted the turn statechart into its own lower component
- this task is the next narrow follow-on: extract the live turn execution engine that still sits under `agent-session`
- a later follow-on may extract `psi.turn` itself into a dedicated component once it no longer depends on `agent-session`-owned turn execution machinery
