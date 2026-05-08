# 119 — Expand turn-runtime to own more of the prepared-turn boundary

## Goal

Extend the existing `turn-runtime` component so it owns more of the coherent lower-level prepared-turn boundary currently still split across `agent-session`, while keeping dispatch invocation and session orchestration in `agent-session`.

## Why

Recent review of `psi.turn` against the dispatch-ownership conclusion sharpened an important boundary fact:

- `agent-session` is the authoritative owner of session dispatch invocation
- `psi.turn` currently mixes lower prepared-turn mechanics with dispatch-owned session orchestration
- the current `turn-runtime` component is already the strongest lower turn boundary in the system, but it is narrower than the meaningful lower-level component

The current `turn-runtime` component mainly owns live prepared-request execution:

- stream handling
- accumulation
- wait / timeout / abort mechanics
- execution-result shaping from a prepared request

But adjacent lower-level prepared-turn responsibilities still live under `agent-session`:

- request preparation in `psi.agent-session.prompt-request`
- response classification / record shaping in `psi.agent-session.prompt-recording`
- some pure turn helpers and summaries in `psi.turn` / `psi.turn.handlers`

That leaves the lower turn boundary fragmented and makes `psi.turn` look more component-worthy than it currently is. The clearer interpretation is:

- current `turn-runtime` is the seed of the right lower component
- the lower component should grow around it
- `psi.turn` should remain a higher session-owned orchestration facade until dispatch-owning APIs are split away

## Problem

The current turn-related ownership is split awkwardly across three layers:

1. `components/turn-runtime/`
   - owns lower live execution mechanics
2. `components/agent-session/src/psi/agent_session/*.clj`
   - still owns lower prepared-turn shaping in `prompt_request` and `prompt_recording`
3. `components/agent-session/src/psi/turn.clj`
   - exposes a mixed API containing both:
     - lower prepared-turn helpers
     - higher dispatch-owning orchestration entrypoints

This creates several structural problems:

- `turn-runtime` is too narrow to represent the full lower prepared-turn boundary
- `psi.turn` still reaches down into `agent-session` for lower request/recording work
- future extraction decisions risk reviving the already-superseded narrow `102-turn-preparation-component-extraction` framing instead of strengthening the existing `turn-runtime` component
- the system lacks one obvious lower owner for the full prepared-turn pipeline below dispatch orchestration

## Intent

Grow the existing `turn-runtime` component so it becomes the authoritative lower owner of the prepared-turn boundary just below dispatch/session orchestration.

This task should:

- keep `turn-runtime` as the existing component identity
- move more lower prepared-turn responsibilities into `components/turn-runtime/`
- treat request preparation and response-record shaping as part of the broader prepared-turn boundary around the current runtime core
- keep session dispatch invocation, queue mutation, interrupt orchestration, and other `:session/...` entrypoints in `agent-session`
- make `psi.turn` clearly a higher orchestration facade rather than a half-extracted component

This task should not:

- extract `psi.turn` into its own component
- move dispatch invocation below `agent-session`
- redesign prompt lifecycle behavior
- redesign session queue / interrupt policy
- re-open the `turn-runtime` ownership repair from task `103` except where this expansion depends on its landed boundary

## Boundary statement

### `turn-runtime` should own

The expanded `turn-runtime` component should own the lower-level mechanics of one prepared turn:

- canonical prepared-request construction from normalized prepared-turn inputs
- provider-facing conversation assembly and prompt-layer packaging when purely a function of supplied inputs
- live prepared-request execution
- stream consumption, accumulation, wait, timeout, and abort mechanics
- assistant-message classification
- deterministic response-record shaping
- lower prepared-turn inspection helpers such as prepared-request summaries or query-text extraction when they are not session-policy-owned

### `agent-session` should own

`agent-session` should remain the authoritative owner of:

- dispatch invocation
- `:session/...` event initiation
- session queue mutation
- interrupt / abort orchestration semantics at the session level
- journal append through dispatch
- prompt lifecycle dispatch handlers
- session-owned policy and projection required before lower prepared-turn assembly

### `psi.turn` should become clearer as a higher facade

After this expansion, `psi.turn` should primarily be understood as:

- a session-owned turn orchestration API
- a public facade over lower `turn-runtime` mechanics
- the owner of dispatch-driven entrypoints like prompt submit, steer, follow-up, interrupt, abort, and queue consumption

It should not remain the long-term authoritative owner of lower prepared-turn preparation or response-shaping logic.

## Relationship to prior tasks

### Task `101-turn-runtime-component-extraction`

Task `101` extracted the live turn execution engine into `components/turn-runtime/`.

This task builds directly on that result. It does not replace `101`; it broadens the component boundary around the extracted runtime.

### Task `102-turn-preparation-component-extraction`

Task `102` framed request preparation and response recording as a separate sibling component.

Task `105` later marked `102` as superseded because that narrower extraction target was structurally premature without the broader component map.

This task intentionally avoids reviving `102` as a separate component. Instead, it uses the current insight that the meaningful lower-level boundary is an expanded `turn-runtime`, not a separate `turn-preparation` component beside it.

### Task `103-turn-runtime-ownership-boundary-repair`

Task `103` repaired the component so `turn-runtime` became a genuinely lower component rather than a cyclic extraction.

This task assumes that repaired lower boundary and extends it, rather than re-litigating the ownership-repair decision.

### Task `105-agent-session-component-extraction-map`

This task should be treated as a child of the turn area described by `105`.

It sharpens the turn story further:

- `turn-runtime` is the lower prepared-turn component
- `psi.turn` remains higher turn/session orchestration
- dispatch invocation stays with `agent-session`

## Scope

In scope:

- extend `components/turn-runtime/` to own more of the lower prepared-turn boundary
- move lower prepared-turn request assembly into `turn-runtime`
- move lower response classification / record shaping into `turn-runtime`
- move or extract any small pure helpers needed to make that ownership coherent
- update `psi.turn` and any direct consumers to depend downward on the expanded `turn-runtime` component
- keep behavior unchanged
- record which functions remain in `agent-session` because they are dispatch/session-owned

Out of scope:

- extracting `psi.turn` as its own component
- moving prompt/session dispatch handlers into `turn-runtime`
- moving session-owned policy/projection below the boundary if it is not already normalizable as lower prepared-turn input
- broad prompt composition extraction work, which belongs to the prompt-assets/prompt-composition track
- redesigning tool-use continuation semantics
- redesigning workflow runtime behavior

## Expected ownership split

### Likely move into `turn-runtime`

Current lower candidates:

- `psi.agent-session.prompt-request`
  - or the lower subset of it after splitting any remaining session-owned projection/policy
- `psi.agent-session.prompt-recording`
- pure helper functions currently in `psi.turn` that support prepared-turn shaping rather than dispatch orchestration
- pure helper functions currently in `psi.turn.handlers` that summarize prepared requests or execution results without encoding dispatch effect choreography

### Likely remain above the boundary

Current higher candidates:

- `psi.turn/prompt-dispatch!`
- `psi.turn/prompt-in!`
- `psi.turn/prompt-execution-result-in!`
- `psi.turn/steer-in!`
- `psi.turn/follow-up-in!`
- `psi.turn/queue-while-streaming-in!`
- `psi.turn/request-interrupt-in!`
- `psi.turn/abort-in!`
- `psi.turn/consume-queued-input-text-in!`
- dispatch/effect/event choreography in `psi.turn.handlers`
- prompt lifecycle registration in `dispatch-handlers.prompt-lifecycle`

## Target shape

Chosen target for this task:

- authoritative component path remains: `components/turn-runtime/`
- authoritative namespace family remains: `psi.turn-runtime.*`

Potential namespace growth inside the existing component:

- existing:
  - `psi.turn-runtime.core`
  - `psi.turn-runtime.stream`
  - `psi.turn-runtime.accumulator`
- likely new lower namespaces:
  - `psi.turn-runtime.request`
  - `psi.turn-runtime.recording`
  - optional small helper namespaces only if they clarify ownership without over-fragmenting the component

API-shape preference:

- one obvious lower prepared-turn entrypoint for request construction
- one obvious lower prepared-turn entrypoint for prepared-request execution
- one obvious lower prepared-turn entrypoint for record shaping
- `psi.turn` remains the public higher session-oriented facade above those lower APIs

## Acceptance

- `turn-runtime` remains the authoritative lower component identity for turn work below dispatch orchestration
- lower prepared-turn request assembly no longer resides authoritatively under `components/agent-session/`
- lower response classification / record shaping no longer resides authoritatively under `components/agent-session/`
- `psi.turn` no longer depends on `psi.agent-session.prompt-request` or `psi.agent-session.prompt-recording`
- `psi.turn` becomes clearer as a higher dispatch-owning/session-owned facade
- no dispatch invocation moves below `agent-session`
- no new component cycle is introduced
- focused verification proves both the expanded component and at least one higher-level consuming path

## Concrete done criteria

- the task records explicitly that this is an expansion of the existing `turn-runtime` component, not creation of a sibling `turn-preparation` component
- authoritative lower request-preparation implementation lives under `components/turn-runtime/`
- authoritative lower response-recording implementation lives under `components/turn-runtime/`
- old `agent-session` request/recording namespaces are removed or left only as temporary migration shims and then removed before completion
- `psi.turn` depends downward on `psi.turn-runtime.*` for lower prepared-turn work
- dispatch/effect choreography remains above the boundary
- final repo search confirms no lingering authoritative ownership of lower prepared-turn code under `agent-session`

## Suggested migration sequence

1. review landed `101`, `103`, and current `turn-runtime` shape
2. classify current `prompt_request` responsibilities into:
   - lower prepared-turn assembly
   - session-owned projection/policy that must stay above the boundary
3. move the lower prepared-turn assembly into `components/turn-runtime/`
4. move `prompt_recording` into `components/turn-runtime/`
5. extract any small pure helpers from `psi.turn` or `psi.turn.handlers` when they clearly belong with the lower prepared-turn boundary
6. update `psi.turn` to depend downward on the expanded `turn-runtime` component
7. update direct consumers and focused tests
8. remove any temporary migration shims
9. run focused verification and record the final ownership result

## Verification intent

Focused verification should prove both architecture and behavior.

Minimum intent:

- focused `turn-runtime` tests for request/execution/recording behavior
- focused higher-level tests proving `psi.turn` still orchestrates turns correctly through the expanded lower component
- repo search proving lower prepared-turn code is no longer authoritatively owned by `agent-session`

Representative higher-level consuming surfaces:

- `psi.agent-session.prompt-lifecycle-test`
- `psi.agent-session.prompt-execution-test`
- any other direct consumer touched by migration

## Risks

- accidentally reviving `turn-preparation` as a separate sibling component instead of strengthening `turn-runtime`
- moving session-owned projection/policy below the boundary prematurely
- leaving `psi.turn` mixed enough that its role is still ambiguous after the change
- broadening the task into prompt-composition extraction rather than staying focused on the prepared-turn boundary

## Related work

- `100-turn-statechart-component-extraction`
- `101-turn-runtime-component-extraction`
- `103-turn-runtime-ownership-boundary-repair`
- `105-agent-session-component-extraction-map`
- superseded historical framing from `102-turn-preparation-component-extraction` should be treated as background only, not as the component target for this task
