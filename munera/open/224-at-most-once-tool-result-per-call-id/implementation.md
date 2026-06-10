# Implementation notes — 224

## Architecture-fit review (design.md)

Reviewed design.md for architectural fit (not ambiguity/correctness). Grounded
against AGENTS.md (S1–S3 VSM, dispatch-owns-writes, effects-as-data), META.md,
and doc/architecture.md (State boundary: canonical root vs runtime handles;
Dispatch sequencing contract; tool-execution dispatch ownership).

Fit confirmed (not misfits):
- Single-chokepoint enforcement is sound: both producers (interrupt path
  `turn.clj:223`, real-result path `tool_runtime_adapter.clj:114`) converge on
  the one pure handler `:session/tool-agent-record-result`
  (`session_mutations.clj:529`). Guarding there matches the `one_way` /
  single-source principle.
- First-writer-wins + suppression aligns with the impossible-invalid-states
  ethos (at-most-once invariant on tool-call-id).
- Optional defensive projection de-dup in `journal->provider-messages` /
  conversation rebuild is consistent with "projection derived purely from
  canonical state" and `robust(code)`; architecturally fine, not a misfit.

Actionable architectural misfits (see design-steps.md):
1. Recommended Option (B) anchors the guard to `:pending-tool-calls` — an
   agent-core runtime-handle data atom, explicitly *not* canonical `:state*`
   (doc/architecture.md "State boundary"). The architecture's stated direction
   is to project observable status worth querying into canonical `:state*`
   through dispatch and keep handles external. Leaning (B) "for minimal
   mechanism" under-weights that direction; the architectural call (B vs C)
   must be made explicitly and, if (B), documented as a deliberate deviation
   per the shims/adapters guidance (deviation requires documented design
   decision).
2. Option (B)'s "agent-core atomically decides applied?; journal append happens
   only when applied" fights the Dispatch sequencing contract
   (handler computes pure result → apply writes state → effects execute last).
   A stateful test-and-set against `:pending-tool-calls` at effect-decision time
   is a state mutation that gates effect emission, i.e. not a pure-result then
   effects-as-data shape. Prefer a pure guard in the dispatch handler reading a
   canonical predicate (recorded-result ids / pending set in `:state*`), emitting
   both effects or neither.
3. Cross-component layering: Option (B) co-locates the decision in agent-core
   (lower component) while the journal append is an agent-session (higher
   component) effect. Making a higher-layer effect conditional on a lower-layer
   atomic decision couples the layers. The agent-session pure handler should own
   the applied?/effects decision from canonical state; agent-core stays the data
   the handler reads.

## Architecture-fit follow-up resolution (design-steps)

Executed the three architecture-fit follow-up items. All three converge on one
decision; resolved together in design.md ("Design Decisions → D1").

- **Decision: Option (C), Option (B) rejected.** Guard via a canonical
  recorded-tool-result-ids predicate projected into `:state*` through dispatch,
  read purely by `:session/tool-agent-record-result`. Grounded in
  doc/architecture.md: State boundary (project queryable status into `:state*`;
  handles stay external), Dispatch sequencing contract (pure result → apply →
  effects last), and cross-component layering.
- **Code grounding confirmed:** `:pending-tool-calls` is an agent-core data atom
  mutated via `swap-data!` (`agent_core/core.clj:424`), i.e. a runtime handle —
  not canonical `:state*`. Producers: interrupt path `turn.clj:223`, real-result
  path `tool_runtime_adapter.clj:114`, both dispatching the same event into the
  unconditional handler `session_mutations.clj:529`.
- **Mechanism (pure both-or-neither):** handler reads canonical recorded-ids; if
  id present → no `:root-state-update`, emit neither effect; else →
  `:root-state-update` adds id, emit both effects. Atomicity from dispatch
  serialization (single writer to `:state*`), not a runtime test-and-set.
- `:pending-tool-calls` retained for interrupt enumeration only; no longer gates
  effects. Scope section in design.md updated to reference the canonical
  predicate instead of `:pending-tool-calls`.
- Plan-time detail flagged in D1: confirm the recorded-ids reset point (mirror
  `:pending-tool-calls` turn/session reset) so the set stays bounded.
- No blockers; all three items completed.

## Ambiguity review (design.md)

Reviewed design.md for ambiguities (statements admitting >1 interpretation),
not architecture-fit or correctness. Grounded against code: both producers
dispatch the same `:session/tool-agent-record-result` — interrupt path
`turn.clj` `record-pending-tool-call-interrupts!` and real-result path
`tool_runtime_adapter.clj` `record-tool-call-result!`'s `:record-result!`.

New actionable ambiguities (see design-steps.md):
1. **Defensive projection de-dup scope is inconsistent across three sections.**
   Desired Behaviour states it as a hard requirement ("The provider-facing
   projection … **must** tolerate already-persisted duplicates by emitting at
   most one `tool_result` per id"), Scope lists it in neither in- nor out-of-
   scope, and Open Question 2 says whether it is in scope "is an open question."
   A reader cannot tell if it is required, deferred, or out of scope.
2. **Late real result: suppressed-at-handler vs rerouted-to-background are two
   different mechanisms, both asserted.** Desired Behaviour: the real completion
   "is still delivered through the existing async/background completion path …
   **not as a second `tool_result`**", implying it never reaches the record
   handler. Root Cause step 3 + D1 Mechanism: the real result **still dispatches
   `:session/tool-agent-record-result`** and is suppressed by the recorded-ids
   guard. Code confirms the latter (adapter `:record-result!` dispatches the
   event). Design does not say which mechanism prevents the second
   `tool_result`, so the role of the recorded-ids guard vs the background path
   is ambiguous.
3. **recorded-ids reset boundary vs cross-turn late result is under-specified
   and outcome-determining.** D1 says recorded-ids is "cleared/reset on the same
   lifecycle boundaries that already reset `:pending-tool-calls` (turn/session
   reset)" yet defers the "exact reset point" to plan-time. But an aborted
   tool's real result arrives **after** the turn that recorded the interrupt; if
   recorded-ids resets at the turn boundary, the late real result finds its id
   absent and is **not** suppressed — re-introducing the duplicate the task
   exists to remove. The reset semantics are not a mere plan detail; they decide
   whether the invariant holds for the headline race. Design must state the
   required persistence (the id must outlive the turn until the late result is
   resolved) or the assumption that the late result never re-dispatches the
   record event (ties to item 2).
4. **"First-writer-wins" (nondeterministic) vs "aborted async tool keeps its
   'interrupted' result" (deterministic) are not reconciled.** D1 grounds
   correctness in dispatch ordering ("dispatch ordering decides the first
   writer"), which does not guarantee the interrupt result is recorded before
   the real one. Yet Desired Behaviour asserts the aborted tool "keeps its
   `interrupted` result", a deterministic outcome. Open Question 3 frames this
   as "confirm intended behaviour" but not as "is interrupt-wins guaranteed by
   ordering?" Specify whether interrupt-first is guaranteed or whether the
   model-visible result is genuinely whichever races first.
5. **Suppressed real result for synchronous tools is unspecified.** Desired
   Behaviour describes the suppressed real completion being surfaced via the
   "async/background completion path (chat-injection / background-job
   terminal)". The Root Cause evidence includes synchronous tools (`bash`,
   `psi-tool`) which may have no background-completion path. The intended fate
   of a suppressed real result for a synchronous tool (silently dropped? is that
   acceptable model-visible behaviour?) is not stated.
6. **Open Question numbering starts at 2 (no item 1).** The resolved
   guard-location question (now D1) left a numbering gap, so "Remaining Open
   Question" is ambiguous about whether an item 1 still exists. Minor: renumber.
