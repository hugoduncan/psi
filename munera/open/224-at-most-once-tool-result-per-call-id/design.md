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
2. On turn interruption, `:on-agent-done`
   (`dispatch_handlers/statechart_actions.clj`) sees `:interrupt-reason`
   (e.g. `:user-abort`) and emits `:runtime/record-pending-tool-call-interrupts`,
   which for **every still-pending** id dispatches
   `:session/tool-agent-record-result` with a synthetic `"interrupted"`
   error toolResult.
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
`journal->provider-messages` → two `tool_result` blocks for one `tool_use` →
provider 400 on the next request.

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
- An aborted, still-in-flight async tool keeps its `"interrupted"` result for the
  model-visible turn; the tool's eventual real completion is still delivered
  through the existing async/background completion path (chat-injection /
  background-job terminal), not as a second `tool_result` for the aborted call.
- No change to the non-interrupted happy path: a normal tool call still records
  exactly one real result.
- Existing journals already containing duplicates should not crash subsequent
  requests. The provider-facing projection
  (`prompt-request/journal->provider-messages` and the conversation rebuild)
  must tolerate already-persisted duplicates by emitting at most one
  `tool_result` per id. (Whether this defensive de-dup is in scope or split into
  a follow-up is an open question — see below.)

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
  single-source-of-truth atom invariant. The two racing producers (interrupt
  path `turn.clj:223`, real-result path `tool_runtime_adapter.clj:114`) both
  dispatch the same event; dispatch ordering decides the first writer, the second
  reads its own id already present and is suppressed.
- `:pending-tool-calls` (agent-core) is retained for its existing runtime use
  (enumerating still-pending ids on interrupt, `turn.clj:220`); it no longer
  gates effect emission. The canonical recorded-ids predicate — not the agent-core
  atom — is the source of truth for the at-most-once decision.
- The recorded-ids set is bounded by being session-scoped and cleared/reset on
  the same lifecycle boundaries that already reset `:pending-tool-calls`
  (turn/session reset). Plan-time detail: confirm the exact reset point so the
  set does not accrete across turns.

This supersedes the earlier lean toward (B); Scope below is updated to reference
the canonical predicate rather than `:pending-tool-calls`.

## Remaining Open Question

2. **Defensive projection de-dup.** Should this task also make
   `journal->provider-messages` / conversation rebuild tolerate pre-existing
   duplicate journals (so already-corrupted sessions recover), or is that a
   separate task? Forward-fix (stop creating duplicates) is the core deliverable;
   defensive de-dup is a recovery concern that may be bundled or split.

3. **First-wins outcome on abort.** Confirm that keeping the `"interrupted"`
   result (and dropping the late real result from the conversation) is the
   intended model-visible behaviour for an aborted async tool, given the real
   result is still surfaced via the background-completion path.

## Acceptance Criteria

- Reproduction test (interrupt a turn with a pending tool-call, then record a
  late real result) fails before the fix and passes after, asserting exactly one
  `tool_result` per `tool_use` id in the provider-facing conversation.
- The at-most-once invariant holds for both the journal and the in-memory message
  history, first-writer-wins, for any tool (`delegate`, `bash`, `psi-tool`, …).
- The non-interrupted single-result path and the interrupt-only path each yield
  exactly one result; existing agent-core / agent-session tests stay green.
- `bb test` green; clj-kondo clean; CHANGELOG updated if user-visible (a tool-use
  no longer wedges the session after an abort qualifies as a user-visible fix).
