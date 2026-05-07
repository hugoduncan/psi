# 103 — Turn runtime ownership boundary repair

## Goal

Repair the ownership boundary left incomplete after task `101` so `turn-runtime` becomes a genuinely lower component rather than a renamed extraction that still depends on `agent-session` internals.

## Why

Task `101-turn-runtime-component-extraction` successfully moved the live turn execution files into `components/turn-runtime/`, but review found two structural defects:

- `components/turn-runtime/deps.edn` depends on `psi/agent-session`
- `components/agent-session/deps.edn` depends on `psi/turn-runtime`

This creates a direct component cycle.

The review also found that `turn-runtime` still requires `agent-session` namespaces for lower execution-time concerns:

- `psi.agent-session.state-accessors`
- `psi.agent-session.conversation`

That means the new component still reaches upward into the session domain for mutation vocabulary, telemetry writes, and helper logic. The extraction is therefore not yet aligned with the intended architecture.

## Problem

The current `turn-runtime` component is structurally mixed:

- some of its responsibilities are truly lower turn-runtime concerns
- some of the state mutation/event names it uses are still owned by `agent-session`
- some helper logic it depends on is generic enough to belong lower than `agent-session`
- one adjacent responsibility, tool output accounting, appears to belong to a tool-domain component rather than to either `turn-runtime` or `agent-session`

Without correcting that ownership, future extractions of `psi.turn` and lower turn/tool components will continue to drag the session domain downward.

## Intent

Repair ownership, not behavior.

This task should:

- remove the direct `agent-session <-> turn-runtime` component cycle
- move turn-execution mutation ownership out of `agent-session`
- keep journal append semantics above the boundary
- avoid callback-based seams
- preserve explicit data-oriented boundaries
- identify and separate tool-domain accounting from turn-runtime ownership

This task should not redesign turn behavior, prompt lifecycle behavior, or tool execution behavior.

## Key decisions already settled

### No callbacks

The follow-up must **not** introduce callback-based indirection to paper over the boundary.

The preferred direction is:

- move lower ownership down where the responsibility is genuinely lower
- keep higher ownership above the boundary where the responsibility is genuinely session-domain
- use explicit state/mutation/data surfaces rather than injected callback hooks

Positive clarification:

- acceptable replacement patterns are lower-owned mutation/state APIs and data-returning lower functions whose results are then applied by the owning lower layer
- unacceptable replacement patterns are higher-layer function injection, callback registration, or behavior-passing from `agent-session` down into `turn-runtime`

### Mutation/effect clarification

The `state-accessors` functions currently used by `turn-runtime` are not primarily effect execution seams.

For the turn-runtime-relevant paths, they are mostly facades over dispatch-routed pure state updates.

So the problem is not “turn-runtime needs effects”.
The problem is “turn-runtime is using session-owned mutation vocabulary for lower-owned state updates”.

### Ownership clarification

These current responsibilities likely belong to `turn-runtime` rather than `agent-session`:

- active turn context mutation
- tool-call-attempt telemetry mutation
- provider request capture mutation
- provider reply capture mutation

This current responsibility likely does **not** belong to `turn-runtime`:

- `record-tool-output-stat`

Tool output accounting appears to be tool-domain ownership and should move toward a tool-owned component rather than remain part of the repaired turn-runtime boundary.

## Scope

In scope:

- break the component cycle between `agent-session` and `turn-runtime`
- remove remaining `agent-session` namespace requires from `psi.turn-runtime.core` and `psi.turn-runtime.accumulator`
- re-home lower turn-execution mutation ownership out of `agent-session`
- move or extract generic helper logic currently borrowed from `agent-session`
- keep explicit data/state boundaries
- refine test ownership only as needed to prove the repaired boundary
- record any intentionally deferred tool-component follow-on if tool accounting cannot fully move in this slice

Out of scope:

- redesigning turn runtime behavior
- redesigning prompt lifecycle orchestration
- redesigning tool execution behavior
- extracting `psi.turn`
- full tool-component extraction
- broad cleanup unrelated to ownership repair

## Boundary

### `turn-runtime` should own

- stream transport helpers
- turn accumulation
- prepared-turn live execution
- active turn context state
- provider capture state
- turn-local tool-call-attempt telemetry
- any generic helper required exclusively for those responsibilities

### `agent-session` should own

- prompt lifecycle orchestration
- journal append execution semantics
- session dispatch coordination
- context callback registration through `psi.turn`
- session-level policy and integration concerns

### tool domain should own

- tool output accounting
- truncation/limit-hit accounting
- per-tool output aggregates

This task may only partially realize the tool-domain move if a dedicated tool component does not yet exist, but it must stop treating that responsibility as part of `turn-runtime` ownership.

## Target shape

Preferred target after this task:

- `components/turn-runtime/deps.edn` no longer depends on `psi/agent-session`
- lower-owned turn state/mutation surfaces live in `turn-runtime`, not under `:session/...` ownership
- if a very small lower shared state namespace is required to avoid mixing state substrate and runtime logic, that is allowed only when recorded explicitly in `implementation.md`; default expectation is to keep this repair inside `components/turn-runtime/`
- `psi.turn-runtime.core` and `psi.turn-runtime.accumulator` no longer require `psi.agent-session.state-accessors`
- `psi.turn-runtime.accumulator` no longer requires `psi.agent-session.conversation` for generic parsing helpers
- `agent-session` remains the orchestrator above `psi.turn` and `psi.turn-runtime`

Tool-accounting minimum acceptable outcome for this slice:

- `turn-runtime` must no longer own, require, or define tool-output accounting policy
- a full tool-component extraction is not required
- if no tool component exists yet, tool-output accounting may remain temporarily outside `turn-runtime` provided the ownership is treated as deferred tool-domain work and recorded explicitly in `implementation.md`

## Acceptance

- no direct component cycle exists between `agent-session` and `turn-runtime`
- `components/turn-runtime/deps.edn` has no dependency on `psi/agent-session`
- `psi.turn-runtime.core` has no require on `psi.agent-session.*`
- `psi.turn-runtime.accumulator` has no require on `psi.agent-session.*`
- lower turn-execution state updates no longer use `psi.agent-session.state-accessors`
- repaired lower mutation/state ownership lands in `components/turn-runtime/` unless an explicitly recorded minimal lower shared namespace is required
- journal append ownership remains above `turn-runtime`
- no callback-based seam was introduced to solve the boundary
- behavior remains unchanged in focused verification
- any deferred tool-accounting ownership move is explicitly recorded

## Suggested implementation direction

1. identify every `turn-runtime` dependence on `agent-session`
2. classify each dependence as:
   - turn-runtime-owned
   - tool-domain-owned
   - session-owned
3. move turn-runtime-owned mutation/state surfaces downward into `components/turn-runtime/` by default
4. move/extract generic helpers downward
5. stop routing lower-owned updates through session-owned mutation names
6. leave journaling above the boundary
7. isolate tool-accounting ownership as a separate concern
8. run focused verification and record the repaired ownership result

## Verification intent

Focused verification should prove both architecture and behavior.

Minimum concrete verification surfaces:

- `psi.turn-runtime.core-test`
- `psi.turn-runtime.accumulator-test`
- `psi.agent-session.prompt-lifecycle-test`

Additional higher-level verification is allowed when the implementation touches other consumers, but those three surfaces are the minimum required proof.

Verification requirements:

- focused `turn-runtime` tests remain green
- focused higher-level turn/session lifecycle consumers remain green
- repo search proves no remaining `psi.agent-session.*` requires inside `components/turn-runtime/src/`
- deps/config inspection proves the component cycle is gone
- `implementation.md` records the exact commands used

## Risks

- accidentally reintroducing callback seams
- moving too much session behavior down into `turn-runtime`
- conflating turn-runtime repair with a full tool-component extraction
- renaming mutation ownership without preserving behavior proof

## Related work

- `101-turn-runtime-component-extraction` created the extracted component but left an ownership/cycle defect
- `102-turn-preparation-component-extraction` is an adjacent follow-on for pure turn shaping; it should build on a repaired turn-runtime boundary rather than a cyclic one
