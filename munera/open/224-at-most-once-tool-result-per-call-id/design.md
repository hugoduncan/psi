# 224 Fix: duplicate tool_result when an interrupt races a tool's own result

## Intent

A delegated workflow (e.g. `lambda-build`) — and, more generally, any tool — can
leave a session in a state where a later provider request fails with:

```
messages.N.content.M: each tool_use must have a single result.
Found multiple `tool_result` blocks with id: toolu_… (status 400)
```

The session becomes unusable for further turns because every subsequent request
replays a malformed conversation. This task removes the defect that produces two
`toolResult` journal entries for a single `tool_use` id, restoring the invariant
that a tool call has exactly one result.

This is **not** a `lambda-build`, `.edn`-workflow, or `delegate` defect. The
workflow runs to completion cleanly. `delegate` only surfaces a general
agent-session core race; the same duplication occurs for `bash` and `psi-tool`.

## Root Cause

The invariant **at most one `toolResult` per `tool-call-id`** is not enforced.
Two independent producers can each record a result for the same in-flight tool
call:

1. `emit-tool-start-in!` (agent-core) adds the tool-call-id to
   `:pending-tool-calls`.
2. On turn interruption, an interrupt result is recorded for **every
   still-pending** id. There are **two distinct interrupt producers** in the
   code, each enumerating `:pending-tool-calls` and dispatching
   `:session/tool-agent-record-result` with a synthetic `"interrupted"` error
   toolResult:
   - the **statechart effect path**: `:on-agent-done`
     (`dispatch_handlers/statechart_actions.clj:129/149`) sees
     `:interrupt-reason` (e.g. `:user-abort`) and emits the
     `:runtime/record-pending-tool-call-interrupts` effect, whose handler
     (`dispatch_effects.clj:127`) enumerates `:pending-tool-calls` and dispatches
     the record event; and
   - the **synchronous abort path**: `abort-in!` (`turn.clj:233`) calls
     `record-pending-tool-call-interrupts!` (`turn.clj:217`), which enumerates
     `:pending-tool-calls` (`turn.clj:220`) and dispatches the record event
     synchronously.

   Both producers converge on the **same single event**
   `:session/tool-agent-record-result`, so the single-chokepoint guard there
   covers both. The reproduced `:user-abort` Evidence below can flow through the
   statechart-effect producer (effect executed during dispatch), not only the
   literal synchronous `abort-in!` call.
3. The in-flight tool then **also** completes and dispatches
   `:session/tool-agent-record-result` with its real result.
4. The `:session/tool-agent-record-result` handler
   (`dispatch_handlers/session_mutations.clj`) unconditionally produces **both**
   an in-memory record (`agent-core/record-tool-result-in!`) **and** a journal
   append (`journal-append-effect/append-message-effect`). There is no
   first-wins guard or dedup at any layer — not in agent-core, not in the
   journal append, and not in the conversation rebuild
   (`turn-runtime/conversation.clj` emits one `tool_result` block per
   `toolResult` message, keyed by tool-call-id).

Result: two journal `toolResult` entries with one tool-call-id →
`journal->provider-messages` (two duplicate `toolResult`-role provider *message
maps*) → conversation rebuild (`turn-runtime/conversation.clj`
`agent-messages->ai-conversation`, which emits one provider `tool_result`
*block* per `toolResult` message via `conv/add-tool-result`
`conversation.clj:95`) → two `tool_result` blocks for one `tool_use` → provider
400 on the next request. The block-emitting projection is the rebuild, not
`journal->provider-messages` (which only emits message maps) — consistent with
Root Cause step 4 and the Desired-Behaviour De-dup Location bullet.

### Evidence

Confirmed sequence in a real journal (`…psi-run-simplification…614f0c01…ndedn`):

```
assistant   [tool_use delegate  id=toolu_01C9Nn…]
toolResult  "interrupted"  is-error  reason=:user-abort   id=toolu_01C9Nn…
toolResult  "delegate"     "Workflow run 215-design-review started"  id=toolu_01C9Nn…
```

A scan of all persisted session journals shows every duplicate-tool-call-id case
has exactly this shape: one synthetic `"interrupted"` result plus one real result
(`delegate`, `bash`, `psi-tool`). The race is systemic, not specific to one tool.

## Desired Behaviour

- A given `tool-call-id` produces **at most one** `toolResult` entry in the
  journal and in the in-memory message history — **first writer wins**.
- Whichever of {real tool result, interrupt result} is recorded first is kept;
  the later one is suppressed (both its in-memory record and its journal append).
- An aborted, still-in-flight tool keeps its `"interrupted"` result for the
  model-visible turn. The tool's eventual real completion **still dispatches
  `:session/tool-agent-record-result`** (the adapter `:record-result!` always
  re-dispatches the event — `tool_runtime_adapter.clj:114`); the recorded-ids
  guard in the handler is what prevents it becoming a second `tool_result` by
  suppressing both its in-memory record and its journal append. The
  async/background completion path (chat-injection / background-job terminal) is
  an **orthogonal content-delivery mechanism** that surfaces the real outcome to
  the model in a *later* turn; it is not what suppresses the duplicate and does
  not itself emit a `tool_result` for the aborted call. (See D1 Mechanism.)
- **Interrupt-first is guaranteed for the headline abort race, not left to
  dispatch tie-breaking.** Either interrupt producer (the statechart-effect path
  `dispatch_effects.clj:127`, reached via `:on-agent-done`, or the synchronous
  `abort-in!` path `turn.clj:217/233`) enumerates only *still-pending*
  tool-call-ids and records their `"interrupted"` results as part of handling the
  abort — the statechart path during effect execution, the synchronous path in
  the `abort-in!` call. In both cases the interrupt result for a still-pending
  tool is recorded before that tool's own real-result re-dispatch: a real result
  for a still-in-flight tool necessarily arrives *after* the abort that
  enumerated it as pending, so its id is already in recorded-ids and it is
  suppressed. First-writer-wins is the general invariant
  mechanism; for the aborted case the first writer is deterministically the
  interrupt because it is recorded while the tool is still pending. (The only
  way the real result could win is if it completed and recorded *before* the
  abort enumerated it — in which case the tool was no longer pending and no
  interrupt result is emitted for it, so there is still exactly one result.)
- **Synchronous tools (`bash`, `psi-tool`) have no background-completion path.**
  When their real result is suppressed by an abort, it is **silently dropped** —
  the model sees only the `"interrupted"` result. This is the intended
  model-visible behaviour: the user aborted the turn, so the discarded synchronous
  output is expected. Only async/background tools (e.g. `delegate`) re-surface
  their real outcome via the background path in a later turn.
- No change to the non-interrupted happy path: a normal tool call still records
  exactly one real result.
- Existing journals already containing duplicates must not crash subsequent
  requests. The provider-facing projection must tolerate already-persisted
  duplicates by emitting at most one `tool_result` per id. **This defensive
  de-dup is in scope** (see Scope): the forward-fix alone leaves already-wedged
  sessions broken because their journals already contain two `toolResult`
  entries, so recovering them requires the projection to be tolerant. It is a
  cheap, purely-derived projection guard keyed by tool-call-id.
- **De-dup location: a single upstream chokepoint at
  `prompt-request/journal->provider-messages`, not two independent guards.** The
  production provider-request pipeline is: journal →
  `session->provider-messages` / `journal->provider-messages`
  (`prompt_request.clj:111`, emits `toolResult`-role provider *message maps*) →
  `agent-messages->ai-conversation` (`turn_runtime/conversation.clj:136`, the
  conversation rebuild, which emits exactly **one** provider `tool_result`
  *block* per `toolResult` message via `conv/add-tool-result`
  `conversation.clj:95`). The rebuild is the only place provider `tool_result`
  blocks are emitted, but in this path its input
  (`:turn/messages`, `prompt_request.clj:296`) is the **journal-derived**
  message list — it consumes `journal->provider-messages`' output, it is not fed
  a separate in-memory history. (`build-provider-conversation`
  `request.clj:54/60` is the rebuild's only production caller and reads
  `:turn/messages`.) Because the rebuild maps `toolResult` messages to blocks
  one-to-one, dropping duplicate `toolResult` messages once at
  `journal->provider-messages` (later message whose tool-call-id already
  appeared is dropped) removes the duplicate before it can become a second
  provider block. A single guard at the upstream journal projection therefore
  suffices; **no independent guard is added at the conversation rebuild.**
- **De-dup keying source: the journal, by `tool-call-id`, first occurrence
  wins.** Because the guard lives at `journal->provider-messages`, "purely
  derived from the journal" is accurate: it keys off the `:tool-call-id` of
  `toolResult` journal-derived messages, keeping the first and dropping later
  duplicates. The conversation rebuild is not a separate de-dup site and so has
  no separate keying source; it inherits the already-de-duped journal-derived
  messages. (The rebuild's generic docstring "from agent-core message history"
  describes its general contract; in the provider-request path that history is
  the journal-derived `:turn/messages`.)

## Scope

In scope:

- Enforce the at-most-once invariant at the single chokepoint where a toolResult
  is recorded for a tool-call-id, covering **both** the in-memory record and the
  journal append as one both-or-neither decision, guarded purely in the
  `:session/tool-agent-record-result` dispatch handler against a **canonical
  recorded-tool-result-ids predicate in `:state*`** (see Design Decision D1).
  Atomicity comes from dispatch serialization, not from a runtime test-and-set.
- A characterization/regression test that reproduces the abort-races-tool-result
  duplication end-to-end (interrupt with a pending tool-call, then a late real
  result) and asserts a single `tool_result` per `tool_use` in the rebuilt
  provider conversation.
- Coverage that the normal single-result path is unaffected and that the
  interrupt-only path still yields exactly one `"interrupted"` result.
- Defensive de-dup at a single upstream chokepoint —
  `prompt-request/journal->provider-messages` — so already-persisted duplicate
  journals do not crash subsequent requests. It drops a `toolResult`
  journal-derived message whose `tool-call-id` already appeared (first
  occurrence wins, purely derived from the journal), so the downstream
  conversation rebuild (`turn_runtime/conversation.clj`
  `agent-messages->ai-conversation`), which emits the provider `tool_result`
  blocks one-per-`toolResult`-message, sees at most one result per id. The
  rebuild consumes this de-duped journal-derived message list and is **not** a
  second de-dup site (see the Desired-Behaviour location/keying bullets).

Out of scope:

- Changing the async/background delegate completion delivery (chat-injection,
  background-job terminal emission) — those are correct and unrelated.
- Workflow definitions (`lambda-build` et al.) — they are not defective.
- Broad refactor of the interrupt subsystem beyond enforcing the invariant.

## Design Decisions (resolved in refinement)

### D1. Where the guard lives — **Option (C): canonical-state predicate**

**Decision.** Enforce the at-most-once invariant with a *canonical* predicate
projected into session root-state (`:state*`) through dispatch, read purely by
the `:session/tool-agent-record-result` handler. **Option (B) is rejected.**

**Rationale (grounded in doc/architecture.md).**

- *State boundary (canonical root vs runtime handles).* `:pending-tool-calls`
  lives on the agent-core data atom and is mutated via `swap-data!`
  (`agent_core/core.clj:424`). It is a *runtime handle's* internal mutable
  lifecycle, explicitly *not* queryable canonical `:state*`. The architecture's
  stated direction is: when a subsystem has observable status worth querying,
  that status is **projected into `:state*` as canonical data through dispatch**,
  while the handle stays external. "At-most-once tool-result has been recorded
  for `tool-call-id`" is exactly such observable, queryable status. Anchoring the
  guard to the agent-core atom (Option B) runs against this direction; per the
  shims/adapters guidance a deviation would require an explicit documented design
  decision — and here no deviation is warranted because the architecturally
  aligned option is available at acceptable cost.

- *Dispatch sequencing contract.* The contract is: handler computes a **pure
  result** → apply writes state → effects execute **last**. Option (B)'s
  "agent-core atomically decides `applied?`, journal append happens only when
  applied" is a stateful test-and-set against a runtime atom at effect-decision
  time — a mutation that gates effect emission, which is *not* pure-result +
  effects-as-data. Option (C) keeps the shape pure: the handler reads the
  canonical predicate, returns a `:root-state-update` that records the id, and
  emits both effects or neither based on that pure read.

- *Cross-component layering.* The journal append is an agent-session (higher
  component) effect; `:pending-tool-calls` ownership is in agent-core (lower
  component). Making a higher-layer effect conditional on a lower-layer atomic
  decision couples the layers. Option (C) places the applied?/effects decision in
  the agent-session pure handler, reading canonical `:state*`; agent-core remains
  the data/handle the system projects from, not the gate.

**Mechanism.**

- Introduce a canonical, per-session set of recorded tool-result ids in
  `:state*` (the at-most-once predicate). Maintain it only through dispatch.
- The `:session/tool-agent-record-result` handler becomes a pure guarded
  transform:
  - read canonical recorded-ids for the session;
  - if `tool-call-id ∈ recorded-ids` → `applied? = false`: return no
    `:root-state-update` and **emit neither effect** (suppress both the
    `:runtime/agent-record-tool-result` in-memory record and the
    `append-message-effect` journal append);
  - else → return a `:root-state-update` adding `tool-call-id` to recorded-ids,
    and emit **both** effects.
- **Atomicity** comes from dispatch serialization (single writer to `:state*`),
  not from a test-and-set on the agent-core atom — consistent with the
  single-source-of-truth atom invariant. The racing producers all dispatch the
  same event `:session/tool-agent-record-result`: the **two interrupt producers**
  (statechart-effect path `dispatch_effects.clj:127` via `:on-agent-done`, and
  synchronous abort path `turn.clj:217/233`) and the **real-result path**
  (`tool_runtime_adapter.clj:114`). Dispatch ordering decides the first writer;
  any later dispatch of the same id reads it already present and is suppressed.
  The single chokepoint covers all three producers.
- **First-writer-wins is the general mechanism; the aborted-tool outcome is still
  deterministic.** Generic first-writer-wins says "whichever dispatch of the
  record event for an id is serialized first is kept" (covering both interrupt
  producers and the real-result producer). For the headline abort race that does
  *not* leave
  the model-visible result to chance: the interrupt path only enumerates
  *still-pending* tool-call-ids and records their `"interrupted"` results as part
  of handling the abort (the statechart-effect producer during effect execution,
  or the synchronous `abort-in!` producer in-line), while a real result for a
  still-in-flight tool can only arrive *after* abort. So the interrupt is
  deterministically the first writer for any tool that was still pending at
  abort, and its `"interrupted"` result is the one kept. (If a tool had already completed and recorded its real
  result before abort, it is no longer pending, the interrupt path emits nothing
  for it, and there is still exactly one result.) See the Desired Behaviour
  "Interrupt-first is guaranteed" bullet.
- `:pending-tool-calls` (agent-core) is retained for its existing runtime use
  (enumerating still-pending ids on interrupt). Both interrupt producers
  enumerate it: the synchronous abort path at `turn.clj:220` and the
  statechart-effect path at `dispatch_effects.clj:131`. It no longer gates effect
  emission. The canonical recorded-ids predicate — not the agent-core atom — is
  the source of truth for the at-most-once decision.
- **Persistence/reset boundary (outcome-determining, not a plan detail).** The
  recorded-ids set must **persist for the session lifetime**, not reset at the
  turn boundary. The headline race is cross-turn: an aborted tool's real result
  arrives *after* the turn that recorded the interrupt. If recorded-ids reset at
  the turn boundary, the late real result would find its id absent and would
  *not* be suppressed — re-introducing the very duplicate this task removes. The
  id must therefore outlive the turn that recorded it and remain present until
  the late real result has been resolved (suppressed). Concretely: recorded-ids
  is session-scoped and cleared only on session reset/clear (the same boundary
  that discards the journal/history), **not** on the per-turn boundary that
  resets `:pending-tool-calls`. This deliberately decouples recorded-ids
  lifetime from `:pending-tool-calls` lifetime. The set is bounded because
  tool-call-ids are finite per session and the journal it guards is itself
  session-scoped.

This supersedes the earlier lean toward (B); Scope below is updated to reference
the canonical predicate rather than `:pending-tool-calls`.

## Resolved Questions

All previously-open questions are now resolved in this design; none remain open.

1. **Guard location (B vs C).** Resolved in Design Decision D1: Option (C),
   canonical recorded-tool-result-ids predicate in `:state*`. Option (B)
   rejected.

2. **Defensive projection de-dup.** Resolved: **in scope** (see Scope and the
   Desired-Behaviour location/keying bullets). The forward-fix alone leaves
   already-wedged sessions broken, so the projection must emit at most one
   `tool_result` per tool-call-id to recover them. The guard is a **single
   upstream chokepoint at `journal->provider-messages`**, keyed off the journal
   `tool-call-id` (first occurrence wins); the downstream conversation rebuild
   consumes the de-duped messages and is not a second de-dup site. Cheap,
   purely-derived, first-occurrence-wins.

3. **First-wins outcome on abort.** Resolved: keeping the `"interrupted"` result
   and suppressing the late real result is the intended model-visible behaviour.
   For async/background tools the real outcome is re-surfaced via the
   background-completion path in a later turn; for synchronous tools it is
   silently dropped (the user aborted). Interrupt-first is deterministic for
   still-pending tools (see the Desired Behaviour determinism and sync-tool
   bullets, and D1 Mechanism).

## Acceptance Criteria

- Reproduction test (interrupt a turn with a pending tool-call, then record a
  late real result) fails before the fix and passes after, asserting exactly one
  `tool_result` per `tool_use` id in the provider-facing conversation.
- The at-most-once invariant holds for both the journal and the in-memory message
  history, first-writer-wins, for any tool (`delegate`, `bash`, `psi-tool`, …).
- The non-interrupted single-result path and the interrupt-only path each yield
  exactly one result; existing agent-core / agent-session tests stay green.
- A journal already containing duplicate `toolResult` entries for one
  tool-call-id projects to exactly one `tool_result` per id through the upstream
  `journal->provider-messages` de-dup (and thus through the downstream
  conversation rebuild that consumes its output), so an already-wedged session
  recovers on its next request.
- `bb test` green; clj-kondo clean; CHANGELOG updated if user-visible (a tool-use
  no longer wedges the session after an abort qualifies as a user-visible fix).
