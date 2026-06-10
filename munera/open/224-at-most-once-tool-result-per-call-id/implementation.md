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

## Architecture-fit review (design.md) — second pass (post-D1)

Re-reviewed the design after D1 adopted Option (C). Grounded against
doc/architecture.md (State boundary: canonical root vs runtime handles;
Dispatch sequencing contract; tool-execution dispatch-owned slice
`:session/tool-run` → `:session/tool-record-result`) and AGENTS.md
(single-source-of-truth atom, one_way, effects-as-data). Verified code topology.

Fit confirmed; **no new actionable architectural misfit**:
- **Chokepoint is the true convergence event.** `:session/tool-record-result`
  (the architecture-described recording slice) funnels into
  `:session/tool-agent-record-result` via `record-tool-call-result!`'s
  `:record-result!` (`tool_runtime_adapter.clj:114`), where the journal append +
  in-memory record actually happen (`session_mutations.clj:529`). Both interrupt
  producers (`turn.clj`, `dispatch_effects.clj`) also converge there. Guarding
  `:session/tool-agent-record-result` (not `:session/tool-record-result`) is
  correct — the interrupt producers bypass `tool-record-result`, so the inner
  event is the only point all three producers share. Matches one_way /
  single-source.
- **recorded-ids set in `:state*` is a legitimate projection, not redundant
  canonical state.** The in-memory history and `:pending-tool-calls` live on the
  agent-core data atom (external handle, `swap-data!` `core.clj:424`); the
  journal is disk-persisted via effect. Neither is queryable `:state*`. So the
  at-most-once predicate cannot be derived from `:state*` and must be projected
  in — exactly the State-boundary pattern (project status, keep handle external).
  No single-source-of-truth violation.
- **Pure both-or-neither + dispatch-serialized atomicity** conforms to the
  Dispatch sequencing contract (pure result → apply → effects last); no runtime
  test-and-set. Session-lifetime persistence (decoupled from per-turn
  `:pending-tool-calls`) correctly matches the cross-turn race. Bounded set.
- **Defensive projection de-dup placement is correct.**
  `journal->provider-messages` (`prompt_request.clj`) and the conversation
  rebuild (`turn_runtime/conversation.clj`) are provider-facing projections in
  agent-session/turn-runtime — distinct from the app-runtime presentation
  transcript projections in the adapter-convergence roadmap. Purely derived,
  keyed by tool-call-id; consistent with robust(code).

No new follow-up items; the three prior architecture-fit items remain resolved
by D1.

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

## Ambiguity follow-up resolution (design-steps)

Executed all six ambiguity-review follow-up items; all updates landed in
design.md. Grounded against code: real-result re-dispatch confirmed in
`tool_runtime_adapter.clj` `record-tool-call-result!` (`:record-result!` →
`dispatch! :session/tool-agent-record-result`); interrupt path confirmed in
`turn.clj` `record-pending-tool-call-interrupts!` enumerating *still-pending*
`:pending-tool-calls` and recording synthetic results synchronously at abort;
handler `session_mutations.clj:529` currently emits both effects unconditionally.

- **(1) De-dup scope → in scope.** Reconciled Desired Behaviour, Scope (new
  in-scope bullet), and the open question; added acceptance criterion. Rationale:
  forward-fix alone leaves already-wedged journals broken; defensive projection
  de-dup is cheap and purely derived.
- **(2) Late-result mechanism → guard suppresses the re-dispatch.** Real result
  still dispatches the record event; recorded-ids guard suppresses its in-memory
  record + journal append. Background/async path is orthogonal content delivery
  (later turn), not the suppressor.
- **(3) Persistence boundary → session lifetime.** recorded-ids must persist
  across turns (cleared only on session reset/clear), decoupled from
  `:pending-tool-calls` per-turn reset; otherwise the cross-turn late result is
  not suppressed. Replaced the prior plan-time-defer text in D1 Mechanism.
- **(4) Determinism reconciled.** first-writer-wins is the general mechanism;
  interrupt-first is deterministic for still-pending tools because the interrupt
  is recorded synchronously at abort while a still-in-flight real result arrives
  after. Folded Open Question 3 in.
- **(5) Sync-tool fate → silently dropped on abort.** No background path for
  `bash`/`psi-tool`; suppressed real result is dropped, intended since the user
  aborted.
- **(6) Renumbered.** "Remaining Open Question" → "Resolved Questions" (1–3, no
  gap); none remain open.

No blockers; all six items completed.

## Inconsistency review (design.md)

Reviewed design.md for internal inconsistencies and design-vs-code
inconsistencies (not ambiguity/architecture-fit). Verified cited line numbers
against code: `core.clj:424` (swap-data! :pending-tool-calls) ✓,
`turn.clj:220/223` (record-pending-tool-call-interrupts! enumerate+dispatch) ✓,
`tool_runtime_adapter.clj:114` (:record-result! re-dispatch) ✓,
`session_mutations.clj:528` (handler) ✓.

New actionable inconsistency (see design-steps.md):
1. **The interrupt-result producer is cited as two different code locations,
   and there are in fact two distinct producers.** Root Cause step 2 + Evidence
   attribute the interrupt to `:on-agent-done`
   (`statechart_actions.clj:129/149`) emitting the
   `:runtime/record-pending-tool-call-interrupts` **effect**, whose handler
   (`dispatch_effects.clj:127`) enumerates `:pending-tool-calls` and dispatches
   `:session/tool-agent-record-result`. Desired Behaviour + D1 Mechanism instead
   attribute the interrupt to `turn.clj:223 record-pending-tool-call-interrupts!`
   (called by `abort-in!` `turn.clj:233`). The code contains **both** producers,
   each enumerating `:pending-tool-calls` and dispatching the same event with an
   `"interrupted"` toolResult. design.md treats them as a single "interrupt
   path" and never reconciles them. This matters twice: (a) D1's determinism
   argument ("records `interrupted` results **synchronously at abort time**,
   `turn.clj`") describes only the `abort-in!` path, yet the reproduced
   `:user-abort` Evidence can flow through the `:on-agent-done` **effect** path
   (effect executed during dispatch, not the literal synchronous `abort-in!`
   call); (b) D1's "`:pending-tool-calls` retained for enumeration only
   (`turn.clj:220`)" omits the second enumeration site (`dispatch_effects.clj`).
   The single-chokepoint fix (guard at `:session/tool-agent-record-result`)
   still covers both, but the design must acknowledge both producers and ground
   the determinism reasoning in the actual reproduced path.

No blockers; one actionable inconsistency.

## Inconsistency follow-up resolution (design-steps)

Executed the single inconsistency-review follow-up item; all updates landed in
design.md. Verified both producers in code before editing:
- `turn.clj:217` `record-pending-tool-call-interrupts!` (enumerates
  `:pending-tool-calls` at `turn.clj:220`, dispatches the record event), called
  synchronously by `abort-in!` (`turn.clj:233`).
- `statechart_actions.clj:129/149` `:on-agent-done` emits
  `:runtime/record-pending-tool-call-interrupts`, whose handler
  (`dispatch_effects.clj:127`) enumerates `:pending-tool-calls`
  (`dispatch_effects.clj:131`) and dispatches the same record event.

Resolution applied:
- **Root Cause step 2** rewritten to state explicitly there are **two distinct
  interrupt producers** (statechart-effect path + synchronous abort path), both
  enumerating `:pending-tool-calls` and converging on the single event
  `:session/tool-agent-record-result`; notes the reproduced `:user-abort` can
  flow through the statechart-effect path, not only the literal synchronous
  `abort-in!` call.
- **Desired Behaviour determinism bullet** + **D1 Mechanism determinism bullet**
  no longer say "synchronously at abort time, `turn.clj`"; they now ground in
  *either* producer (effect-execution or synchronous) recording the interrupt
  for still-pending ids before the real-result re-dispatch.
- **D1 Mechanism atomicity bullet** now names all three producers (two interrupt
  + real-result) and states the single chokepoint covers all three.
- **D1 `:pending-tool-calls` retention bullet** now notes **both** enumeration
  sites (`turn.clj:220` and `dispatch_effects.clj:131`).
- Generalized "whichever of the two events" → record-event dispatch wording to
  cover >2 producers per id.

No blockers; item completed.

## Ambiguity review (design.md) — second pass

Re-reviewed design.md for ambiguities (statements admitting >1 interpretation),
not architecture-fit/correctness. Prior six ambiguity items + the inconsistency
item are all resolved and verified present in design.md. Grounded the projection
claims against code:
- `journal->provider-messages` (`prompt_request.clj:111`) projects the
  **persisted journal**; repair via `repair-dangling-tool-uses`.
- `agent-messages->ai-conversation` (`turn_runtime/conversation.clj:136`)
  rebuilds the conversation **from agent-core in-memory message history**
  (docstring), turning each `"toolResult"` message into one `add-tool-result`
  block keyed by `:tool-call-id` (`conversation.clj:93`). Its input is *not* the
  journal.

New actionable ambiguity (see design-steps.md):
1. **Defensive de-dup location across the two named projection sites is
   ambiguous, and its keying source is self-contradictory for one site.** Scope
   and the final Desired-Behaviour bullet require "at most one `tool_result` per
   tool-call-id" in "the provider-facing projection (`journal->provider-messages`
   **and** the conversation rebuild)" and qualify it "**Purely derived from the
   journal** … first occurrence wins". Two interpretations are left open:
   (a) the guard must be implemented independently at **both** sites, vs (b) a
   single shared downstream chokepoint suffices (the design's Root Cause implies
   `journal->provider-messages` emits the duplicate `tool_result` blocks, but the
   code shows the conversation rebuild is what emits provider `tool_result`
   blocks). Additionally, "**purely derived from the journal**" only fits
   `journal->provider-messages`; the conversation rebuild derives from in-memory
   agent-core history, not the journal, so the de-dup's keying source (journal vs
   history) for that second site is unspecified/contradicted. A reader cannot
   tell where the guard(s) live or what each keys off. Distinct from resolved
   ambiguity item 1 (which fixed only the in/out-of-scope question, not the
   location/keying).

No blockers; one actionable ambiguity.
