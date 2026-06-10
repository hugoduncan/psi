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
