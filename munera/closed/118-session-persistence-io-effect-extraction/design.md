# 118 — Session persistence IO effect extraction

## Goal

Move session persistence file IO behind explicit dispatch effects while keeping canonical session persistence semantics in the lower `session-persistence` component.

## Why

Task `117-session-persistence-component-extraction` successfully moved canonical session-facing persistence ownership into `psi.session-persistence.core`, but the extraction exposed a deeper boundary problem:

- persistence subtree ownership is now lower-level
- but persistence helpers still perform file IO directly
- and an initial implementation attempt briefly created a load cycle when the lower component tried to reuse higher/generic state helpers

That cycle was fixed locally, but it revealed a broader architectural mismatch:

- state mutation and persistence semantics are partly treated as pure domain logic
- persistence file writes are still performed directly from domain helpers instead of through explicit dispatch-owned effects

This conflicts with the project architecture direction:

- pure handlers
- effects as data
- dispatch owns side-effect execution
- replay fidelity improves as runtime-owned side effects move under explicit effect execution

## Reconnaissance findings

A code pass against the current implementation sharpens the target boundary.

### Current production append path

The current production append flow is:

1. `:session/append-journal-entry` handler returns `{:effects [{:effect/type :persist/journal-append-entry ...}]}`
2. `dispatch-effects/execute-effect!` handles `:persist/journal-append-entry`
3. that executor calls `persist/append-entry-in!`
4. `persist/append-entry-in!`:
   - appends in-memory journal state via `append-journal-entry-in!`
   - then calls `persist-journal-in!`
5. `persist-journal-in!` directly performs append-or-flush file IO and, on flush, mutates `:flushed?`

So the existing append path is already dispatch-owned at the outermost level, but the executor still delegates into a lower helper that mixes:

- in-memory mutation
- persistence policy
- direct file IO
- post-success flush-state mutation

That means the real extraction seam is narrower and more concrete than the original design wording implied: the task should split the current single executor-side helper path into a pure state update plus a separate explicit persistence IO effect.

### Current fork persistence path

`session_lifecycle.clj` currently calls `journal-store/flush-journal!` directly during `fork-session-in!` to create/write the child session file with lineage metadata.

That confirms the task is not only about incremental append flows. There is at least one lifecycle write path that bypasses explicit persistence effect execution entirely.

### Current handler/effect shapes already constrain the first cut

The current handler and schema surface already includes:

- `:session/append-journal-entry`
- `:persist/journal-append-entry`
- specialized append-entry effect variants for message/model/thinking-level/session-info

This means the first cut does not need to redesign the event vocabulary seen by upstream callers. The extraction can happen underneath the existing handler/event surface.

### Current session metadata lookup behavior matters

Today `append-entry-in!` pulls `:worktree-path`, `:parent-session-id`, and `:parent-session-path` from session state just before persistence.

That makes one design constraint explicit:

- either the pure request-shaping helper accepts those values as arguments from the handler/executor
- or the executor remains responsible for materializing those inputs from session state before calling the pure helper

The executor must not recompute persistence policy, but it may still gather the session metadata needed to ask the lower policy layer for a request.

## Problem

`psi.session-persistence.core` currently mixes three concerns:

1. persistence-specific domain ownership
   - journal path ownership
   - flush-state ownership
   - persistence subtree initialization
   - semantic journal entry constructors
   - lazy-flush policy
2. in-memory root-state mutation
   - append journal entry under the persistence subtree
   - update flush-state under the persistence subtree
3. external side effects
   - append entry lines to session files
   - bulk flush journal files
   - file-header/session-file store interaction through `session-journal.store`

That mixed ownership causes several problems:

- persistence helpers are not purely semantic/domain-level
- file IO is harder to replay or suppress consistently
- dispatch/effect architecture is bypassed for persistence file writes
- tests must stub direct helper calls instead of effect execution seams
- future extractions can still hit boundary pressure similar to the cycle seen during `117`

## Intent

Keep `session-persistence` as the authoritative owner of persistence semantics, but move persistence file IO execution behind explicit effect data and effect executors.

This task is not trying to move persistence policy back upward into `agent-session`.

Instead, it should produce this split:

- `session-persistence` owns persistence semantics and pure decision logic
- dispatch/effect execution owns persistence file IO
- higher-level session orchestration still decides when journal entries are appended or sessions are created/resumed/forked

## Desired architectural split

### `psi.session-persistence.*` should own

- persistence-specific paths
  - `session-journal-path`
  - `session-flush-state-path`
- persistence subtree constructors
  - `flush-state`
  - `persistence-state`
- semantic entry constructors
- journal read helpers
- pure journal append / flush-state transition helpers
- pure decision logic for whether persistence requires:
  - no IO
  - append-one-entry IO
  - bulk-flush IO

### dispatch / effect execution should own

- executing file-backed persistence operations
  - append entry to disk
  - flush journal to disk
  - any header-writing that is part of that flush operation
- any runtime-specific side-effect suppression / replay behavior
- post-success state transition that marks the journal as flushed

### `agent-session` should continue to own

- session lifecycle orchestration
- dispatch registration
- when journal entries are appended
- when session create/resume/fork flows are invoked
- broader workflow/prompt/session policy

## Scope

### In scope

- introduce explicit persistence IO effect shape(s)
- refactor current direct persistence helper file writes so dispatch/effect execution performs them
- preserve current lazy-flush behavior
- preserve current append-in-memory-first behavior
- preserve current persisted session load/list/find semantics
- preserve current resume/fork session-file/header/lineage invariants
- route session-file writes used by session append/fork flows through explicit persistence IO effects
- keep persistence semantics owned by `session-persistence`
- improve replay/effect-boundary alignment for persistence writes

### Out of scope

- redesigning the persisted session file format
- redesigning `session-journal.store`
- changing journal entry schema
- redesigning session lifecycle policy
- redesigning dispatch generally beyond what is needed for persistence IO effects
- moving persisted session read/list/load wrappers out of `session-persistence`
- removing all compatibility wrappers from `117` unless this task naturally absorbs one or two of them safely
- redesigning upstream event vocabulary such as `:session/append-journal-entry` unless a very small compatibility adjustment is required

## Main design decisions

### 1. Separate persistence semantics from persistence IO execution

This is the core design move.

`session-persistence` should still answer questions like:

- where does the journal live in state?
- what is the persistence subtree shape?
- when does lazy flush occur?
- after a journal append, should persistence do nothing, append one line, or bulk flush?

But it should not directly perform the file IO that answers those decisions.

### 2. Keep append-first semantics canonical

Current behavior established and preserved through earlier tasks must remain true:

- append journal entry in memory first
- then decide whether persistence IO is needed
- then execute persistence IO if applicable

The effect extraction must not reverse that ordering.

### 3. Keep lazy flush semantics unchanged

Current behavior must remain:

- before the first assistant message, persistence IO is a no-op even if entries exist
- on the first assistant message, bulk flush writes header + all entries
- after flush, future writes append one entry at a time

This task changes ownership/execution shape, not behavior.

### 4. Use one explicit persistence IO effect family beneath the existing append surface

The first cut should use one generic persistence effect family with an operation discriminator rather than proliferating many top-level effect types.

Canonical direction:

- `:persist/session-journal-io`
  - `:op :append-entry`
  - `:op :flush-journal`

Refined first-cut boundary:

- existing higher-level append events/effects may remain as compatibility or orchestration surfaces
- but they must no longer be the point where direct file IO occurs
- instead, production append/fork flows should bottom out in `:persist/session-journal-io`

Rationale:

- the effect family stays small
- append-vs-flush remains explicit in effect data
- tests can cover one executor seam for real file IO
- future suppression/replay policy can target one persistence IO family cleanly
- current upstream handler/event contracts can remain stable while the lower execution boundary is cleaned up

### 5. Keep pure persistence decision logic in the lower component

The lower component should provide a pure helper that shapes the persistence action to take.

Canonical direction:

- input:
  - `entries` — the fully materialized would-be post-append journal entries vector
  - `flush-state` — includes at least `:session-file` and `:flushed?`
  - `session-id`
  - `worktree-path`
  - `parent-session-id`
  - `parent-session-path`
- output:
  - `nil` for no IO
  - or a canonical IO request description for append/flush

Clarification:

- the handler should compute the request from a locally derived post-append entries value
- the helper should not depend on post-apply state reads after `:root-state-update`
- the executor may gather metadata before calling the helper, but must not recompute persistence policy itself

This keeps persistence policy from drifting back into `dispatch-effects`.

### 6. Keep dependency direction clean

The cycle from task `117` should be treated as a design signal.

This task should preserve the corrected dependency direction:

- lower persistence semantics must not depend on higher generic state helpers for their own core behavior

If additional shared mutation helpers are needed, they should be truly lower/generic or local to the persistence component, not back-edges to a higher owner.

### 7. Mark `:flushed?` true only after successful flush IO

This task makes the flush-state transition explicit.

Canonical rule:

- do not mark the state flushed before the flush effect succeeds
- only successful `:op :flush-journal` execution may advance `:flushed?` to `true`
- failed append or flush IO must leave flush-state unchanged

This preserves alignment between in-memory flush-state and actual disk state.

### 8. Clarify the meaning of `:flushed?`

For this task, `:flushed?` should be treated as a mode flag meaning:

- the session has completed the initial full flush to disk
- future persistence should use incremental append behavior rather than first-flush bulk write behavior

It is not a precise guarantee that every later incremental append succeeded.

Implication:

- if `:flushed?` is already `true` and a later incremental append fails, this task does not introduce a new “disk fully in sync” status flag
- the first cut preserves current behavior and only guarantees that failed operations do not incorrectly advance `:flushed?` from `false` to `true`

### 9. Production dispatch flows should move memory append into handler root-state updates

The reconnaissance pass shows that today the append executor performs both memory mutation and persistence by calling `persist/append-entry-in!`.

The cleaner target is:

1. the handler applies the journal append via `:root-state-update`
2. the handler or a small helper asks lower persistence semantics whether IO is needed
3. the handler emits `:persist/session-journal-io` only when needed
4. the IO executor performs only file-backed persistence work, plus post-success flush-state update for flush operations

This better matches the dispatch architecture already used elsewhere in the project:

- state mutation is represented in handler results
- effect execution owns side effects
- the persistence IO executor becomes narrower and easier to suppress/replay

The first cut may preserve existing `:session/append-journal-entry` and `:persist/journal-append-*` vocabulary while shifting the actual memory append out of the executor and into handler state updates.

### 10. Existing `:persist/journal-append-*` effects become compatibility/convenience surfaces, not the canonical IO seam

The current effect family:

- `:persist/journal-append-entry`
- `:persist/journal-append-message-entry`
- `:persist/journal-append-model-entry`
- `:persist/journal-append-thinking-level-entry`
- `:persist/journal-append-session-info-entry`

may remain temporarily to preserve upstream ergonomics.

Canonical rule:

- these are no longer the authoritative production file-write seam
- the canonical production file-write seam is `:persist/session-journal-io`
- retained append effects should either:
  - be compatibility/convenience shims that route into handler-owned append + canonical IO effect shaping, or
  - be retired from production use while remaining temporarily schema-compatible

The task does not require immediate removal of these append effects, but it does require that real file-backed persistence bottoms out only in `:persist/session-journal-io`.

### 11. Session lifecycle file creation paths are in scope when they perform persistence writes

This task is not limited to incremental append flows.

If a lifecycle path writes session journal files directly in order to establish persisted session lineage or child-session files, that write is part of the same boundary problem.

Canonical rule:

- append-triggered journal persistence is in scope
- fork/session-file creation writes that flush a child journal to disk are also in scope
- persisted read/list/load wrappers remain in `session-persistence` and are not being redesigned here

### 12. Fork persistence should cross the same boundary via dispatch-owned effect declaration

For the first cut, avoid calling `dispatch-effects/execute-effect!` directly from lifecycle code.

Canonical direction:

- lifecycle code may continue to own the orchestration and input assembly for child-session persistence
- but the actual file write should cross the boundary through a dispatch-owned event/effect path that declares `:persist/session-journal-io`
- direct lifecycle calls to store IO should be removed from the production path

This keeps the fork path aligned with the same explicit effect architecture as append flows.

### 13. Failure semantics are explicit for the first cut

This task does not redesign persistence retries, but it does define minimum consistency behavior.

Canonical rule:

- in-memory append remains authoritative once the state update has occurred
- failed persistence IO does not roll back the in-memory journal append
- failed persistence IO does not mark `:flushed? true`
- append-after-flush failure leaves existing `:flushed?` mode unchanged
- any richer retry/recovery policy is out of scope unless already required by existing behavior

### 14. Preserve current upstream append ergonomics where possible

Because the current code already has specialized append effects and callers that dispatch `:session/append-journal-entry`, the design should prefer a lower-boundary refactor over a broad caller migration.

That means:

- callers may continue to dispatch `:session/append-journal-entry`
- specialized append constructors/effects may remain as convenience or compatibility surfaces
- the important change is that the production path beneath them becomes:
  - pure state update
  - pure request shaping
  - explicit IO effect execution

## Canonical IO request shape

The pure persistence decision helper should return either `nil` or a fully materialized request map.

Representative shape:

```clojure
{:op :append-entry
 :session-id session-id
 :session-file session-file
 :worktree-path worktree-path
 :parent-session-id parent-session-id
 :parent-session-path parent-session-path
 :entry entry}
```

or

```clojure
{:op :flush-journal
 :session-id session-id
 :session-file session-file
 :worktree-path worktree-path
 :parent-session-id parent-session-id
 :parent-session-path parent-session-path
 :entries entries}
```

Notes:

- the request should include all write inputs needed by the executor
- the executor should not need to re-derive lazy-flush policy from ambient session state
- `append-entry` should carry the last entry explicitly rather than forcing the executor to infer it again
- `flush-journal` should carry the full journal payload
- request-shaping may happen after gathering session metadata from state, but the policy decision itself should remain lower-owned and pure

## Suggested implementation shape

### Pure lower-component layer

Introduce or refine helpers in `psi.session-persistence.core` or a small internal support namespace to provide:

- pure append/root-update helpers for journal state
- pure flush-state transition helpers
- pure decision helper for persistence IO action selection

Representative shape only, not mandated naming:

- `append-journal-entry-root-update`
- `mark-flushed-root-update`
- `persistence-io-request`

A small internal namespace split is acceptable if it makes the public `core` surface clearer, but the lower component remains the authoritative owner of the semantics.

### Dispatch/handler layer

Refine the current append handlers so they own state mutation and effect declaration explicitly.

Representative execution flow for append:

1. `:session/append-journal-entry` handler derives `next-entries` locally by applying the append logic to the current entries
2. handler applies journal append via `:root-state-update`
3. handler gathers session metadata needed for persistence
4. lower persistence semantics compute whether IO is needed from `next-entries` and current flush-state
5. handler returns `:persist/session-journal-io` effect data when needed
6. executor performs file IO
7. on successful `:op :flush-journal`, executor applies the post-success flush-state update directly via `mark-flushed-root-update`

This is a refinement of the existing handler/effect surface, not a redesign of the caller-visible event contract.

### Dispatch/effect layer

Route actual file IO through explicit effect execution.

Representative execution flow for fork/session-file creation:

1. lifecycle logic creates or initializes in-memory child session state as it does today
2. lifecycle path dispatches through a state/effect path that shapes a fully materialized `:persist/session-journal-io` request for the child file flush
3. executor performs `flush-journal!` with lineage metadata
4. any needed post-success flush-state update is applied explicitly

### Write executor boundary

The executor may use lower-level store functions directly or thin canonical wrappers exposed by `session-persistence`, but this task must preserve one-way ownership:

- persistence policy lives in `session-persistence`
- concrete file IO runs at the explicit effect boundary
- the executor must not recompute persistence policy on its own

For the first cut, executor-side direct state update after successful flush is acceptable and preferred over introducing another state-only event, as long as the update uses the lower-owned `mark-flushed-root-update` helper.

## Acceptance

- persistence file IO no longer happens directly from canonical session-persistence domain helpers used in production append/fork flows
- explicit effect execution owns session journal file append/flush side effects
- `session-persistence` remains the owner of persistence semantics and decision logic
- in-memory append-first behavior is preserved
- lazy first-assistant flush behavior is preserved
- append-after-flush behavior is preserved
- persisted session load/list/find behavior is preserved
- the production append path no longer depends on executor-side calls to `persist/append-entry-in!` for combined memory+IO behavior
- retained `:persist/journal-append-*` effects, if any, are no longer the authoritative production file-write seam
- focused tests prove that persistence file writes are now exercised through effect execution seams rather than direct helper-local IO in production flows
- focused tests prove that successful flush execution is the only path that marks `:flushed? true`
- focused tests prove that persistence IO can be suppressed/replaced at the effect layer without losing the in-memory append behavior
- the dependency direction remains clean: `session-persistence` does not regain a dependency upward on `session-state.state`

## Testing

Focused proof should include:

- pure persistence decision logic tests
- dispatch/handler tests proving append state mutation occurs through handler-owned `:root-state-update` plus explicit IO-effect declaration
- focused proof that the canonical `:persist/session-journal-io` executor does not itself append to in-memory journal state
- dispatch/effect tests for append-vs-flush execution
- regression tests for lazy flush
- regression tests for append after flush
- regression tests for fork/resume flows that rely on persisted session files
- a focused proof that persistence IO execution is suppressible/replaceable at the effect layer
- a focused proof that failed flush does not advance `:flushed?`
- migration-sensitive tests updated away from stubbing `persist-journal-in!` as the production append seam

## Related work

- `098-journal-append-dispatch-effect-convergence` established dispatch-owned append-first semantics
- `099-session-journal-codec-and-store-extraction-from-agent-session` extracted the lower file/store substrate
- `117-session-persistence-component-extraction` made persistence ownership explicit and exposed the remaining IO-boundary issue
- `105-agent-session-component-extraction-map` should be able to reference this as the follow-on that completes the persistence boundary by separating semantics from effect execution
