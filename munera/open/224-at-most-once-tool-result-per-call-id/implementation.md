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

## Ambiguity review (fifth pass) — follow-up executed

Resolved the :118 alignment item. Qualified the Desired-Behaviour bullet to read
"An aborted, **genuinely still in-flight** tool — one whose real result has **not
yet been produced or dispatched** at abort time — keeps its `"interrupted"`
result," matching the narrow phrasing the fourth pass installed at the
determinism bullet (:130+), D1 Mechanism (:314+), and Resolved Question 3 (:376+).
Added an inline qualifier that this is the typical headline case and that in the
concurrent-completion window the real result may win (still exactly one result),
with a cross-reference to the determinism bullet, D1 Mechanism, and Resolved
Question 3. This removes reading (a) (the over-claim that any id pending at abort
deterministically keeps its interrupt result), which was literally false in the
concurrent window. All four sites now use the same "genuinely still in-flight"
framing. No blockers.

## Inconsistency review (design.md) — second pass

Re-reviewed design.md for internal inconsistencies and design-vs-code
inconsistencies. Re-verified all cited line numbers against current code:
`core.clj:424` (swap-data! :pending-tool-calls) ✓, `statechart_actions.clj:129`
(:on-agent-done) / `:149` (record-pending effect emit) ✓, `dispatch_effects.clj:127`
(effect handler) / `:131` (enumerate :pending-tool-calls) ✓, `turn.clj:217`
(record-pending-tool-call-interrupts!) / `:220` (enumerate) / `:233` (abort-in! call) ✓,
`tool_runtime_adapter.clj:114` (:record-result! re-dispatch) ✓,
`session_mutations.clj:529` (handler: agent-record-tool-result + append-message-effect) ✓,
`prompt_request.clj:111` (journal->provider-messages) / `:296` (:turn/messages) ✓,
`conversation.clj:95` (add-tool-result) / `:136` (agent-messages->ai-conversation) ✓,
`request.clj:54/60` (build-provider-conversation reads :turn/messages) ✓.

New actionable inconsistency (see design-steps.md):
1. **Root Cause "Result:" line attributes provider `tool_result` *block*
   emission to `journal->provider-messages`, contradicting both Root Cause
   step 4 and the De-dup Location bullet.** The Root Cause closing line reads
   "two journal `toolResult` entries with one tool-call-id →
   `journal->provider-messages` → two `tool_result` blocks for one `tool_use` →
   provider 400", presenting `journal->provider-messages` as the emitter of the
   two `tool_result` blocks. But (a) Root Cause step 4 (same section) states the
   **conversation rebuild** (`turn-runtime/conversation.clj`) "emits one
   `tool_result` block per `toolResult` message", and (b) the Desired-Behaviour
   De-dup Location bullet states explicitly that `journal->provider-messages`
   "emits `toolResult`-role provider *message maps*" and "The rebuild is the only
   place provider `tool_result` blocks are emitted." Code confirms (b):
   `conversation.clj:95` `conv/add-tool-result` is the only `tool_result`-block
   emitter; `journal->provider-messages` (`prompt_request.clj:111`) emits message
   maps. The "Result:" arrow elides the rebuild step and mis-attributes block
   emission to the journal projection, now actively contradicting the precise
   pipeline model the second-pass ambiguity resolution added. (The second-pass
   ambiguity item *noticed* this Root Cause implication as supporting evidence
   but its resolution updated only the de-dup bullets/Scope/AC/Resolved-Q2 — it
   did not correct the Root Cause "Result:" line, so the contradiction is now
   present in design.md.) Fix: rewrite the "Result:" arrow to route through the
   rebuild, e.g. "two journal `toolResult` entries → `journal->provider-messages`
   (two duplicate message maps) → conversation rebuild → two `tool_result` blocks
   for one `tool_use` → provider 400", consistent with step 4 and the De-dup
   Location bullet.

No blockers; one actionable inconsistency.

## Ambiguity follow-up resolution (design-steps) — second pass

Executed the single second-pass ambiguity item (de-dup projection
location/keying). Verified the provider-request pipeline in code before editing:

- `journal->provider-messages` (`prompt_request.clj:111`) projects the journal
  into `toolResult`-role provider *message maps*; `session->provider-messages`
  wraps it (`prompt_request.clj:131/280`).
- `agent-messages->ai-conversation` (`turn_runtime/conversation.clj:136`) is the
  **only** production emitter of provider `tool_result` blocks — one per
  `toolResult` message via `conv/add-tool-result` (`conversation.clj:95`).
- Its only production caller is `build-provider-conversation`
  (`request.clj:54/60`), which reads `:turn/messages` — set from
  `prepared-turn-messages` → `session->provider-messages`
  (`prompt_request.clj:296`). So in the provider path the rebuild's input is the
  **journal-derived** message list, not separate in-memory agent-core history.
  (The rebuild's docstring "from agent-core message history" is the generic
  contract; here that history is the journal-derived `:turn/messages`.)

Resolution applied to design.md:
- **Location → single upstream chokepoint at `journal->provider-messages`**
  (interpretation b). One-to-one `toolResult` message → block mapping means
  de-duping messages once upstream removes the duplicate before it can become a
  second block; no independent guard at the conversation rebuild.
- **Keying → the journal, by `tool-call-id`, first occurrence wins.** "Purely
  derived from the journal" is now accurate (guard at the journal projection);
  the rebuild is not a second de-dup site and has no separate keying source.
- Edited: Desired Behaviour (rewrote final journal-tolerance bullet, added two
  location/keying bullets), Scope in-scope de-dup bullet, Acceptance criterion,
  Resolved Question 2.

No blockers; item completed.

## Inconsistency review (second pass) follow-up — Root Cause "Result:" arrow

Item: the Root Cause closing "Result:" line attributed provider `tool_result`
*block* emission to `journal->provider-messages`, contradicting (a) Root Cause
step 4 and (b) the Desired-Behaviour De-dup Location bullet, which state the
conversation rebuild emits the blocks.

Code verified before editing:
- `journal->provider-messages` (`prompt_request.clj:111`) projects journal
  entries into provider *message maps* (`:message` entries → message maps;
  `toolResult`-role maps built at `prompt_request.clj:36`). It does **not** emit
  provider `tool_result` blocks.
- The conversation rebuild `agent-messages->ai-conversation`
  (`conversation.clj`) dispatches `toolResult`-role messages to
  `append-tool-result-msg`, which calls `conv/add-tool-result`
  (`conversation.clj:95`) — exactly one provider `tool_result` block per
  `toolResult` message. This is the sole block emitter.

Resolution applied to design.md: rewrote the Root Cause "Result:" arrow to route
block emission through the rebuild ("two journal `toolResult` entries →
`journal->provider-messages` (two duplicate message maps) → conversation rebuild
(`agent-messages->ai-conversation`, one block per message via `conv/add-tool-result`
`conversation.clj:95`) → two `tool_result` blocks → provider 400") and added an
explicit note that the block-emitting projection is the rebuild, not
`journal->provider-messages`. Root Cause now agrees with step 4 and the De-dup
Location bullet.

No blockers; item completed.

## Architecture-fit review (design.md) — third pass

Fresh architecture-fit pass on the current (post-D1, Option-C) design. Grounded
against doc/architecture.md (State boundary: canonical root vs runtime handles;
Dispatch sequencing contract; tool-execution dispatch-owned slice
`:session/tool-run` → `:session/tool-execute-prepared` + `:session/tool-record-result`),
META.md, and AGENTS.md (single-source-of-truth atom, dispatch-owns-writes,
effects-as-data, one_way, ¬shims/adapters). No new code re-verification needed
beyond prior passes.

Fit confirmed; **no new actionable architectural misfit**:
- recorded-tool-result-ids in `:state*`, handle (`:pending-tool-calls`,
  agent-core `swap-data!` atom) external → matches State-boundary "project
  queryable status into canonical state, keep handle external".
- pure guarded handler (read predicate → `:root-state-update` → both-or-neither
  effects), dispatch-serialized atomicity, no runtime test-and-set → conforms to
  Dispatch sequencing contract.
- guard at `:session/tool-agent-record-result` (inner event) is the only point
  all three producers (two interrupt + real-result) converge — interrupt
  producers bypass the dispatch-owned `:session/tool-record-result` slice; the
  interrupt-bypass is pre-existing topology and routing them through
  `:session/tool-record-result` is explicitly out of scope, so no actionable
  misfit here. one_way / single-source preserved.
- defensive de-dup at `journal->provider-messages` is a provider-request
  projection in agent-session/turn-runtime, distinct from app-runtime UI
  transcript projections; purely-derived, tool-call-id keyed → robust(code).
- Option B (would have needed a documented deviation per ¬shims/adapters) was
  rejected for the architecturally-aligned Option C.

No new follow-up items; prior architecture-fit items remain resolved by D1.

## Ambiguity review (design.md) — third pass

Re-reviewed design.md for ambiguities (statements admitting >1 interpretation),
not architecture-fit/correctness. Prior ambiguity + inconsistency items remain
resolved. Audited every dispatcher of `:session/tool-agent-record-result` in
code to ground the producer-enumeration claims.

New actionable ambiguity (see design-steps.md):
1. **The at-most-once guarantee's reliance on an *exhaustive* producer
   enumeration vs. the *general funnel* property is ambiguous — and the
   enumeration is in fact incomplete.** Root Cause step 2 asserts "There are
   **two distinct interrupt producers** in the code", and D1's atomicity bullet
   says "The racing producers all dispatch the same event …: the **two interrupt
   producers** … and the **real-result path** … The single chokepoint covers
   all three producers." This reads as an exhaustive count (two interrupt + one
   real = three). But the same bullets also state a general property ("any later
   dispatch of the same id reads it already present and is suppressed"), under
   which the enumeration is illustrative, not load-bearing. A reader cannot tell
   whether the invariant's soundness *depends on* the enumeration being complete
   (in which case it must be correct) or only on the funnel (all producers
   dispatch the one event, enumeration irrelevant). This matters because the
   enumeration is **incomplete**: code has **three** interrupt producers, not
   two — a third enumerates `:pending-tool-calls` and dispatches
   `:session/tool-agent-record-result` with an `"interrupted"` toolResult at
   session close:
   - statechart-effect path `dispatch_effects.clj:127/134` (`:on-agent-done`)
   - synchronous abort path `turn.clj:217/223` (`abort-in!`)
   - **session-close path `session_close.clj:55/61`
     `repair-pending-tool-calls-before-close!`** (called by `close-session-in!`)
   So four dispatch sites converge on the handler `session_mutations.clj:529`,
   not three. The single-chokepoint fix still covers the omitted producer (it
   dispatches the same event), but the design's "two interrupt producers" /
   "covers all three producers" wording is factually incomplete, and the
   close path can itself record a duplicate for one pending id (abort-in!'s
   interrupt at `turn.clj:223` plus `repair-pending-tool-calls-before-close!`
   both enumerate the same `:pending-tool-calls` during close), which the design
   never acknowledges. Resolve by either (a) stating the guarantee rests on the
   funnel property (every producer dispatches the one event; enumeration is
   illustrative, not exhaustive) and dropping the exhaustive-count language, or
   (b) completing the enumeration to include the session-close producer.

No blockers; one actionable ambiguity.

## Ambiguity follow-up resolution (design-steps) — third pass

Executed the single third-pass ambiguity item (exhaustive enumeration vs funnel
property; enumeration incomplete — session-close producer omitted).

Code verified before editing:
- Third interrupt producer confirmed: `repair-pending-tool-calls-before-close!`
  (`session_close.clj:55`) enumerates `:pending-tool-calls` (`:58`) and
  dispatches `:session/tool-agent-record-result` (`:61`) with an `"interrupted"`
  toolResult; called by `close-session-in!` (`:106`).
- Statechart-effect producer (`dispatch_effects.clj:127` enumerate `:131`),
  synchronous abort producer (`turn.clj:217/220/233`), real-result path
  (`tool_runtime_adapter.clj:114`) re-verified. All four dispatch sites converge
  on handler `session_mutations.clj:529`.
- **Review's close-path-duplicate mechanism is inaccurate.** In
  `close-session-in!`, `abort-session-runtime!` (`:105`) calls agent-core
  `agent/abort-in!` (`agent_core/core.clj:466`), which **clears**
  `:pending-tool-calls` to `#{}` (it does *not* record interrupt results) — it
  is *not* `turn.clj`'s `abort-in!` (`turn.clj:229`, which does record). So the
  review's "abort-in! `turn.clj:223` + repair both enumerate the same pending set
  during close" duplicate is not the actual close path. This argues for the
  funnel framing: the precise per-producer interaction is non-load-bearing.

Resolution applied (chose **(a) funnel property load-bearing**, plus completed
the enumeration for accuracy):
- **Root Cause step 2** rewritten: "several distinct interrupt producers", count
  "not load-bearing", list illustrative; added the session-close producer as a
  third bullet; added an explicit **"Funnel property (load-bearing)"** paragraph
  stating soundness rests on every producer dispatching the one event (chokepoint
  covers any producer regardless of count), and that duplicates from an omitted
  or re-enumerating producer (incl. session-close) are suppressed by the guard.
- **D1 atomicity bullet**: dropped "two interrupt producers"/"covers all three
  producers" exhaustive-count language; now leads with the funnel property and
  lists known producers (three interrupt incl. session-close + real-result)
  illustratively, noting the invariant does not depend on completeness.
- **D1 `:pending-tool-calls` retention bullet**: now cites all three enumeration
  sites (`turn.clj:220`, `dispatch_effects.clj:131`, `session_close.clj:58`).

No blockers; item completed.

## Inconsistency review (design.md) — third pass

Re-reviewed design.md for design-vs-code inconsistencies. Re-verified producer
line numbers (`statechart_actions.clj:129/149`, `dispatch_effects.clj:127/131`,
`turn.clj:217/220/233`, `session_close.clj:55/58/60/106`,
`tool_runtime_adapter.clj:114`, `session_mutations.clj:529`) — all accurate.
Audited how `:user-abort` vs `:deferred-interrupt` reach each interrupt producer.

New actionable inconsistency (see design-steps.md):
1. **The design claims the reproduced `:user-abort` evidence can flow through the
   statechart-effect interrupt producer; code shows it cannot.** `:on-agent-done`
   (`statechart_actions.clj:132`) reads session-data `:interrupt-reason`. The
   **only** writer of `:interrupt-reason` is the `:session/request-interrupt`
   handler (`session_mutations.clj:638`, `(or reason :deferred-interrupt)`),
   whose **only** dispatcher passes `:reason :deferred-interrupt`
   (`turn.clj:189/193`). `:user-abort` appears **only** at `turn.clj:233` as the
   interrupt *message* reason in the synchronous `abort-in!` inline path and is
   **never** written to `:interrupt-reason` (verified: no code assigns
   `:interrupt-reason :user-abort`; the schema enum `:session-close` /
   `:context-shutdown` values are likewise unwired). So the statechart-effect
   producer (`:on-agent-done` → `:runtime/record-pending-tool-call-interrupts`,
   `dispatch_effects.clj:127`) fires only for `:deferred-interrupt`, never for
   `:user-abort`. design.md contradicts this in three places: (a) Root Cause
   step 2 statechart-effect bullet "sees `:interrupt-reason` (e.g. `:user-abort`)"
   — wrong example, should be `:deferred-interrupt`; (b) the Funnel-property
   paragraph "The reproduced `:user-abort` Evidence below can flow through the
   statechart-effect producer ... not only the literal synchronous `abort-in!`
   call" — false; the `:user-abort` evidence flows **exclusively** through the
   synchronous path; (c) the Desired-Behaviour and D1 Mechanism determinism
   bullets frame the headline `:user-abort` race as recordable by "either
   interrupt producer (statechart-effect ... or synchronous `abort-in!`)" — for
   the `:user-abort` race the first writer is deterministically the synchronous
   inline recording in `abort-in!`; the statechart-effect path applies to the
   distinct `:deferred-interrupt` race. The funnel property (general invariant,
   any producer dispatches the one event) is unaffected, but the evidence and
   determinism attribution must distinguish `:user-abort` ⇒ synchronous path
   from `:deferred-interrupt` ⇒ statechart-effect path. (Introduced by the
   first-pass inconsistency resolution, which asserted the reproduced
   `:user-abort` "can run through the `:on-agent-done` effect"; that assertion is
   incorrect.)

No blockers; one actionable inconsistency.

## Inconsistency follow-up resolution (design-steps) — third pass

Executed the single third-pass inconsistency item (`:user-abort` cannot flow
through the statechart-effect producer).

Code verified before editing:
- `:interrupt-reason` writers: `:session/request-interrupt` handler
  (`session_mutations.clj:638`, `(or reason :deferred-interrupt)`) and
  `:on-agent-done`/`:on-abort` resets to nil (`statechart_actions.clj:144/160`).
  Default nil (`session_state/model.clj:273`). No writer assigns `:user-abort`.
- `:session/request-interrupt`'s only dispatcher is `turn.clj:189`, passing
  `:reason :deferred-interrupt` (`turn.clj:193`).
- `:user-abort` appears only at `turn.clj:233` as the interrupt *message* reason
  passed to the synchronous `record-pending-tool-call-interrupts!` (`turn.clj:217`)
  in `abort-in!`.
- `:on-agent-done` (`statechart_actions.clj:132`) reads `:interrupt-reason` and
  emits `:runtime/record-pending-tool-call-interrupts` (`:149`) only when it is
  non-nil — i.e. only for `:deferred-interrupt`. Effect handler at
  `dispatch_effects.clj:127`.

Resolution applied to design.md (three contradicting sites):
- **(a) Root Cause step 2 statechart bullet** — changed "sees `:interrupt-reason`
  (e.g. `:user-abort`)" to "sees a non-nil `:interrupt-reason` (only ever
  `:deferred-interrupt`)"; fixed the read line cite to `:132`.
- **(b) Funnel-property section** — removed the false "reproduced `:user-abort`
  Evidence can flow through the statechart-effect producer" claim; added a
  "Which producer fires for which reason" paragraph: `:user-abort` ⇒ synchronous
  `abort-in!` path exclusively, `:deferred-interrupt` ⇒ statechart-effect path;
  funnel property explicitly noted unaffected.
- **(c) Desired-Behaviour + D1 Mechanism determinism bullets** — stopped
  attributing the headline `:user-abort` race to "either producer"; the first
  writer is deterministically the synchronous inline recording in `abort-in!`;
  the statechart-effect path applies to the distinct `:deferred-interrupt` race.

Funnel property (general invariant — any producer dispatches the one event) left
intact. No blockers; item completed.

## Architecture-fit review (design.md) — fourth pass

Independent architecture-fit pass on the current post-D1 (Option-C) design.
Grounded against doc/architecture.md (State boundary: canonical root vs runtime
handles; Dispatch sequencing contract — pure result → apply → effects last;
pure-result kinds incl. `:root-state-update`), META.md, and AGENTS.md
(single-source-of-truth atom, dispatch-owns-writes, effects-as-data, one_way,
¬shims/adapters).

Fit confirmed; **no new actionable architectural misfit**:
- recorded-tool-result-ids in `:state*` with `:pending-tool-calls` (agent-core
  `swap-data!` atom) kept external → matches State-boundary "project queryable
  status into canonical state, keep handle external".
- pure guarded handler returning `:root-state-update` (a recognized pure-result
  kind) with both-or-neither effect emission, dispatch-serialized atomicity, no
  test-and-set → conforms to the Dispatch sequencing contract.
- single chokepoint at `:session/tool-agent-record-result` (funnel property) →
  one_way / single-source; interrupt producers bypassing the dispatch-owned
  `:session/tool-record-result` slice is pre-existing topology, rerouting is
  out of scope → not an actionable misfit.
- defensive de-dup at `journal->provider-messages` is a purely-derived
  provider-request projection keyed by tool-call-id; complementary recovery of
  already-persisted duplicates, not a redundant second source of truth → robust(code).
- session-lifetime recorded-ids set is bounded by per-session tool-call count and
  the session-scoped journal it guards → no unbounded-growth concern beyond the
  journal itself.

No new follow-up items; prior architecture-fit items remain resolved by D1.

## Ambiguity review (design.md) — fourth pass

Re-reviewed design.md for ambiguities (statements admitting >1 interpretation),
not architecture-fit/correctness. Prior ambiguity + inconsistency items remain
resolved. Grounded the abort/real-result ordering against code:
`:pending-tool-calls` is *cleared* inside `record-tool-result-in!`
(`agent_core/core.clj:407`, `disj`), which runs only via the **effect**
`:runtime/agent-record-tool-result` (`dispatch_effects.clj:125`) — i.e. *after*
the `:session/tool-agent-record-result` handler returns and state is applied
(`session_mutations.clj:529`). Abort enumerates `:pending-tool-calls`
synchronously (`turn.clj:217/220/233`).

New actionable ambiguity (see design-steps.md):
1. **The determinism claim conflates "still pending at abort (enumeration)" with
   "the interrupt's record-event is dispatched before the real result's" — and
   in a real window they diverge, so interrupt-first is not guaranteed for every
   tool that was pending at abort.** Desired Behaviour ("Interrupt-first is
   guaranteed … not left to dispatch tie-breaking") and D1 Mechanism assert the
   interrupt is "deterministically the first writer for any tool that was still
   pending at abort", justified by "a real result for a still-in-flight tool
   necessarily arrives *after* the abort that enumerated it as pending." But
   "still pending" is an **apply-state** property: an id is removed from
   `:pending-tool-calls` only when the *effect* `:runtime/agent-record-tool-result`
   runs (`core.clj:407`), which is strictly later than the dispatch+apply of that
   real result's `:session/tool-agent-record-result`. So there is a window where
   a real result's record-event is already **enqueued** (and may be serialized
   *first*) while its id is **still in** `:pending-tool-calls` because the
   clearing effect has not yet executed. In that window abort enumerates the id
   as pending and dispatches an interrupt *after* the real-result record event;
   under dispatch serialization the real-result record applies first (adds the id
   to recorded-ids, keeps the **real** result) and the interrupt is suppressed —
   the opposite of the claimed "interrupt is the one kept." The design's
   parenthetical only excludes the case where the real result "completed and
   recorded *before* the abort enumerated it" using "no longer pending", but that
   exclusion is keyed to the *clearing effect having run*, not to *record-event
   enqueue order* — so the enqueued-but-not-yet-cleared window is unaddressed.
   "Arrives after" / "recorded before" / "still pending at abort" therefore admit
   two readings (dispatch-enqueue order vs effect-apply/pending-clear order) that
   yield opposite model-visible winners. This does **not** threaten the
   at-most-once invariant (still exactly one result either way) — only the
   asserted determinism of *which* result the model sees. Resolve by either
   (a) restating the headline-race guarantee as "at most one result, and
   first-writer-wins by dispatch order" and dropping the stronger
   "interrupt-first is deterministic for any tool pending at abort" claim
   (acknowledging the enqueued-real-result window can make the real result win),
   or (b) defining "still pending at abort" precisely in dispatch-enqueue terms
   and justifying why a real result's record-event cannot already be enqueued
   ahead of the abort's interrupt dispatch for an id abort still sees as pending.

No blockers; one actionable ambiguity.

## Ambiguity follow-up resolution (design-steps) — fourth pass

Executed the single fourth-pass ambiguity item (headline-race determinism claim
conflates enumeration-pending with record-event dispatch order).

Code verified before editing:
- `:pending-tool-calls` is cleared **only** inside `record-tool-result-in!`
  (`agent_core/core.clj:407`, `update :pending-tool-calls disj tool-call-id`),
  which runs solely via the **effect** `:runtime/agent-record-tool-result`
  (`dispatch_effects.clj:124`) — strictly *after* the
  `:session/tool-agent-record-result` handler returns and its `:root-state-update`
  (recorded-ids add) applies.
- `record-pending-tool-call-interrupts!` (`turn.clj:217`) reads
  `:pending-tool-calls` **synchronously** off the agent-core data atom
  (`turn.clj:220`, `agent/get-data-in … :pending-tool-calls`) and dispatches the
  record event per pending id; called by `abort-in!` (`turn.clj:233`).
- Confirms the reviewer's window: a real-result record-event can be serialized
  first (adding its id to recorded-ids) while `:pending-tool-calls` still lists
  the id (clearing effect not yet run), so abort enumerates it and dispatches an
  interrupt that the guard then suppresses — the **real** result wins. So
  "interrupt-first is deterministic for any tool pending at abort" over-claims.

Resolution applied (chose **(a)**): the deterministic guarantee is
**at-most-once** (exactly one result per id); the model-visible winner is
**first-writer-wins by dispatch order**. Dropped the unconditional
"interrupt-first" claim. Distinguished "still pending at abort" (apply-state,
cleared by a later effect) from record-event dispatch-enqueue order. Three sites
rewritten in design.md:
- **Desired-Behaviour determinism bullet** — replaced "Interrupt-first is
  guaranteed for the headline abort race" with "The deterministic guarantee is
  at-most-once; the model-visible winner is first-writer-wins by dispatch order",
  with three sub-bullets: typical headline case (genuinely in-flight tool → real
  result not yet produced → interrupt wins), concurrent-completion window (real
  result may be first writer and win — acceptable), and why this does not regress
  the task (one result regardless; interrupt path only enumerates still-pending
  ids).
- **D1 Mechanism determinism bullet** — same reframing, grounded in the
  effect-clears-pending vs handler-applies ordering, with "This is not
  unconditional interrupt-first."
- **Resolved Question 3** — restated as at-most-once + first-writer-wins, typical
  headline case interrupt-wins, concurrent window real-result-may-win.

At-most-once invariant explicitly unaffected (always exactly one result); only
the over-asserted determinism of *which* result was corrected. No blockers; item
completed.

## Inconsistency review (design.md) — fourth pass

Re-reviewed design.md for internal inconsistencies and design-vs-code
inconsistencies (not ambiguity/architecture-fit). Re-verified all load-bearing
code cites against current source:
- handler emits **both** effects unconditionally:
  `session_mutations.clj:529/531/533` (`:runtime/agent-record-tool-result` +
  `append-message-effect`) — matches Root Cause step 4 ✓
- `:runtime/agent-record-tool-result` effect (`dispatch_effects.clj:124/125`) →
  `agent/record-tool-result-in!` clears `:pending-tool-calls`
  (`core.clj:398/407` `disj`) — matches D1 apply-state/pending-clear ordering ✓
- real-result re-dispatch `tool_runtime_adapter.clj:114`; synchronous abort path
  `turn.clj:217/220/233` (`:user-abort`); statechart-effect producer
  `dispatch_effects.clj:127/131`; session-close producer `session_close.clj:55/58/61`,
  called by `close-session-in!` `:106` ✓
- `:user-abort` only at `turn.clj:233` (message reason); never written to
  `:interrupt-reason` — matches third-pass correction ✓

Examined the funnel paragraph's illustrative example "a later producer such as
session-close re-enumerates an id an earlier producer already recorded": code
shows recording (the winning dispatch) emits `:runtime/agent-record-tool-result`
which clears the id from `:pending-tool-calls`, and in `close-session-in!`
`abort-session-runtime!` (`:105` → agent-core `agent/abort-in!` `core.clj:466`,
clears pending to `#{}` when running) runs **before**
`repair-pending-tool-calls-before-close!` (`:106`). So re-enumeration of an
already-recorded id is essentially unrealizable. Judged **not a new actionable
inconsistency**: the design frames this as a hypothetical ("even if …")
robustness statement, the funnel property (general invariant) is sound
regardless, and the third-pass resolution already considered the
abort-clears-pending fact and deliberately chose the funnel framing to render the
precise per-producer interaction non-load-bearing. Re-flagging would duplicate
resolved work.

**No new actionable inconsistency.** Design is internally consistent and
consistent with referenced code on all checked points.

## Plan/steps ambiguity review (plan.md + steps.md) — first pass

Reviewed `plan.md` and `steps.md` (not `design.md`) for ambiguities — statements
admitting >1 implementer interpretation. Grounded against code:
`session_mutations.clj:529` handler returns **only** `{:effects [...]}` (no other
keys), so steps' "return `{}`" / "emit both effects" is complete (not actionable);
path helpers (`session-data-path`, `session-telemetry-path`) live in
`session_state/state.clj` (plan's primary Slice-A home is correct → the
parenthetical "or … most consistent home" is harmless); `journal->provider-messages`
(`prompt_request.clj:111`) wraps `repair-dangling-tool-uses` and toolResult
messages carry top-level `:tool-call-id` (`tool-result-id` `:31`), so the
de-dup keying field in Slice C is accurate; session slots are explicitly seeded
(`session_state/init.clj` `initialize-session-slots` `:78/87` + `model/initial-session`,
`initial-telemetry` `:11`).

New actionable ambiguities (see steps.md → "Plan/steps ambiguity review follow-ups"):

1. **Forward-fix characterization test assertion layer is unspecified, and the
   Slice-C de-dup can mask a forward-fix regression.** Steps Slice B writes the
   reproduction test to "rebuild the provider conversation and assert exactly one
   `tool_result` per `tool_use` id." Once Slice C adds the de-dup at
   `journal->provider-messages` (the production input to the rebuild,
   `request.clj:60` `:turn/messages`), an assertion on the *rebuilt provider
   conversation* would emit one `tool_result` **even if the forward fix
   regressed**, because the projection de-dups. Two interpretations: (a) assert
   on the raw recorded layer (exactly one `toolResult` entry in the journal +
   in-memory message history) — isolates the forward fix; (b) assert on the
   post-de-dup rebuild — does not isolate it. The forward-fix test must pin layer
   (a) so the two fixes are independently characterized; "fails on `main`" alone
   does not guarantee post-Slice-C isolation.

2. **Which interrupt producer/reason the Slice-B reproduction drives is
   unspecified.** Steps Slice B says only "interrupt the turn." design.md
   distinguishes three producers with different ordering semantics and identifies
   the reproduced Evidence as `:user-abort` via the **synchronous `abort-in!`
   path** (`turn.clj:233`; never the statechart-effect `:deferred-interrupt`
   path). The determinism framing (interrupt is the first writer only because it
   records synchronously) is path-specific, so the reproduction should specify
   driving the `:user-abort` synchronous abort path to match the Evidence;
   otherwise an implementer could exercise a different producer with different
   first-writer ordering.

3. **Slice-A "defaulting to `#{}`" conflates a path helper with the default
   source.** Steps Slice A asks for a path helper "defaulting to `#{}`," but a
   path helper returns a vector and cannot itself default. The codebase seeds
   per-session slots explicitly (`init.clj` `initialize-session-slots`,
   `model/initial-session`, `initial-telemetry`), so it is unspecified whether
   recorded-tool-result-ids is (a) seeded in the session model/init alongside
   telemetry, or (b) relied on nil-safe at the Slice-B read/update site
   (`get-state-value-in … #{}` + `(fnil conj #{})`). This is outcome-affecting:
   choice (a) means Slice A touches init/model (not just "add a path"), and it
   interacts with the Slice-B clearing-boundary decision (re-init vs explicit
   clear). Pin which mechanism supplies the default and at which site.

No blockers; three actionable plan/steps ambiguities.

## Architecture-fit review (design.md) — fifth pass

Independent architecture-fit pass on the current post-D1 (Option-C) design.
Grounded against doc/architecture.md (State boundary: canonical root vs runtime
handles; Dispatch sequencing contract: pure result → apply → effects last;
dispatch-owned tool-execution slice `:session/tool-run` → `:session/tool-execute-prepared`
→ `:session/tool-record-result`) and AGENTS.md (single-source-of-truth atom,
one_way, effects-as-data). Verified code topology directly.

Fit confirmed; **no new actionable architectural misfit**:
- **Chokepoint claim grounded in code.** `:session/tool-agent-record-result`
  handler (`session_mutations.clj:529`) is where *both* the in-memory record
  effect (`:runtime/agent-record-tool-result`) and the journal append
  (`append-message-effect`, `:533`) are emitted. The converged tool slice's
  `:session/tool-record-result` (`:565`) delegates via
  `record-tool-call-prepared-result!` → `:record-result!`
  (`tool_runtime_adapter.clj:114`) into that same inner event; the three
  interrupt producers (`turn.clj:223`, `dispatch_effects.clj:134`,
  `session_close.clj:61`) also dispatch it directly, bypassing
  `:session/tool-record-result`. So `:session/tool-agent-record-result` is the
  only event all producers share — guarding it (not the outer slice) is the
  correct single chokepoint. Matches one_way / single-source.
- **recorded-ids in `:state*` is the State-boundary pattern.** `:pending-tool-calls`
  is an agent-core runtime-handle atom (not queryable `:state*`); the
  at-most-once status is projected into canonical `:state*` through dispatch and
  the handle stays external — exactly the architecture's stated direction.
- **Pure both-or-neither + dispatch-serialized atomicity** conforms to the
  Dispatch sequencing contract (pure result → apply → effects last); no runtime
  test-and-set gating effect emission.
- **Defensive de-dup at `journal->provider-messages`** is a provider-request
  projection in agent-session/turn-runtime — appropriately scoped to the path
  that produces the provider 400, distinct from the app-runtime canonical
  transcript reconstruction in the adapter-convergence roadmap. Purely derived,
  keyed by tool-call-id. Not a misfit.
- **Session-lifetime recorded-ids** correctly decoupled from per-turn
  `:pending-tool-calls`, matching the cross-turn race; bounded set.

No new follow-up items; prior architecture-fit items remain resolved by D1.

## Ambiguity review (design.md) — fifth pass

Re-reviewed design.md for ambiguities (statements admitting >1 interpretation),
not architecture-fit/correctness. Prior four ambiguity passes + inconsistency
items remain resolved. Cross-checked the determinism narrative for residual
tension between the early Desired-Behaviour bullet and the later determinism
framing the fourth pass installed.

New actionable ambiguity (see design-steps.md):
1. **The Desired-Behaviour bullet "An aborted, still-in-flight tool keeps its
   `"interrupted"` result for the model-visible turn" (design.md:118) reads as
   an *unconditional* interrupt-wins claim, contradicting the later
   first-writer-wins / concurrent-window framing.** The fourth-pass resolution
   reframed *which* result the model sees as **first-writer-wins by dispatch
   order** and explicitly added that in the concurrent-completion window the
   **real** result may win — but it rewrote only three sites (the Desired-
   Behaviour *determinism* bullet at :130+, the D1 Mechanism determinism bullet
   at :314+, and Resolved Question 3 at :376+), each of which now carefully
   qualifies "**genuinely** still in-flight (real result not yet produced/
   dispatched)". The earlier bullet at :118 was left using the bare phrase
   "still-in-flight tool keeps its interrupted result," which admits two
   readings: (a) for **any** id still in `:pending-tool-calls` at abort the
   interrupt result is deterministically kept (the over-claim the fourth pass
   removed elsewhere), or (b) "still-in-flight" silently carries the later
   bullet's narrow meaning "genuinely in-flight, real result not yet dispatched"
   (excluding the concurrent window). Reading (a) is literally false in the
   concurrent-completion window — the model sees the real result, so the tool
   does **not** keep its interrupted result. A reader reconciling :118 with the
   determinism bullet cannot tell which reading is intended. Distinct from the
   resolved fourth-pass item, which targeted the determinism bullet/D1/Resolved-Q3
   and never touched :118. Resolve by aligning :118 with the determinism framing:
   either qualify it ("genuinely still in-flight — real result not yet produced/
   dispatched") with a cross-reference to the determinism bullet, or restate it
   as "keeps its interrupted result in the typical headline case; in the
   concurrent-completion window the real result may win (still exactly one
   result)".

No blockers; one actionable ambiguity.

## Inconsistency review (design.md) — fifth pass

Re-reviewed design.md for internal inconsistencies and design-vs-code
inconsistencies (not ambiguity/architecture-fit). Re-verified every load-bearing
code cite against current source — all accurate:
- statechart-effect producer `statechart_actions.clj:132` (read non-nil
  `:interrupt-reason`) / `:149` (emit `:runtime/record-pending-tool-call-interrupts`,
  guarded by `(cond-> … interruption-reason …)`) ✓
- effect handler `dispatch_effects.clj:127` / enumerate `:131` / dispatch `:134`;
  in-memory record effect `:124` ✓
- synchronous abort producer `turn.clj:217` (record-pending-tool-call-interrupts!)
  / `:220` (enumerate `:pending-tool-calls`) / `:229` (abort-in! defn) / `:233`
  (call with `:user-abort`); `:deferred-interrupt` set at `turn.clj:193` via
  `:session/request-interrupt` (`:189`) ✓
- session-close producer `session_close.clj:55/58/61`, called by
  `close-session-in!` `:106` (after `abort-session-runtime!` `:105`) ✓
- real-result re-dispatch `tool_runtime_adapter.clj:114` ✓
- handler emits **both** effects unconditionally `session_mutations.clj:529/531/533`;
  `:interrupt-reason (or reason :deferred-interrupt)` at `:638` ✓
- `core.clj:407` (`disj` :pending-tool-calls inside record-tool-result-in!,
  `dispatch_effects.clj:124` effect only) / `:424` (conj on tool-start) / `:466`
  (agent-core abort-in! clears, does not record); per-turn clear in
  `end-loop-in!` `:447` confirms the "`:pending-tool-calls` resets at the per-turn
  boundary" claim ✓
- `journal->provider-messages` `prompt_request.clj:111` emits provider *message
  maps* (not blocks); `session->provider-messages` `:131`; `:turn/messages` `:296`;
  `conv/add-tool-result` `conversation.clj:95` is the sole `tool_result`-block
  emitter (`agent-messages->ai-conversation` `:136`); `build-provider-conversation`
  reads `:turn/messages` `request.clj:60` ✓

Checked the cross-section narrative for residual contradiction:
- `:user-abort` ⇒ synchronous `abort-in!` path / `:deferred-interrupt` ⇒
  statechart-effect path is now consistent across Root Cause step 2, the
  "Which producer fires for which reason" paragraph, the Funnel property, the
  Desired-Behaviour determinism bullets, and D1 Mechanism — matches code
  (`:interrupt-reason` is never assigned `:user-abort`).
- at-most-once (deterministic) vs first-writer-wins-by-dispatch (which result)
  reconciled identically at all four sites (:118, determinism bullet, D1
  Mechanism, Resolved Q3), all using the "genuinely still in-flight" qualifier
  and the concurrent-completion caveat.
- Root Cause "Result:" arrow routes block emission through the conversation
  rebuild, agreeing with step 4 and the De-dup Location/keying bullets.
- in-memory-history vs journal: forward fix covers both (both-or-neither);
  defensive de-dup is journal-projection only, and the broken-session recovery
  path is journal-derived (`:turn/messages`), so no second in-memory de-dup is
  needed — internally consistent.

**No new actionable inconsistency.** Design is internally consistent and
consistent with referenced code on all checked points.

## Architecture-fit review (design.md) — fifth pass

Independent architecture-fit pass on the current post-D1 (Option-C) design.
Grounded against doc/architecture.md (State boundary: canonical root vs runtime
handles; Dispatch sequencing contract — pure result → apply → effects last;
request preparation as pure projection), META.md, and AGENTS.md
(single-source-of-truth atom, dispatch-owns-writes, effects-as-data, one_way,
¬shims/adapters).

Fit confirmed; **no new actionable architectural misfit**:
- recorded-tool-result-ids in `:state*`, `:pending-tool-calls` (agent-core
  `swap-data!` atom) kept external for interrupt enumeration → State-boundary
  conformant.
- pure guarded handler returning `:root-state-update` with both-or-neither
  effect emission, atomicity from dispatch serialization (no test-and-set) →
  Dispatch sequencing contract.
- defensive de-dup at `journal->provider-messages` is a purely-derived
  provider-request projection keyed by tool-call-id (request prep = pure
  projection); complementary recovery of legacy duplicate journals, not a
  parallel source of truth → robust(code), defense-in-depth over distinct
  temporal populations (new-write prevention vs already-persisted recovery).
- single chokepoint at `:session/tool-agent-record-result` (funnel) → one_way /
  single-source; interrupt producers not routing through the dispatch-owned
  `:session/tool-record-result` slice is pre-existing topology, rerouting is out
  of scope.
- session-lifetime recorded-ids cleared on session reset/clear (same boundary as
  journal/history) → coherent canonical-state lifecycle; bounded by per-session
  tool-call count.

Considered and judged **non-actionable** (recorded so future passes do not
re-raise): D1's rationale invokes "observable, queryable status" to prefer
Option C, but the design specifies no EQL resolver over recorded-ids. This is
not a misfit — the State-boundary principle does not mandate a resolver for
every `:state*` datum, and adding one would be scope creep. The C-over-B
decision is independently load-bearing on the Dispatch-sequencing (pure-result
vs test-and-set) and cross-component-layering rationales, so the unrealized
queryability claim is non-load-bearing.

No new follow-up items; prior architecture-fit items remain resolved by D1.

## Ambiguity review (design.md) — sixth pass

Re-reviewed design.md for ambiguities (statements admitting >1 interpretation),
not architecture-fit/correctness. All prior five ambiguity-pass items +
inconsistency items remain resolved and present in design.md. Focused this pass
on residual tension in the determinism narrative and the under-specified-boundary
candidates, since those drove the prior diminishing-returns passes.

Checked and judged **non-actionable** (recorded so future passes do not re-raise):
- **Early Desired-Behaviour bullets (:114–:117) "first writer wins".** Bullets 1–2
  state plainly "at most one … first writer wins" / "Whichever of {real, interrupt}
  is recorded first is kept". These are consistent with — not contradicting — the
  later determinism bullet's "deterministic guarantee is at-most-once; winner is
  first-writer-wins by dispatch order". No unconditional interrupt-wins reading
  survives (the :118 bullet was qualified to "genuinely still in-flight" in pass 5).
  Not ambiguous.
- **"session reset/clear" recorded-ids clearing boundary.** D1 specifies it
  semantically as "the same boundary that discards the journal/history", decoupled
  from the per-turn `:pending-tool-calls` reset. The concrete clearing event is a
  plan/implementation detail; specifying the boundary by reference to the
  journal/history-discard lifecycle is appropriate at design level and admits one
  interpretation. Not actionable as ambiguity.
- **Async re-surface vs out-of-scope.** The background/async re-surfacing path is
  explicitly an orthogonal content-delivery mechanism, not a `tool_result`, and
  changing it is out of scope — consistently stated. Not ambiguous.

**No new actionable ambiguity.** The design is exhaustively disambiguated; five
prior passes show clear convergence (the last two addressed single-bullet phrasing
alignment, both resolved).

## Inconsistency review (design.md) — sixth pass

Independent inconsistency pass (internal + design-vs-code; not ambiguity/arch-fit).
Re-verified every load-bearing cite against current source — all accurate:
- statechart-effect producer: read `:interrupt-reason` `statechart_actions.clj:132`;
  emit `:runtime/record-pending-tool-call-interrupts` guarded by `interruption-reason`
  `:149/150` ✓
- effect handler `dispatch_effects.clj:127`, enumerate `:131`, dispatch `:134`;
  in-memory record effect `:124` ✓
- synchronous abort: `record-pending-tool-call-interrupts!` `turn.clj:217`,
  enumerate `:220`, `abort-in!` `:229` calling with `:user-abort` `:233` ✓
- session-close: `repair-pending-tool-calls-before-close!` `session_close.clj:55`,
  enumerate `:58`, dispatch `:61`; `close-session-in!` runs `abort-session-runtime!`
  `:105` then repair `:106` ✓
- real-result re-dispatch `tool_runtime_adapter.clj:113/114` ✓
- handler emits both effects `session_mutations.clj:529`; `:interrupt-reason
  (or reason :deferred-interrupt)` `:638`; `:session/request-interrupt` handler `:630`,
  sole dispatcher `turn.clj:189` passing `:deferred-interrupt` `:193` ✓
- `:user-abort` only at `turn.clj:233` (message reason); schema enum
  `model.clj:89` permits it for `:interrupt-reason` but no `assoc` ever writes it
  there — matches "only ever `:deferred-interrupt`" claim ✓

Cross-section narrative consistent: `:user-abort` ⇒ synchronous path /
`:deferred-interrupt` ⇒ statechart-effect path; at-most-once (deterministic) vs
first-writer-wins-by-dispatch (which result); single de-dup chokepoint at
`journal->provider-messages` with block emission via the conversation rebuild;
session-lifetime recorded-ids decoupled from per-turn `:pending-tool-calls`.

Considered and judged **non-actionable** (recorded so future passes do not
re-raise): Root Cause's "`:interrupt-reason` is written **only** by the
`:session/request-interrupt` handler" is, strictly, an overstatement —
`statechart_actions.clj:144/160` also write `:interrupt-reason nil` (the
`:on-agent-done` reset). But those are clearing writes; the load-bearing
conclusion the sentence supports ("the only non-nil reason ever set is
`:deferred-interrupt`; no code ever writes `:interrupt-reason :user-abort`") is
correct and code-verified. The imprecision changes no conclusion and re-flagging
would be a sixth-order nitpick.

**No new actionable inconsistency.** Design is internally consistent and
consistent with referenced code on all checked points.

## Plan/steps ambiguity follow-up resolution (plan + steps) — first pass

Executed the three plan/steps ambiguity follow-up items added by the preceding
review pass (commit 7082a3db1). All three resolve plan/steps ambiguities; no
implementation has started (all Slice A–D items still unchecked), so resolution
is confined to plan.md/steps.md refinement — no code/test/doc change yet.

Code grounding before deciding:
- `initialize-session-slots` (`session_state/init.clj:78`) seeds `:telemetry
  initial-telemetry` and `:turn`; it is the session-init/journal-discard boundary,
  called on new, resume (`:114`, `:181`), fork (`:151`), branch (`:204`), and
  child (`child_session_state.clj:228`). Existing update sites use `(fnil conj …)`
  defensively (`session_mutations.clj:469/498/588/597`). Path helpers
  (`session-data-path`, `session-telemetry-path`) live in `session_state/state.clj`.

Resolutions:
- **(item 3 — default source) → choice (a): init seeding.** Seed
  `:recorded-tool-result-ids #{}` in `initialize-session-slots` alongside
  `:telemetry`. This supplies the `#{}` default **and**, because init runs on
  every session-lifecycle reset that discards journal/history, clears the set on
  that same boundary — so it also **resolves the Slice-B clearing-boundary item**
  (no per-turn clear, no standalone clear handler). Read/update site keeps nil-safe
  `#{}` / `(fnil conj #{})` as defense-in-depth only, not as the canonical default.
  Chosen over (b) read-site-only because the codebase seeds slots explicitly and
  this collapses default + clearing into one consistent mechanism.
- **(item 2 — repro interrupt path) → `:user-abort` synchronous `abort-in!`.**
  Slice-B repro now drives `turn.clj:233` `abort-in!` →
  `record-pending-tool-call-interrupts!` (`turn.clj:217`), matching the design
  Evidence and the deterministic first-writer reasoning; excludes the
  statechart-effect `:deferred-interrupt` producer (different ordering).
- **(item 1 — assertion layer) → raw recorded layer.** Slice-B repro asserts
  exactly one `toolResult` entry for the id in the journal + agent-core in-memory
  message history (not the rebuilt provider conversation), isolating the forward
  fix from the Slice-C `journal->provider-messages` de-dup. Slice-C retains the
  separate projection-recovery test.

Edits: steps.md (Slice A default-source bullet, Slice B repro/handler/clearing
bullets, three follow-up items checked with resolutions), plan.md (Key decisions
+ Slice order Slice-A/Slice-B). No blockers; all three items completed.

## Plan/steps inconsistency review (plan.md + steps.md) — first pass

Reviewed `plan.md` and `steps.md` for inconsistencies — internal/cross-file
disagreements and plan/steps-vs-code/design citation drift (not ambiguity/
architecture-fit). Grounded against code: handler `:session/tool-agent-record-result`
takes `_ctx` and currently returns only `{:effects [...]}`
(`session_mutations.clj:529/531/533`) — confirms the rename premise; `abort-in!`
defn `turn.clj:229`, calls `record-pending-tool-call-interrupts!` with `:user-abort`
at `turn.clj:233`, `record-pending-tool-call-interrupts!` defn `turn.clj:217`;
`journal->provider-messages` `prompt_request.clj:111`; `initialize-session-slots`
seeds `:telemetry`/`:turn` `init.clj:78/87/88`; `agent-messages->ai-conversation`
`conversation.clj:136`, sole block emitter `conv/add-tool-result` `conversation.clj:95`.

New actionable inconsistencies (see steps.md → "Plan/steps inconsistency review follow-ups"):

1. **Slice-B test enumeration disagrees between plan.md and steps.md.** plan.md
   Slice order Slice B enumerates only three Slice-B tests — reproduction +
   "normal-single-result and interrupt-only coverage". steps.md Slice B lists
   **four** tests: it adds a dedicated test "asserting **at-most-once** under the
   concurrent-completion window (real result recorded first → real result kept,
   interrupt suppressed)". plan.md mentions the concurrent window only in its
   Risks section (as a constraint, "Tests must assert *at-most-once* … not
   unconditionally interrupt wins"), never as a Slice-B test. An implementer
   following plan.md writes 3 tests; following steps.md writes 4 — the two files
   disagree on Slice-B coverage. Reconcile: add the concurrent-completion
   at-most-once test to plan.md's Slice-B enumeration (or fold it explicitly into
   the listed coverage) so plan and steps agree.

2. **plan.md cites `agent-messages->ai-conversation` at the wrong line, contradicting
   the design's verified citation.** plan.md §3 ("Defensive projection de-dup")
   writes "The downstream conversation rebuild (`turn_runtime/conversation.clj`
   `agent-messages->ai-conversation`, the sole `tool_result`-block emitter,
   `conversation.clj:95`)", attaching line `:95` to `agent-messages->ai-conversation`.
   Code: `agent-messages->ai-conversation` is at `conversation.clj:136`; `:95` is
   `conv/add-tool-result` inside `append-tool-result-msg` (the actual sole
   block-emit site). design.md's De-dup Location bullet and the prior
   inconsistency-review passes deliberately distinguish these (rebuild fn `:136`;
   block emitter `conv/add-tool-result` `:95`). plan.md conflates the function
   name with the emit line, contradicting the verified design/code model.
   Reconcile: cite the rebuild at `:136` and attribute the block emission to
   `conv/add-tool-result` `:95` (matching design.md).

No blockers; two actionable plan/steps inconsistencies.

## Plan/steps inconsistency follow-up resolution (steps) — first pass

Executed both first-pass plan/steps inconsistency-review follow-up items; both
landed in plan.md (no code/test/doc touched — pure plan↔steps reconciliation).

Code verified before editing:
- `conv/add-tool-result` at `conversation.clj:95` (sole `tool_result`-block
  emitter, inside `append-tool-result-msg`); `agent-messages->ai-conversation`
  (the rebuild fn) at `conversation.clj:136`.

- **(1) Slice-B test enumeration reconciled.** plan.md Slice-order Slice B
  previously listed three tests (reproduction + normal-single-result +
  interrupt-only); steps.md Slice B lists a fourth (at-most-once
  concurrent-completion). Added the concurrent-completion at-most-once test to
  plan.md's Slice-B enumeration (it had appeared only in plan.md Risks), so
  plan.md and steps.md now agree on four Slice-B tests.
- **(2) `conversation.clj` citation fixed.** plan.md §3 mis-cited
  `agent-messages->ai-conversation` as the block emitter at `conversation.clj:95`.
  Rewrote to cite the rebuild fn at `:136` and attribute block emission to
  `conv/add-tool-result` (inside `append-tool-result-msg`) at `:95`, matching
  design.md's De-dup Location bullet and the code.

No blockers; both items completed.

## Plan/steps ambiguity review (plan.md + steps.md) — second pass

Reviewed `plan.md` + `steps.md` (not `design.md`) for statements admitting >1
implementer interpretation. Grounded against code: `journal->provider-messages`
= `(repair-dangling-tool-uses (into [] (keep …) journal))` (`prompt_request.clj:111`);
`repair-dangling-tool-uses` collects only the **contiguous** toolResult run after
each assistant message via `split-with tool-result-message?` and adds a synthetic
`interrupted` result for any tool-call-id not present in that contiguous run
(`prompt_request.clj:97-101`); `tool-result-message?` keys on `:role "toolResult"`,
`tool-result-id` = `:tool-call-id`; `initialize-session-slots` (`init.clj:78`) seeds
`:telemetry`/`:turn` after `assoc-in session-data-path next-sd` and is called on
new/resume/fork/branch/child (`init.clj:114/151/181/204`, `child_session_state.clj:228`)
— Slice-A seeding decision verified sound and unambiguous; the handler funnel
`:session/tool-agent-record-result` (`session_mutations.clj:529`) returns only
`{:effects […]}` today, confirming the both-or-neither rewrite premise.

New actionable ambiguity (see steps.md → "Plan/steps ambiguity review follow-ups
(second pass)"):

1. **Slice-C de-dup ordering relative to `repair-dangling-tool-uses` is unpinned,
   and the two placements yield different recovery outputs.** Plan §3 and steps
   Slice C say to add the de-dup "in `journal->provider-messages`" and to "ensure
   interaction with `repair-dangling-tool-uses` is correct (de-dup removes extras;
   repair adds missing)", but never state whether de-dup runs **before** or
   **after** `repair-dangling-tool-uses`. Because repair only inspects the
   *contiguous* toolResult run following each assistant message (`split-with`), a
   tool-call-id whose real result sits **non-contiguously** is treated as missing
   and gets a synthetic result appended. For an already-wedged/malformed journal —
   exactly Slice C's recovery target — de-dup-**before**-repair can leave **two**
   results for one id (non-contiguous real + repair's synthetic), while
   de-dup-**after**-repair guarantees ≤1 unconditionally. Plan's Risks "disjoint
   concerns" framing understates this interaction. Two reasonable implementer
   interpretations differ in robustness/outcome → actionable. Resolution: pin
   de-dup to run on `repair-dangling-tool-uses`'s **output** (wrap it) so
   at-most-once holds even against synthetic results repair adds for non-contiguous
   ids, and have the Slice-C recovery test include a **non-contiguous** duplicate
   so the test actually distinguishes (and locks) the placement.

No blockers; one actionable plan/steps ambiguity.

## Plan/steps ambiguity follow-up resolution (second pass)

Executed the single second-pass plan/steps ambiguity item (Slice-C de-dup
ordering relative to `repair-dangling-tool-uses`).

Code verified before editing:
- `journal->provider-messages` (`prompt_request.clj:111`) returns
  `(repair-dangling-tool-uses (into [] …))` (`prompt_request.clj:119`) — repair
  is the **last** transform applied today.
- `repair-dangling-tool-uses` (`prompt_request.clj:83`) scans only the
  **contiguous** toolResult run after each assistant tool-use block via
  `(split-with tool-result-message? (rest remaining))` (`prompt_request.clj:96`),
  collecting `present-ids` from that contiguous run and appending a synthetic
  `interrupted-tool-result` for each tool-call-id **not** present in it. So a real
  toolResult for an id that is **non-contiguous** with its assistant block is seen
  as missing, and a synthetic for the same id is appended → two results for one id
  survive a de-dup applied to the pre-repair list.

Resolution applied (decision: **de-dup applies to repair's output**):
- **plan.md §3** — added a "De-dup ordering = after `repair-dangling-tool-uses`
  (wrap its output)" paragraph with the contiguous-scan rationale and the concrete
  shape `(dedupe-tool-results (repair-dangling-tool-uses (into [] …)))`.
- **plan.md Risks** — rewrote the "De-dup ordering vs `repair-dangling-tool-uses`"
  bullet from "disjoint concerns / ensure ≤1" to the decided de-dup-after-repair
  ordering, noting the order is load-bearing and the Slice-C test must include a
  non-contiguous duplicate.
- **plan.md Slice order (Slice C)** — pinned de-dup to repair's output and required
  the recovery test to include a non-contiguous duplicate.
- **steps.md Slice C** — rewrote the de-dup step to wrap repair's output (with
  rationale + line cites) and extended the recovery-test step to assert both a
  contiguous and a **non-contiguous** duplicate yield exactly one `tool_result`
  per id, locking de-dup-after-repair.

No blockers; item completed. (Task remains design/plan-only; Slices A–D not yet
implemented.)

## Plan/steps inconsistency review (plan.md + steps.md) — second pass

Reviewed `plan.md` + `steps.md` for cross-file inconsistencies (internal +
plan/steps-vs-design/code citation drift; not ambiguity/architecture-fit).
Re-verified every plan/steps code cite against current source — all accurate:
handler `:session/tool-agent-record-result` `session_mutations.clj:529` takes
`_ctx` and returns only `{:effects […]}` (rename premise holds);
`journal->provider-messages` `prompt_request.clj:111` wraps
`repair-dangling-tool-uses` at `:119`, `split-with` contiguous scan `:96`;
conversation rebuild `agent-messages->ai-conversation` `conversation.clj:136`,
sole block emitter `conv/add-tool-result` `:95`; `initialize-session-slots`
`init.clj:78` seeds `:telemetry` `:87` / `:turn` `:88`. Prior plan↔steps items
(Slice-B test count 3→4; `conversation.clj:95` vs `:136`) remain resolved and
consistent.

New actionable inconsistency (see steps.md → "Plan/steps inconsistency review
follow-ups (second pass)"):

1. **design.md's forward-fix reproduction-test assertion layer contradicts the
   plan/steps raw-recorded-layer decision.** design.md still pins the forward-fix
   reproduction test to the rebuilt provider conversation in **two** places:
   Scope ("asserts a single `tool_result` per `tool_use` in the rebuilt provider
   conversation", design.md:225) and Acceptance Criteria bullet 1 ("asserting
   exactly one `tool_result` per `tool_use` id in the provider-facing
   conversation", design.md:396). But plan.md Key decisions ("Forward-fix repro …
   asserted at the **raw recorded layer** … the journal + agent-core in-memory
   history — **not** on the rebuilt provider conversation") and steps.md Slice B
   (plus the resolved ambiguity follow-up item 1) deliberately moved that
   assertion to the raw recorded layer **specifically so the Slice-C
   `journal->provider-messages` de-dup cannot mask a forward-fix regression**. An
   implementer following design.md asserts on the post-de-dup rebuild (the exact
   masking the plan/steps decision exists to prevent); following plan/steps
   asserts on the raw layer. The two file sets disagree on the load-bearing
   assertion layer of the same reproduction test. Reconcile: update design.md
   Scope (:225) + AC bullet 1 (:396) to assert the forward-fix reproduction at the
   raw recorded layer (journal + agent-core in-memory history), keeping the
   separate Slice-C projection-recovery assertion (design AC bullet 4, :399-403)
   on the rebuild/`journal->provider-messages` de-dup.

No blockers; one actionable plan/steps inconsistency.

## Plan/steps inconsistency follow-up resolution (third pass)

Executed the single third-pass plan/steps inconsistency item (Slice-A proposed
path-helper name vs `session-` prefix convention).

Verified the convention against code: every per-session path helper in
`components/session-state/src/psi/session_state/state.clj` is `session-`-prefixed
(`session-data-path` `:29`, `session-telemetry-path` `:30`,
`session-turn-ctx-path` `:33`, `session-scheduler-path` `:34`,
`session-scheduler-schedules-path` `:35`, `session-scheduler-queue-path` `:36`).

Resolution applied to steps.md Slice A step 1: renamed the proposed helper from
`recorded-tool-result-ids-path [sid]` to the `session-`-prefixed form
`session-recorded-tool-result-ids-path [sid]`
(`[:agent-session :sessions sid :recorded-tool-result-ids]`), and inlined the
existing `session-`-prefixed helpers it must match (replacing the prior loose
"consistent with existing per-session path helpers" wording with the explicit
prefixed list). The proposed name and the consistency requirement now agree.
plan.md needs no change — it refers to "the `:state*` path helper" generically
and never names the helper.

No blockers; item completed.

## Plan/steps inconsistency review (second pass) follow-up — design repro-test assertion layer

Executed the single second-pass plan/steps inconsistency item: design.md still
pinned the forward-fix reproduction test to the **rebuilt provider conversation**
(Scope characterization-test bullet + Acceptance Criteria bullet 1), contradicting
plan.md Key decisions and steps.md Slice B, which assert the forward-fix
reproduction at the **raw recorded layer** so the Slice-C
`journal->provider-messages` de-dup cannot mask a forward-fix regression.

Resolution applied to design.md (two sites):
- **Scope characterization-test bullet** — now asserts at the raw recorded layer
  (exactly one `toolResult` entry per tool-call-id in the journal **and** the
  agent-core in-memory message history), via the `:user-abort` synchronous
  `abort-in!` path, explicitly **not** on the rebuilt provider conversation;
  notes the projection-level recovery is characterized separately.
- **Acceptance Criteria bullet 1** — same reframing to the raw recorded layer +
  `:user-abort` synchronous path, explicitly not the rebuilt provider
  conversation; cross-references the separate already-wedged-journal recovery AC.
- **AC bullet 4** (already-wedged journal projects to one `tool_result` via the
  upstream `journal->provider-messages` de-dup) left unchanged on the rebuild /
  de-dup path — the two fixes stay independently characterized.

design.md now agrees with plan.md Key decisions and steps.md Slice B. No blockers;
item completed. No code/test/doc changes required (design-only reconciliation).

## Plan/steps ambiguity review (plan.md + steps.md) — third pass

Reviewed `plan.md` + `steps.md` (not `design.md`) for statements admitting >1
implementer interpretation. Re-verified the existing pins against code:
`:root-state-update` is established as a `(fn [state] …)` over root-state
(`session_mutations.clj:509`, `dispatch_schema.clj:17` `fn?`), so steps Slice-B's
`<(fnil conj #{}) id into recorded-ids>` is loose shorthand but the convention is
unambiguous in practice; `record-pending-tool-call-interrupts!` (`turn.clj:217`)
enumerates `:pending-tool-calls` and dispatches the record event, and
`record-tool-result-in!` performs the `:pending-tool-calls` `disj` **inside** the
`:runtime/agent-record-tool-result` effect (`agent_core/core.clj:407`), i.e.
strictly after the handler's apply — confirming the design's apply-before-disj
window.

New actionable ambiguity (see steps.md → "Plan/steps ambiguity review follow-ups
(third pass)"):

1. **The Slice-B concurrent-completion (4th) test's construction mechanism is
   unspecified, and the obvious construction cannot exercise the suppression it
   claims to.** Steps Slice B test 4 says "assert at-most-once under the
   concurrent-completion window (real result recorded first → real result kept,
   interrupt suppressed) — assert exactly one result, not which one." The
   *assertion* is pinned, but the *setup* is not, and the setup is the hard part.
   The headline repro (test 1) is explicitly driven through `abort-in!`
   (`turn.clj:233`), which enumerates `:pending-tool-calls` and dispatches an
   interrupt record-event **only** for ids still present in that set
   (`turn.clj:219-220`). But `:pending-tool-calls` `disj` for an id happens
   *inside* the real result's `:runtime/agent-record-tool-result` effect
   (`agent_core/core.clj:407`), which runs after that handler's apply completes.
   In a single-threaded sequential dispatch test, once the real result's
   `:session/tool-agent-record-result` dispatch has fully run (apply **and**
   effect), the id is both in recorded-ids **and** already removed from
   `:pending-tool-calls` — so a subsequent `abort-in!` enumerates a set that no
   longer contains the id and **dispatches no interrupt at all**. The genuine
   concurrent window (id still pending because the `disj` effect has not yet run
   while recorded-ids already has the id) only exists under real apply/effect
   interleaving, which sequential tests cannot reproduce without a seam.
   Two materially different implementer interpretations result: (a) drive
   `abort-in!` after recording the real result — but then the interrupt is never
   dispatched, so the test vacuously "passes" without ever exercising the
   suppression path it purports to cover (false-confidence test); or (b) bypass
   `abort-in!` and directly dispatch two `:session/tool-agent-record-result`
   events for the same id in real-then-interrupt order — trivially deterministic
   and does exercise the recorded-ids suppression, but does **not** go through the
   `abort-in!` enumeration path that the real concurrent race uses. The step gives
   no guidance on which, and the choice decides whether the test actually proves
   "interrupt suppressed when real result won the race." Pin the construction:
   state that the concurrent-completion test directly dispatches the two
   `:session/tool-agent-record-result` events (real first, then a synthetic
   interrupt for the same id) — exercising the handler chokepoint's first-writer
   suppression — and explicitly note that the faithful `abort-in!`-enumeration
   window is not sequentially reproducible (so `abort-in!` is **not** the vehicle
   for this test), or specify the seam if `abort-in!` is required.

No blockers; one actionable plan/steps ambiguity. (Task remains design/plan-only;
Slices A–D not yet implemented.)

## Plan/steps ambiguity follow-up resolution (third pass)

Executed the single third-pass plan/steps ambiguity item (pin the Slice-B
concurrent-completion (4th) test's construction mechanism).

Code verified before editing:
- `record-pending-tool-call-interrupts!` (`turn.clj:217`) reads
  `:pending-tool-calls` synchronously (`turn.clj:219-220`) and dispatches
  `:session/tool-agent-record-result` only for ids still in that set; called by
  `abort-in!` (`turn.clj:233`).
- The real result's `:runtime/agent-record-tool-result` effect runs
  `record-tool-result-in!`, which `disj`s the id from `:pending-tool-calls`
  (`agent_core/core.clj:407`) *after* the `:session/tool-agent-record-result`
  handler applies (handler `session_mutations.clj:529` only emits effects).
- Confirms the reviewer's window: in a sequential test, once the real result has
  fully run (apply + effect), the id is in recorded-ids and gone from
  `:pending-tool-calls`, so a later `abort-in!` enumerates nothing and dispatches
  no interrupt — an `abort-in!`-based test would pass vacuously.

Resolution applied (chose **direct dispatch**, `abort-in!` not the vehicle):
- **steps.md Slice B test 4** now pins construction: directly dispatch the two
  `:session/tool-agent-record-result` events for one id (real first, then a
  synthetic `"interrupted"` for the same id) to exercise the handler chokepoint's
  first-writer suppression; explicit note that `abort-in!` is not the vehicle and
  why (enumeration window not sequentially reproducible).
- **plan.md Slice B** now states the same construction + the `abort-in!`-vacuity
  rationale, grounded in the `disj`-in-effect ordering.

No blockers; item completed. (Task remains design/plan-only; Slices A–D not yet
implemented.)

## Plan/steps inconsistency review (plan.md + steps.md) — third pass

Reviewed `plan.md` + `steps.md` for cross-file / internal inconsistencies
(citation drift + intra-file disagreement; not ambiguity/architecture-fit).
Re-verified every load-bearing code cite against current source — all accurate:
handler `:session/tool-agent-record-result` `session_mutations.clj:529` takes
`_ctx`, returns only `{:effects [...]}` (rename premise holds); `abort-in!`
`turn.clj:229`, calls `record-pending-tool-call-interrupts!` with `:user-abort`
`turn.clj:233`, enumeration `turn.clj:217/220`; `disj` of `:pending-tool-calls`
inside `:runtime/agent-record-tool-result` at `agent_core/core.clj:407`
(post-apply); adapter `:record-result!` re-dispatch `tool_runtime_adapter.clj:114`;
`journal->provider-messages` `prompt_request.clj:111` wrapping
`repair-dangling-tool-uses` `:119`, contiguous `split-with` `:96`; conversation
rebuild `agent-messages->ai-conversation` `conversation.clj:136`, sole block
emitter `conv/add-tool-result` `:95`; `initialize-session-slots` `init.clj:78`.
Prior plan↔steps items (Slice-B test count 3→4; `conversation.clj:95`↔`:136`;
design repro raw-recorded-layer) remain resolved and consistent.

New actionable inconsistency (see steps.md → "Plan/steps inconsistency review
follow-ups (third pass)"):

1. **Slice-A proposed path-helper name violates the very naming convention the
   same step requires.** steps.md Slice A proposes the helper
   `recorded-tool-result-ids-path [sid]` (no `session-` prefix), and its third
   bullet then requires "naming/placement is **consistent with existing
   per-session path helpers** (`session-data-path`, `session-telemetry-path`)".
   But every existing per-session path helper in
   `components/session-state/src/psi/session_state/state.clj` is uniformly
   `session-`-prefixed: `session-data-path` (`:29`), `session-telemetry-path`
   (`:30`), `session-turn-ctx-path` (`:33`), `session-scheduler-path` (`:34`),
   `session-scheduler-schedules-path` (`:35`), `session-scheduler-queue-path`
   (`:36`). The proposed `recorded-tool-result-ids-path` drops the `session-`
   prefix, so an implementer who follows the literal proposed name breaks the
   convention the same step demands, while one who follows the convention bullet
   must rename it (e.g. `session-recorded-tool-result-ids-path`). The two
   directives in one step contradict. Reconcile: rename the proposed helper to
   the `session-`-prefixed form (`session-recorded-tool-result-ids-path`),
   matching the established convention the bullet cites.

No blockers; one actionable plan/steps inconsistency.

## Plan/steps ambiguity review (plan.md + steps.md) — fourth pass

Reviewed `plan.md` + `steps.md` (not `design.md`) for statements admitting >1
implementer interpretation. Prior three plan/steps ambiguity passes (assertion
layer / repro interrupt path / Slice-A default source; Slice-C de-dup ordering;
Slice-B test-4 construction) and the three plan/steps inconsistency passes remain
resolved. Task is still design/plan-only (Slices A–D unchecked).

Re-grounded the remaining candidate ambiguities against code:
- **Slice-A path vs seeding placement (candidate — non-actionable).** Proposed
  helper `session-recorded-tool-result-ids-path [sid]` →
  `[:agent-session :sessions sid :recorded-tool-result-ids]`
  (`session_state/state.clj`). `initialize-session-slots`
  (`session_state/init.clj:87/88`) seeds `[… sid :telemetry]` and `[… sid :turn]`
  **directly under the session map**, and `session-telemetry-path`
  (`state.clj:30`) is `[… sid :telemetry k]` — same session-map level. So
  "seed `#{}` alongside `:telemetry`" writes the datum at exactly the level the
  path helper reads. No level mismatch; placement is unambiguous.
- **Handler read API (candidate — non-actionable).** `get-state-value-in`
  (`state.clj:84`) is 2-arity `[ctx path]`; the handler already destructures
  `{:keys [session-id tool-result-msg]}` and `tool-result-msg` carries top-level
  `:tool-call-id`. Steps' "read via `session/get-state-value-in`, nil-safe to
  `#{}`" combined with the Slice-A path helper resolves to one call shape.
  Current handler returns only `{:effects [...]}` (`session_mutations.clj:529`),
  confirming the both-or-neither rewrite premise. Single interpretation.
- **Slice-B repro setup "start a tool call (pending)" (candidate —
  non-actionable).** The step qualifies "pending" / "still-pending id", which
  already constrains the tool to be in-flight (not completed) at abort; the
  concrete pending-seeding mechanism is ordinary test mechanics that does not
  change the repro's validity so long as the id is pending when `abort-in!`
  enumerates `:pending-tool-calls`.
- **`:root-state-update` shorthand, de-dup first-occurrence-wins, Slice-C test
  wiring** — all previously judged unambiguous and re-confirmed.

**No new actionable plan/steps ambiguity.** Three prior ambiguity passes plus
three inconsistency passes have converged; plan.md and steps.md are exhaustively
disambiguated and agree with each other and with referenced code on every checked
point. No follow-up items added.

## Plan/steps inconsistency review (plan.md + steps.md) — fourth pass

Reviewed `plan.md` + `steps.md` for cross-file / internal inconsistencies
(citation drift + intra-file disagreement; not ambiguity/architecture-fit).
Re-verified every load-bearing code cite against current source — all accurate:
- handler `:session/tool-agent-record-result` fn line `session_mutations.clj:529`
  (still `_ctx`, returns `{:effects [...]}` — rename premise holds);
- `record-pending-tool-call-interrupts!` defn `turn.clj:217`, enumeration `:220`;
  `abort-in!` defn `turn.clj:229` with its `:user-abort` dispatch of
  `record-pending-tool-call-interrupts!` at the call line `:233` (the meaningful
  dispatch line the files cite, consistent with their internal-line convention
  e.g. `dispatch_effects.clj:131`, `turn.clj:220`, `core.clj:407`);
- `disj` of `:pending-tool-calls` inside `record-tool-result-in!`
  (`core.clj:398`) at `:407`, run by `:runtime/agent-record-tool-result`
  (`dispatch_effects.clj:124/125`); adapter `:record-result!` re-dispatch
  `tool_runtime_adapter.clj:114`;
- `journal->provider-messages` defn `prompt_request.clj:111` wrapping
  `repair-dangling-tool-uses` (defn `:83`) at `:119`; the repair's contiguous
  `split-with tool-result-message?` at `:96` (the one inside repair, not the `:56`
  occurrence);
- conversation rebuild `agent-messages->ai-conversation`
  `turn_runtime/conversation.clj:136`; sole block emitter `conv/add-tool-result`
  `:95` (inside `append-tool-result-msg` `:93`);
- Slice-A path helpers `session-data-path` `:29` … `session-scheduler-queue-path`
  `:36` all `session-`-prefixed; `get-state-value-in [ctx path]` `state.clj:84`;
  `initialize-session-slots` defn `init.clj:78`, seeds `:telemetry`/`:turn` at
  `:87`/`:88` (the "alongside `:telemetry`" anchor).

Cross-file checks: Slice-B test enumeration (4 tests) matches between plan.md and
steps.md; the reproduction (test 1, interrupt-first via `abort-in!`) vs the
concurrent-completion (test 4, real-first via direct dispatch, `abort-in!`
explicitly excluded) are complementary and non-contradictory; Slice-A helper name
(`session-`-prefixed), `conversation.clj:95`/`:136` split, and the design
raw-recorded-layer repro all remain reconciled from prior passes. Slice-D
CHANGELOG framing agrees (Fixed; session no longer wedges after abort).

**No new actionable inconsistency.** Three prior plan/steps inconsistency passes
have converged; plan.md and steps.md agree with each other and with referenced
code on every checked point. No follow-up items added.

## Implementation pass (2026-06-10)

Executed Slices A–D. All design decisions held; no deviations.

### Slice A — canonical recorded-ids state
- `session-recorded-tool-result-ids-path [sid]` →
  `[:agent-session :sessions sid :recorded-tool-result-ids]` added to
  `session_state/state.clj` (alongside `session-telemetry-path`).
- Seeded `:recorded-tool-result-ids #{}` in `initialize-session-slots`
  (`session_state/init.clj`) alongside `:telemetry`/`:turn` — supplies the `#{}`
  default and clears on every session-lifecycle reset
  (new/resume/fork/branch/child) in one place. No separate clear handler.

### Slice B — guarded handler (forward fix) + tests
- `:session/tool-agent-record-result` handler
  (`dispatch_handlers/session_mutations.clj`) is now a pure both-or-neither
  transform: `_ctx`→`ctx`; reads recorded-ids (nil-safe `#{}`); if the
  `tool-call-id` is present returns `{}` (suppresses both effects); else returns
  a `:root-state-update` adding the id via `(fnil conj #{})` plus both effects
  (`:runtime/agent-record-tool-result` + `append-message-effect`). Atomicity
  from dispatch serialization; no agent-core test-and-set.
- New test ns `tool_result_at_most_once_test.clj` (4 tests, all green):
  1. abort-races-real-result → exactly one toolResult (journal + in-memory),
     interrupt wins (headline `:user-abort` `abort-in!` path, asserted at the raw
     recorded layer).
  2. normal-single-result path unaffected.
  3. interrupt-only path → one `"interrupted"` result.
  4. concurrent-completion via **direct dispatch** of the two record events
     (real first, then synthetic interrupt) → real result wins, exactly one
     result. `abort-in!` deliberately not the vehicle (would pass vacuously —
     real-result effect `disj`s the id from `:pending-tool-calls`).
- Clearing-boundary check: `initialize-session-slots` is the sole
  journal/history-discard + session-init seam; no journal-only `/clear` reset
  bypasses it, so the init seeding fully covers reseed. (`/clear` is not a
  distinct journal-only reset in this codebase.)

### Slice C — defensive projection de-dup + test
- Added `dedupe-tool-results` (private) in `prompt_request.clj`; wraps
  `(repair-dangling-tool-uses (into [] …))` as
  `(dedupe-tool-results (repair-dangling-tool-uses …))` inside
  `journal->provider-messages` — de-dup-**after**-repair. First occurrence of a
  `toolResult` tool-call-id wins; ordering and non-toolResult messages preserved.
- New test `journal-duplicate-tool-results-project-to-one-test` in
  `prompt_request_test.clj`: a journal with both a **non-contiguous** duplicate
  (assistant tool-use → user msg → real result, so repair synthesizes a second)
  and a **contiguous** duplicate projects through the conversation rebuild to
  exactly one `tool_result` per id. Locks de-dup-after-repair (non-contiguous
  case would be two under de-dup-before-repair).

### Slice D — verify/docs/changelog
- clj-kondo clean on all 4 changed src files + 2 test files.
- `bb commit-check:dispatch-architecture`: failures=0; no **new** advisories
  (handler stays pure — `:root-state-update` + effects-as-data, no direct
  `:state*` swap).
- CHANGELOG `[Unreleased]` → Fixed: tool-use no longer wedges a session after a
  turn abort; already-wedged journals recover via projection de-dup.
- Focused suites green: agent-core core (11), session-close (5),
  prompt-request (18 incl. new), prompt-lifecycle (24), new at-most-once (4).
- Note: `tool_execution_test` cannot load under the bare `:test-paths`
  classpath (`psi.metrics.extension` not on it) — pre-existing, extension-path
  dependent; unrelated to this change. Full `bb test` includes extension paths.

No doc file documents the toolResult invariant beyond CHANGELOG, so no other doc
update needed.

## Implementation review (task-implementation-review) — first pass

Reviewed code/tests/docs against design D1 (Option C) + architecture. Verified:
re-ran `tool-result-at-most-once-test` + `prompt-request-test` (21 tests, 62
assertions, all green); clj-kondo clean on all changed files; CHANGELOG Fixed
entry present and user-facing.

Fit confirmed — no architectural or correctness misfit:
- Forward-fix handler (`session_mutations.clj:529`) is a pure both-or-neither
  guard reading the canonical `:state*` recorded-ids set via
  `session/get-state-value-in`, returning `:root-state-update` + both effects or
  `{}`; atomicity from dispatch serialization, no test-and-set. Matches D1
  exactly (State-boundary, Dispatch-sequencing, single-source).
- Init seeding (`init.clj`) supplies default + session-lifecycle clear in one
  place; path helper `session-`-prefixed per convention.
- `dedupe-tool-results` de-dup-after-repair in `journal->provider-messages`
  recovers wedged journals; non-contiguous + contiguous recovery test locks the
  ordering. Single upstream chokepoint, no second de-dup site.

Actionable (one):
1. **Characterization tests reimplement production pending-marking instead of
   using the public agent-core API.** `abort-races-real-result-…` and
   `interrupt-only-…` seed the in-flight tool with
   `(swap! (:data-atom agent-ctx) update :pending-tool-calls (fnil conj #{}) id)`
   — a direct reach into the agent-core data-atom internals that re-implements
   `agent/emit-tool-start-in!` (`agent_core/core.clj:420`, the production
   mechanism that adds an id to `:pending-tool-calls` *and* emits the
   `:tool-execution-start` event). Per the project λtest guidance (use real
   infrastructure for logic deps, not reimplementations), drive the pending state
   through `agent/emit-tool-start-in!` so the test (a) exercises the real
   in-flight path including the start event, and (b) is robust to changes in the
   `:pending-tool-calls` representation rather than silently breaking or passing
   vacuously. (`concurrent-completion-…` correctly uses direct dispatch by
   design and is out of scope for this item.)

## Implementation review follow-up (1st pass) — resolved

Replaced the direct `:data-atom` swap with the public
`agent/emit-tool-start-in!` API in the two characterization tests that seed
in-flight tool state (`tool_result_at_most_once_test.clj`):

- `abort-races-real-result-yields-one-tool-result-test`
- `interrupt-only-path-yields-one-result-test`

Both now call
`(agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name "bash" :arguments "{}"})`,
exercising the real in-flight path (incl. the `:tool-execution-start` event) and
staying robust to `:pending-tool-calls` representation changes.
`concurrent-completion-real-result-wins-test` left on its design-pinned direct
dispatch (out of scope).

Verification: clj-kondo clean, clj-paren-repair success; focused suite green
(4 tests / 14 assertions); full `bb clojure:test:unit` green (exit 0).

## Implementation review (task-implementation-review) — second pass

Re-reviewed code/tests/docs against design D1 (Option C) + architecture. Verified:
focused suite green (`tool-result-at-most-once-test` + `prompt-request-test`, 21
tests / 62 assertions); clj-kondo clean on all changed files; handler is a pure
both-or-neither guard reading canonical `:state*` recorded-ids and returning
`:root-state-update` + both effects or `{}` (matches D1); `dedupe-tool-results`
de-dup-after-repair in `journal->provider-messages` is a local domain dedupe,
consistent with existing per-component dedupe helpers (`memory/core.clj`
`dedupe-records`, `resolvers/telemetry.clj` `dedupe-api-errors`) — not a missed
reusable shared utility. No new architectural/abstraction/performance misfit.

Actionable (one):
1. **The load-bearing session-scoped (not turn-scoped) lifetime of recorded-ids
   is not exercised by any test, so a regression to turn-scoped reset would pass
   the whole suite while reintroducing the cross-turn duplicate.** design.md D1
   flags the persistence/reset boundary as "outcome-determining, not a plan
   detail": recorded-ids "must persist for the session lifetime, not reset at the
   turn boundary," because the headline race is **cross-turn** (the aborted
   tool's real result arrives in a *later* turn than the one that recorded the
   interrupt). The implementation is correct — recorded-ids is touched only in
   `initialize-session-slots` (session lifecycle), while `:pending-tool-calls`
   resets per-turn in `end-loop-in!` (`agent_core/core.clj:449`). But every
   characterization test records the interrupt and the late real result within a
   single in-flight sequence with **no intervening turn boundary**
   (`abort-races-real-result-…`, `interrupt-only-…`, `concurrent-…` never call
   `end-loop-in!` / start a new turn between the two record events). So if a
   future change added a recorded-ids clear at the per-turn boundary (mirroring
   `:pending-tool-calls`), all four tests would still pass yet the cross-turn
   headline race this task exists to fix would silently regress — the test net
   does not lock the session-scoped decision. Add a regression test that crosses
   a turn boundary between recording the interrupt result and dispatching the
   late real result for the same tool-call-id (e.g. record the synthetic
   interrupt, advance the turn via `end-loop-in!`/new-turn so `:pending-tool-calls`
   resets, then dispatch the real result) and assert at-most-once still holds at
   the raw recorded layer — so a turn-scoped reset of recorded-ids would fail.

## Implementation review (second pass) follow-up — cross-turn recorded-ids regression test

Executed the single second-pass implementation-review follow-up item: add a
cross-turn regression test locking the session-scoped lifetime of recorded-ids.

Added `recorded-ids-survive-turn-boundary-test` to
`components/agent-session/test/psi/agent_session/tool_result_at_most_once_test.clj`:
- turn N: `agent/emit-tool-start-in!` (pending) → `session/abort-in!` records the
  synthetic `"interrupted"` result and adds the id to the canonical recorded-ids
  set in `:state*`.
- turn boundary: `agent/end-loop-in!` resets `:pending-tool-calls #{}` (asserted),
  the per-turn reset the recorded-ids set must NOT share. `end-loop-in!` only
  touches the agent-core data atom, so `:state*` recorded-ids is untouched.
- turn N+1: `record-result!` dispatches the late real result for the same id; the
  guard reads recorded-ids from `:state*` (still present) and suppresses it.
- asserts exactly one `toolResult` for the id at the raw recorded layer (journal
  + agent-core in-memory history), interrupt first-writer-wins.

A turn-scoped clear of recorded-ids (mirroring the `:pending-tool-calls` reset)
would let the late real result record a second entry → the `(= 1 (count …))`
assertions fail; this is the regression the test locks.

Test-only change (no production edit, no docs/changelog — the guarantee is
already documented). clj-kondo clean, clj-paren-repair clean. Focused suite green
(5 tests / 19 assertions).

## Implementation review (task-implementation-review) — third pass

Re-reviewed code/tests/docs against design D1 (Option C) + architecture.
Independently verified the single-upstream-chokepoint claim by tracing the
production provider-request path end to end:
`session->provider-messages` (`prompt_request.clj:151`) →
`journal->provider-messages` (de-dup applied after `repair-dangling-tool-uses`,
`prompt_request.clj:139`) → `:turn/messages` (`prompt_request.clj:317`) →
`build-provider-conversation` (`request.clj:54/81`, sole production caller, reads
`:turn/messages`) → `agent-messages->ai-conversation` (`conversation.clj:136`,
sole production caller of the rebuild) → `add-tool-result` (`conversation.clj:95`,
sole `tool_result`-block emitter). The de-dup sits strictly upstream of the only
block emitter, and the rebuild has exactly one production caller — so the
single-chokepoint guarantee holds.

Also verified no `:pending-tool-calls` leak from suppression: the winning writer's
`:runtime/agent-record-tool-result` effect always `disj`s the id
(`agent_core/core.clj:407`); the suppressed loser shares the same id, so pending
is always cleared by the winner regardless of which producer wins.

Verification: focused suites green (`tool-result-at-most-once-test` 5/19,
`prompt-request-test` 17/48); clj-kondo clean on all changed files; CHANGELOG
Fixed entry present and user-facing.

No new architectural/abstraction/performance/correctness misfit. The two prior
implementation-review actionable items (public `emit-tool-start-in!` seeding;
cross-turn lifetime regression test) remain resolved. **No new actionable
feedback.**

## Test review (task-test-review) — first pass

Applied `task-test-review`: well-formedness, behaviour coverage (∀b ∈ design.
∃t), and infra-dep hygiene (injectable ∧ nullable ∧ ¬mock ∧ ¬stub).

Verified:
- **Well-formed.** Both test files (`tool_result_at_most_once_test.clj`,
  `prompt_request_test.clj` de-dup block) are clear, single-purpose, accurately
  named, with helper extraction (`record-result!`, `journal-tool-results`,
  `memory-tool-results`, `rebuilt-tool-result-count`). `(first journal/memory)`
  is sound only because count=1 is asserted first. Green: 5/19 + 17/48.
- **Infra deps clean.** Real `session/create-context` + in-memory persistence
  (`:persist? false`); real agent-core via `agent/emit-tool-start-in!`. No
  mocks, no stubs, no interaction assertions — state/output assertions only
  (journal entry counts + in-memory message counts + winning tool-name).
- **Behaviour coverage.** Forward-fix headline (interrupt wins), both-or-neither
  (journal ∧ memory asserted together), normal single-result, interrupt-only,
  concurrent-completion (real wins, direct-dispatch seam per design pin),
  cross-turn lifetime, and projection recovery (contiguous ∧ non-contiguous,
  locking de-dup-after-repair) are all covered. Cross-id isolation is implicitly
  covered (each id asserted →1, so over-dedup collapsing distinct ids would be
  caught).

New actionable (1) — see steps.md "Test review follow-ups (first pass)":
- The projection de-dup test (`journal-duplicate-tool-results-project-to-one-test`)
  asserts only `count=1` per id, never **which** result survives. design.md Scope
  pins the projection de-dup as "**first occurrence wins, purely derived from the
  journal**", and the forward-fix tests do assert their winner — but this test
  would pass equally if de-dup kept the *last* occurrence. The contiguous case has
  distinct content (`first-contig` vs `dup-contig`), so locking "first occurrence
  wins" is a one-line assertion.

## Test review follow-up (first pass) — resolved

- **Locked first-occurrence-wins in the projection de-dup test.** Extracted a
  `rebuilt-tool-results` helper from `rebuilt-tool-result-count` and added a third
  assertion to `journal-duplicate-tool-results-project-to-one-test`
  (`prompt_request_test.clj`): the surviving rebuilt `tool_result` for `id-contig`
  must carry `:content :text "first-contig"` (the **first** journal occurrence),
  not `dup-contig`. A last-wins `dedupe-tool-results` would now fail. Non-contiguous
  case left count-only (its survivor is the repair-appended synthetic). Focused
  test green (1 test / 3 assertions); clj-kondo clean; parens balanced.

## Test review (task-test-review) — second pass

Re-applied `task-test-review` (well-formed ∧ behaviour-coverage(∀b∈design.∃t) ∧
infra-hygiene(injectable ∧ nullable ∧ ¬mock ∧ ¬stub)). Suites green:
`tool-result-at-most-once` 5/19, `prompt-request` 17/49. Infra deps clean — real
`session/create-context` + in-memory persistence (`:persist? false`) + real
agent-core via `agent/emit-tool-start-in!`; no mocks/stubs, state/output
assertions only. First-pass first-occurrence-wins follow-up confirmed resolved.

New actionable (1) — see steps.md "Test review follow-ups (second pass)":
- **No handler-layer test records two *distinct* tool-call-ids in one session, so
  the at-most-once-PER-tool-call-id granularity is unlocked at the `:state*`
  guard.** Every handler-layer test (`abort-races…`,
  `recorded-ids-survive-turn-boundary…`, `normal-single-result…`,
  `interrupt-only…`, `concurrent-completion…`) uses exactly one id per session
  (`tc-abort-race`, `tc-cross-turn`, `tc-normal`, `tc-interrupt-only`,
  `tc-concurrent`). The guard is `(contains? recorded-ids tool-call-id)` /
  `(conj recorded-ids tool-call-id)` — keyed per id — but a regression that made
  it per-**session** (e.g. a single boolean "a result was recorded" flag instead
  of the per-id set) would suppress every tool result after the *first* distinct
  call in a session — a severe normal-path break (every multi-tool turn loses all
  but the first result) — yet pass the whole suite: no test dispatches a real
  result for a second distinct id in the same session and asserts it is still
  recorded. The only two-distinct-id coverage is at the projection layer
  (`dedupe-tool-results`, `journal-duplicate-tool-results-project-to-one-test`
  uses `id-noncontig`/`id-contig`), **not** the `:state*` handler guard. This is
  the symmetric gap to the cross-turn lifetime test already added (2nd impl-review
  pass): per-id keying is as load-bearing as session-scoped lifetime. Add a
  handler-layer test that records real results for two distinct tool-call-ids in
  one session and asserts both are recorded (each →1, no cross-suppression); a
  per-session guard would fail it.

## Test review follow-ups (second pass) — executed

Added `distinct-tool-call-ids-both-recorded-test` to
`tool_result_at_most_once_test.clj`. Records real results for two distinct
tool-call-ids (`tc-distinct-a`, `tc-distinct-b`) in one session via
`record-result!` and asserts each id records exactly one `toolResult` at the raw
recorded layer — journal (`journal-tool-results`) and agent-core in-memory
history (`memory-tool-results`) — with no cross-id suppression (each →1, tool-name
`"bash"`). Locks per-tool-call-id granularity of the `:state*` recorded-ids
guard: a per-session boolean "recorded" flag (suppress-after-first) would suppress
`id-b` and fail its count-1 assertions, while the actual per-id `contains?`/`conj`
guard records both. Symmetric to the cross-turn lifetime lock already present
(`recorded-ids-survive-turn-boundary-test`). Focused suite green (6 tests / 25
assertions, scry CLI `-n` RC=0); clj-kondo clean, parens balanced. Test-only
change; no production code touched, no doc/changelog impact.

## Test review (task-test-review) — third pass

Re-applied `task-test-review` criteria: well-formed ∧ behaviour-coverage
(∀b∈design.∃t.covers) ∧ infra-hygiene (injectable ∧ nullable ∧ ¬mock ∧ ¬stub).
Suites green: `tool-result-at-most-once` 6/25, `prompt-request` 17/49.

- **Well-formed.** All tests isolated (`safe-context-opts {:persist? false}`),
  clear arrange/act/assert, descriptive names + `testing` strings; direct-dispatch
  vs `abort-in!` seams documented inline where load-bearing.
- **Infra hygiene clean.** Real `session/create-context` + in-memory persistence,
  real agent-core via `agent/emit-tool-start-in!`/`abort-in!`/`end-loop-in!`;
  state/output assertions only (journal counts, in-memory message counts, winning
  tool-name) — no mocks, stubs, or interaction assertions.
- **Behaviour coverage (all design behaviours mapped).** headline interrupt-wins
  (`abort-races…`); both-or-neither (journal ∧ memory asserted together, divergence
  caught); normal single-result (`normal-single-result…`); interrupt-only
  (`interrupt-only…`); concurrent-completion real-wins (`concurrent-completion…`,
  design-pinned direct-dispatch seam); cross-turn session-scoped lifetime
  (`recorded-ids-survive-turn-boundary…`); per-tool-call-id granularity
  (`distinct-tool-call-ids-both-recorded…`); already-wedged projection recovery
  contiguous+non-contiguous + first-occurrence-wins
  (`journal-duplicate-tool-results-project-to-one…`).

Candidate gaps examined and discharged (no new actionable):
- **Per-producer coverage** (statechart-effect `:deferred-interrupt`,
  session-close): design's funnel property makes producer enumeration explicitly
  *not load-bearing* — all producers funnel through the single
  `:session/tool-agent-record-result` event, whose chokepoint suppression is
  directly locked by `concurrent-completion…`. Per-producer tests would test the
  non-load-bearing enumeration, not new behaviour.
- **"any tool" (delegate/psi-tool)**: guard is keyed by tool-call-id and
  tool-name-agnostic; bash exercises the mechanism. Extra tool-names = redundant,
  low-signal.
- **Non-`toolResult` message preservation through `dedupe-tool-results`**: already
  covered by `journal->provider-messages-repairs-dangling-tool-use-test`
  (`(= ["assistant" "toolResult" "user"] (mapv :role messages))`) which runs the
  full projection including the new de-dup.
- **Async re-surfacing / background delivery**: explicitly out of scope.

Verdict: REVIEW_COMPLETE — three skill criteria satisfied; the two prior passes
resolved the genuine gaps (first-occurrence-wins, per-tool-call-id granularity).
No new actionable feedback.

## Test shaper review (test-shaper) — first pass

Applied `test-shaper` (clarity ∧ signal ∧ robustness ∧ economy ∧ consistency),
distinct from the prior `task-test-review` passes (which covered behaviour
coverage + infra hygiene).

Strong as-is:
- **simple / single-concern / minimal setup** — every test one behaviour, real
  `create-context` + in-memory persistence, real agent-core seams
  (`emit-tool-start-in!`/`abort-in!`/`end-loop-in!`), state-based assertions only.
- **robust both-or-neither** — count asserted on **both** journal and in-memory
  layers in the headline/cross-turn tests, so a one-sided suppression regression
  (journal≠memory) fails. No flakiness (sequential, no I/O/concurrency; the
  unasserted `Instant/now` timestamps don't influence outcomes).
- **economy** — `recorded-ids-survive-turn-boundary` (cross-turn lifetime) and
  `distinct-tool-call-ids-both-recorded` (per-id granularity) are not redundant
  with the headline test; each locks a distinct load-bearing property.
- projection test genuinely distinguishes de-dup-after-repair (non-contiguous
  count=1) and first-occurrence-wins.

New actionable (1) — see steps.md "Test shaper review follow-ups (first pass)":
- **Inconsistent assertion style across the at-most-once suite** (`consistent(
  assertion_style)` ∧ `meaningful_failures`). Failure messages are present on the
  count assertions in `abort-races…`, `recorded-ids-survive-turn-boundary…`,
  `concurrent-completion…` (first only), and `distinct…`, but absent in
  `normal-single-result-path-unaffected…` and `interrupt-only-path-yields-one-
  result…` and on the second count assertion of `concurrent-completion…`.
  Symmetry is also uneven: `normal-single-result…` / `interrupt-only…` assert the
  winning `:tool-name` only on `journal`, while the headline/cross-turn/concurrent
  tests assert it on **both** journal and memory. Normalize: give the count/tool-
  name assertions consistent failure messages and the same journal+memory
  symmetry across the suite, so a failing count/winner reports which layer and
  which expectation. Low-priority consistency polish; no behaviour gap.

## Test shaper follow-up (first pass) — executed

Applied both test-shaper follow-ups to `tool_result_at_most_once_test.clj`
(test-only; no production change, no docs/changelog):

- **Assertion-style normalization** — added layer-naming failure messages to the
  previously-bare `count` assertions in `normal-single-result-path-unaffected-
  test`, `interrupt-only-path-yields-one-result-test`, and the second `count`
  assertion of `concurrent-completion-real-result-wins-test` ("…in the journal" /
  "…in the in-memory history"). All `count` assertions in the suite now report
  which recorded layer diverged on failure.
- **Journal/memory winner symmetry** — `normal-single-result-path-unaffected-
  test` (`"bash"`) and `interrupt-only-path-yields-one-result-test`
  (`"interrupted"`) now assert the winning `:tool-name` on **both** `journal` and
  `memory`, matching the headline/cross-turn/concurrent tests' both-or-neither
  winner check.

Verify: focused suite green (6 tests / 27 assertions, up from 25 — the two added
memory winner assertions); clj-kondo clean; clj-paren-repair balanced.

## Test shaper review (test-shaper) — second pass

Re-read `tool_result_at_most_once_test.clj` + `prompt_request_test.clj` against
test-shaper (`consistent` ∧ `meaningful_failures`). The first-pass normalization
left two residual asymmetries — both test-only, no behaviour gap.

New actionable (2) — see steps.md "Test shaper review follow-ups (second pass)":

1. **Winner-symmetry regression in `distinct-tool-call-ids-both-recorded-test`**
   (`consistent(assertion_style)`). The first pass established a both-layer
   winner check (assert `:tool-name` on **journal and memory**) for the
   headline/cross-turn/concurrent/normal/interrupt tests, but
   `distinct-tool-call-ids-both-recorded-test` (added after, in test-review 2nd
   pass) asserts the winning `:tool-name` only on `journal-a`/`journal-b`, never
   on `memory-a`/`memory-b` — even though it already binds both. It breaks the
   suite-wide both-or-neither winner symmetry. Add the two memory-layer
   `:tool-name` assertions.

2. **Winner `:tool-name` assertions are message-less suite-wide**
   (`meaningful_failures` ∧ `consistent(assertion_style)`). The first pass added
   layer-naming failure messages to every `count` assertion, but the `:tool-name`
   winner assertions across all tests in `tool_result_at_most_once_test.clj`
   remain bare. The suite is now internally inconsistent: count assertions report
   which layer diverged, winner assertions do not. Add layer-naming failure
   messages to the winner `:tool-name` assertions so a winner mismatch also
   reports journal-vs-memory. Low priority.

## Test shaper review (second pass) follow-up — executed

Executed both second-pass test-shaper items in
`tool_result_at_most_once_test.clj`:

1. **Winner-symmetry restored in `distinct-tool-call-ids-both-recorded-test`.**
   Added `(is (= "bash" (:tool-name (first memory-a))) …)` and the `memory-b`
   equivalent (bindings already present), so the test asserts the winning
   `:tool-name` on **both** journal and memory layers, matching the suite-wide
   both-layer winner check.
2. **Layer-naming messages on all winner `:tool-name` assertions.** Every winner
   assertion across the suite now carries a journal/memory layer message
   ("the interrupt wins on the journal layer" / "…on the in-memory history
   layer"; "the real result wins on the … layer"), matching the layer-naming the
   first pass applied to the `count` assertions. Assertion style is now uniform.

Verification: focused suite green (6 tests / 29 assertions, up from 27 — the two
new memory-layer winner assertions); clj-kondo clean; parens balanced.

## Test shaper review (third pass)

Independent test-shaper pass on `tool_result_at_most_once_test.clj` and the
projection test `journal-duplicate-tool-results-project-to-one-test`
(`prompt_request_test.clj`). Prior two passes (assertion messages, both-layer
winner symmetry) are resolved and present. The projection test is well-shaped
(first-occurrence-wins content locked, non-contiguous case included). Two new
actionable items, both in `tool_result_at_most_once_test.clj` (see steps.md):

1. **Repeated both-layer count+winner assertion ceremony** (`economical` ∧
   `consistent(assertion_style)` ∧ robust). Five of the six handler-layer tests
   repeat the identical four-assertion block (count=1 on `journal` + `memory`,
   winner `:tool-name` on `journal` + `memory`, each layer-named). This is the
   exact ceremony the prior two test-shaper passes had to repair *per test by
   hand* (pass 1: missing messages; pass 2: a test missing the memory winner
   assertion) — i.e. the duplication is the source of the divergence drift.
   Extract a shared `assert-single-recorded-result` helper (winner name passed
   at the call site so intent stays visible; helper keeps the layer-naming
   messages) → compresses ceremony without hiding intent, makes the both-layer
   invariant uniform and drift-resistant.

2. **Uncontrolled time in message builders** (`deterministic(control(time))` ∧
   `minimal_incidental_setup`). `real-result-msg`/`interrupt-result-msg` stamp
   `:timestamp (java.time.Instant/now)` — incidental wall-clock time, never
   asserted, no de-dup/ordering keys off it. Use a fixed instant. Low priority.
