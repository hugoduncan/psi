# 102 — Turn preparation component extraction

## Goal

Extract the prepared-turn projection and response-recording layer into a separate component so the lower-level pure machinery for shaping one turn no longer lives under `agent-session`.

## Why

Task `101-turn-runtime-component-extraction` extracted the impure live execution substrate:

- stream invocation / wait / abort
- turn accumulation
- prepared-request execution
- active-turn abort

After that extraction, the next direct downward `psi.turn` dependencies that still sit under `agent-session` are the pure turn-shaping namespaces:

- `psi.agent-session.prompt-request`
- `psi.agent-session.prompt-recording`

Those namespaces are lower than turn lifecycle orchestration and lower than session lifecycle ownership. They project canonical session prompt state into one prepared turn and classify one execution result into the canonical recording shape. Extracting them creates the next structural step toward a future standalone `psi.turn` component by removing the current direct dependencies:

- `psi.turn` → `psi.agent-session.prompt-request`
- `psi.turn` → `psi.agent-session.prompt-recording`

After this task, `psi.turn` should depend on a lower turn-owned preparation substrate rather than on `agent-session` ownership for these pure transformations.

## Problem

The current pure turn-shaping layer is split across two `agent-session` namespaces:

- `components/agent-session/src/psi/agent_session/prompt_request.clj`
- `components/agent-session/src/psi/agent_session/prompt_recording.clj`

That creates three ownership problems:

- it makes `agent-session` appear to own generic prepared-turn projection and response-classification mechanics
- it keeps `psi.turn` coupled downward to `agent-session` after the runtime extraction
- it blurs the boundary between lower-level turn preparation and higher-level turn/session orchestration

This task is meant to remove that ownership blur without redesigning prepared-request or recording behavior.

## Intent

Create one explicit lower-level component for pure turn preparation mechanics.

That component should own:

- provider-message projection from canonical journal/session state
- request/runtime option projection for one turn
- effective system-prompt assembly and prompt-layer projection
- user-message expansion through skills/templates during request preparation
- construction of the canonical prepared-request map
- assistant-message classification into stop / tool-use / error outcomes
- construction of the canonical record-response pure-result map

That component should not own:

- session dispatch orchestration
- prompt submission/start/prepare/record/continue/finish control flow
- live provider execution
- stream accumulation / waiting / abort
- journal append execution semantics
- extension dispatch
- workflow orchestration
- adapter or UI behavior

## Refactoring findings

Using `clj-surgeon` to inspect the current extraction candidates showed a coherent pure two-namespace cluster.

`clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/prompt_request.clj` showed one internally coherent request-projection surface:

- `journal->provider-messages`
- `session->provider-messages`
- `session->request-options`
- `effective-system-prompt`
- `build-provider-conversation`
- `build-prompt-layers`
- `expand-user-message`
- `build-prepared-request`

with `build-prepared-request` already acting as the canonical assembly root over the other helpers.

`clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/prompt_recording.clj` showed one small, coherent response-classification surface:

- `extract-tool-calls`
- `classify-assistant-message`
- `build-record-response`

with `build-record-response` already acting as the canonical assembly root over the other helpers.

`clj-surgeon -op :deps -file components/agent-session/src/psi/turn.clj` confirmed that `psi.turn` currently delegates directly to these two namespaces only for:

- `build-prepared-request`
- `build-record-response`

This is the extraction boundary for this task.

## Scope

In scope:

- create a new `turn-preparation` component
- move the authoritative prepared-turn projection namespace below `agent-session`
- move the authoritative response-recording/classification namespace below `agent-session`
- update `psi.turn` and any remaining direct consumers to depend on the extracted component
- keep behavior unchanged
- keep the dependency slope one-way: higher-level turn/session orchestration depends on extracted preparation machinery
- move or update focused tests so the extracted ownership is explicit and still proven
- rename moved component-owned tests to `psi.turn-preparation.*-test` namespaces so namespace ownership matches component ownership
- record, at completion, which focused tests moved into `components/turn-preparation/test/psi/turn_preparation/` and which intentionally remained under `components/agent-session/test`, with a brief reason for each class of test

Out of scope:

- extracting `psi.turn` itself into a separate component
- redesigning prompt lifecycle orchestration
- changing prepared-request semantics
- changing execution-result classification semantics
- changing journal append execution semantics
- changing live runtime / stream execution behavior
- changing workflow runtime behavior
- broad cleanup of prompt/session handler code unrelated to this extraction

## Boundary

This task is only about the lower-level pure machinery that shapes one prepared turn and one recorded response.

### In the new component

The extracted component should own the authoritative implementation of:

- current `prompt_request` responsibilities
- current `prompt_recording` responsibilities
- prepared-request construction from canonical session state
- assistant-message classification for recording
- record-response pure-result construction

### Above the new component

The following responsibilities must remain outside the new component:

- `psi.turn` as the public turn lifecycle API
- dispatch handlers for submit/prepare/record/continue/finish
- context callback binding ownership in `components/agent-session/src/psi/agent_session/context.clj`
- live execution/runtime helpers extracted by task `101`
- any helper whose main job is to execute journal append effects rather than shape the pure record-response result

Context-boundary clarification:

- the extraction must preserve the current public callback boundary owned by `context.clj`
- `context.clj` should continue to bind higher-level callbacks to `psi.turn` functions rather than binding directly to low-level `psi.turn-preparation.*` internals
- `psi.turn-preparation.*` is a lower implementation dependency of `psi.turn`, not a new public callback surface for context ownership

## Target shape

Chosen target for this task:

- component path: `components/turn-preparation/`
- namespace family: `psi.turn-preparation.*`

First-cut authoritative namespaces:

- `psi.turn-preparation.request`
  - source file: `components/turn-preparation/src/psi/turn_preparation/request.clj`
- `psi.turn-preparation.recording`
  - source file: `components/turn-preparation/src/psi/turn_preparation/recording.clj`

Expected ownership split:

- `psi.turn-preparation.request`
  - current `prompt_request.clj` implementation
  - authoritative `build-prepared-request`
  - authoritative request shaping / system-prompt assembly / provider-conversation projection helpers

- `psi.turn-preparation.recording`
  - current `prompt_recording.clj` implementation
  - authoritative `classify-assistant-message`
  - authoritative `build-record-response`

API-surface clarifications:

- `build-prepared-request` is the preferred higher-level request-preparation entrypoint exposed by `psi.turn-preparation.request`
- `build-record-response` is the preferred higher-level response-recording entrypoint exposed by `psi.turn-preparation.recording`
- lower helper functions may remain public where current tests or legitimate lower-level consumers already depend on them, but higher-level production callers should prefer the two assembly-root entrypoints unless a concrete lower-level need is already proven in code

Ownership clarifications:

- preferred steady-state production dependency slope should be:
  - `components/agent-session/src/psi/agent_session/context.clj` -> `psi.turn`
  - `psi.turn` -> `psi.turn-preparation.request`
  - `psi.turn` -> `psi.turn-preparation.recording`
  - `psi.turn` -> `psi.turn-runtime.core`
- some existing lower-level production namespaces may still need to require `psi.turn-preparation.request` or `psi.turn-preparation.recording` directly because they depend on helper-level APIs rather than on the public `psi.turn` facade; those cases are allowed only when they are concrete existing consumers, kept minimal, and recorded explicitly in `implementation.md`
- this task should reduce direct lower-level consumers where doing so is trivial and clarifying, but it does not require forcing every helper-level consumer through `psi.turn` if that would introduce wrapper duplication or a worse dependency shape
- completion requires one obvious owner per function surface; duplicate long-term wrappers across `psi.turn-preparation.*`, `psi.turn`, and legacy `agent-session` namespaces are not allowed

Compatibility-shim preference:

- default expectation is no compatibility shim unless the edit sequence concretely requires one to keep the tree compiling during migration
- if a temporary shim is introduced, it must be removed in the same slice before final verification
- the old `psi.agent-session.prompt-request` and `psi.agent-session.prompt-recording` namespaces must not remain authoritative owners at completion

## Consumer migration set

Known direct production consumers that must move or be evaluated in this slice:

- `components/agent-session/src/psi/turn.clj`
- `components/agent-session/src/psi/agent_session/prompt_loop.clj`
- `components/agent-session/src/psi/agent_session/prompt_turn.clj`
- `components/agent-session/src/psi/turn.clj`
- `components/agent-session/src/psi/agent_session/prompt_loop.clj`
- `components/agent-session/src/psi/agent_session/prompt_turn.clj`
- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
- any remaining post-101 runtime-adjacent consumers that still require request/recording helpers directly
- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
- `components/agent-session/src/psi/agent_session/context.clj` callback wiring, while preserving `psi.turn` as the public callback boundary
- any remaining production/test namespaces requiring:
  - `psi.agent-session.prompt-request`
  - `psi.agent-session.prompt-recording`

Consumer-routing clarification:

- `psi.turn` must be updated to delegate to the extracted preparation namespaces
- other direct consumers are to be migrated based on the API they actually use:
  - consumers of the public turn lifecycle surface should depend on `psi.turn`
  - consumers of lower-level request/recording helper APIs may depend directly on `psi.turn-preparation.*`
- the task does not require adding new wrapper functions to `psi.turn` solely to hide legitimate helper-level usage

Known direct test surface affected by current ownership:

- `components/agent-session/test/psi/agent_session/prompt_request_test.clj`
- request/recording-focused portions of `components/agent-session/test/psi/agent_session/prompt_execution_test.clj`
- request-focused portions of `components/agent-session/test/psi/agent_session/prompt_lifecycle_test.clj`
- request-focused portions of `components/agent-session/test/psi/agent_session/child_session_mutation_test.clj`
- `components/agent-session/test/psi/agent_session/workflow_execution_test.clj`
- `components/turn-runtime/test/psi/turn_runtime/core_test.clj` because completed task `101` still leaves at least one extracted-component test depending on the old recording namespace
- any remaining direct consumers found by repo search

Test-movement clarification:

- this task does not require splitting mixed-purpose higher-level test files merely to isolate a few request/recording assertions
- preferred rule is:
  - move whole focused test files when they are primarily about request preparation or response recording ownership
  - leave mixed higher-level lifecycle/integration files in place and update their requires/usages only
  - if needed, add a small new component-owned focused test file instead of carving up an existing mixed-purpose file

Completion requires a final repo search confirming that authoritative usage has moved off the old `agent-session` preparation namespaces.

## Acceptance

- a separate `turn-preparation` component exists
- the authoritative prepared-turn projection and response-recording implementation no longer resides under `components/agent-session/`
- the authoritative namespace names match the new component ownership
- no new component cycle is introduced
- `psi.turn` depends on the extracted turn-preparation component rather than on `psi.agent-session.prompt-request` / `psi.agent-session.prompt-recording`
- all direct consumers compile against the extracted namespaces
- focused turn-preparation verification is green from the new component boundary
- at least one higher-level consuming path still works unchanged in behavior
- no live runtime or journal-execution semantics are pulled down into the extracted component
- any compatibility shim is used only temporarily during migration and removed before completion

## Concrete done criteria

- the task records the chosen component path explicitly as `components/turn-preparation/`
- the task records the authoritative namespace family explicitly as `psi.turn-preparation.*`
- the authoritative request-preparation implementation lives in `psi.turn-preparation.request`
- the authoritative response-recording implementation lives in `psi.turn-preparation.recording`
- `psi.agent-session.prompt-request` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- `psi.agent-session.prompt-recording` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- when no shim is used for those old namespaces, the old source files are removed in this slice rather than left in place as inert duplicates
- all existing direct production consumers are updated in this slice
- focused proofs move with or explicitly target the new component boundary and pass
- no prompt lifecycle or turn semantic changes are required beyond ownership/import adjustments

## Constraints

- prefer the smallest viable extraction slice
- preserve behavior and public prepared-request / record-response shapes
- do not widen the task into `psi.turn` extraction
- do not widen the task into prompt lifecycle redesign
- do not widen the task into runtime/stream extraction already covered by task `101`
- do not widen the task into journal component redesign
- keep the result easy to explain: lower-level pure turn shaping is below `agent-session`; turn lifecycle orchestration stays above it
- maximize orthogonality and keep the namespace dependency tree as close to one-way as possible

## Suggested migration sequence

1. create `components/turn-preparation/` and add repo/component deps
2. move `prompt_request` into `psi.turn-preparation.request`
3. move `prompt_recording` into `psi.turn-preparation.recording`
4. update `psi.turn` to require the new preparation namespaces
5. update direct production consumers that still require the old preparation namespaces
6. update focused tests and move any clearly component-owned tests
7. remove any temporary compatibility shims
8. run focused verification and record final ownership in task notes

## Verification intent

Focused verification should cover both the extracted component and at least one higher-level consumer.

Minimum verification intent:

- extracted component-focused tests for request preparation and response recording behavior
- focused higher-level tests proving `psi.turn` still prepares and records turns through the extracted preparation component
- final repo search confirming no lingering authoritative requires/usages of the old namespaces remain

Representative focused verification surfaces after migration:

- moved component-owned tests under `components/turn-preparation/test/psi/turn_preparation/`
- existing higher-level consuming-path tests such as:
  - `psi.agent-session.prompt-lifecycle-test`
  - request/recording-focused higher-level tests that remain under `agent-session`

Minimum focused proof for completion:

- at least one moved request-preparation-focused test namespace under `components/turn-preparation/test/psi/turn_preparation/`
- at least one moved recording-focused test namespace under `components/turn-preparation/test/psi/turn_preparation/`
- `psi.agent-session.prompt-lifecycle-test`

Representative focused commands after migration must name concrete test namespaces from those surfaces; `implementation.md` must record the exact commands used once the final moved test namespaces are known.

## Risks

- incomplete consumer migration is the main risk
- accidental extraction of lifecycle/runtime responsibilities would blur the boundary immediately
- opportunistic prompt-lifecycle cleanup would create scope creep
- test churn could expand unnecessarily unless proof updates stay tightly aligned with the ownership move
- task `101` is treated as already complete; this task should build cleanly on that fixed runtime boundary rather than re-opening runtime ownership decisions

## Related work

- task `094-prompt-lifecycle-component-extraction` established `psi.turn` as the public turn owner
- task `100-turn-statechart-component-extraction` extracted the lower turn statechart substrate
- task `101-turn-runtime-component-extraction` extracted the impure live turn execution substrate
- this task is the next narrow follow-on: extract the pure turn preparation and response-recording layer that still sits under `agent-session`
- a later follow-on may extract `psi.turn` itself into a dedicated component once it no longer depends on `agent-session`-owned lower turn machinery
