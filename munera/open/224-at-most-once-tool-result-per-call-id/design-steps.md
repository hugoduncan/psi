# Design follow-up — architecture-fit review

- [x] Resolve Design Question 1 (guard location B vs C) **explicitly in
      design.md** rather than leaving a lean. Judge B against doc/architecture.md
      "State boundary: canonical root vs runtime handles" — `:pending-tool-calls`
      is an agent-core runtime handle, not canonical `:state*`. If choosing B,
      record it as a deliberate, documented deviation from the project's
      "project queryable status into canonical state through dispatch" direction
      (deviation requires a documented design decision).
      → Resolved: chose **Option (C)** (canonical recorded-ids predicate in
      `:state*`); Option (B) rejected. See design.md "Design Decisions → D1".
- [x] Reconcile the guard mechanism with the Dispatch sequencing contract
      (pure handler result → apply → effects last). A test-and-set against
      `:pending-tool-calls` to gate effect emission is a state mutation deciding
      effects, not pure-result + effects-as-data. Specify how the design keeps
      effect emission (in-memory record + journal append) as a pure both-or-
      neither decision — e.g. a canonical at-most-once predicate (recorded
      tool-result ids / pending set) readable purely in
      `:session/tool-agent-record-result`.
      → Resolved: handler reads canonical recorded-ids, returns
      `:root-state-update` adding the id, emits both effects or neither; atomicity
      from dispatch serialization, not a runtime test-and-set. See D1 "Mechanism".
- [x] Address cross-component layering: the journal append is an agent-session
      effect while `:pending-tool-calls` ownership is in agent-core. Specify that
      the agent-session pure dispatch handler owns the applied?/effects decision
      (from canonical state agent-core projects), so a lower component does not
      gate a higher component's effect.
      → Resolved: agent-session pure handler owns the applied?/effects decision
      reading canonical `:state*`; `:pending-tool-calls` retained for interrupt
      enumeration only, no longer gates effects. See D1 rationale + Mechanism.

# Design follow-up — ambiguity review

- [x] Resolve the **defensive projection de-dup scope** inconsistency. Desired
      Behaviour states it as a "must", Scope omits it, Open Question 2 calls it
      open. Pick one: in-scope (move the requirement into Scope) or deferred to a
      follow-up task (downgrade the Desired-Behaviour "must" to a non-goal /
      reference), and make all three sections agree.
      → Resolved: chose **in scope**. Added a Scope in-scope bullet + acceptance
      criterion; rewrote the Desired-Behaviour bullet ("This defensive de-dup is
      in scope"); resolved Open Question 2. All three sections now agree.
- [x] Disambiguate **how the late real result is prevented from producing a
      second `tool_result`**. Reconcile Desired Behaviour ("delivered through the
      async/background completion path, not as a second `tool_result`") with Root
      Cause step 3 + D1 Mechanism (real result still dispatches
      `:session/tool-agent-record-result` and is suppressed by the recorded-ids
      guard). Code confirms the adapter `:record-result!` re-dispatches the
      event. State explicitly whether the guard suppresses the re-dispatched real
      result, the real result is rerouted away from the record handler, or both.
      → Resolved: **the recorded-ids guard suppresses the re-dispatched real
      result** (it still dispatches the event; the handler suppresses both its
      in-memory record and journal append). The background/async path is an
      orthogonal content-delivery mechanism (later turn), not the suppressor and
      not a `tool_result`. Stated in the rewritten Desired-Behaviour bullet.
- [x] Specify the **recorded-ids reset/persistence boundary** in design.md (not
      only plan.md), because it determines whether the invariant holds for the
      headline cross-turn race. An aborted tool's real result arrives after the
      turn that recorded the interrupt; if recorded-ids resets at the turn
      boundary the late result is no longer suppressed. State the required
      lifetime (id persists until the late result is resolved) or the assumption
      that the late result never re-dispatches the record event (link to the
      item above).
      → Resolved: recorded-ids **persists for the session lifetime**, cleared
      only on session reset/clear — explicitly **not** at the per-turn boundary
      and **decoupled** from `:pending-tool-calls` lifetime. New D1 Mechanism
      "Persistence/reset boundary" bullet replaces the prior plan-time defer.
- [x] Reconcile **"first-writer-wins" (nondeterministic by dispatch order)**
      with **"an aborted async tool keeps its `interrupted` result"
      (deterministic)**. State whether the interrupt result is guaranteed to be
      recorded first (and how) or whether the model-visible outcome is genuinely
      whichever producer races first. Fold Open Question 3's "confirm intended
      behaviour" into this resolution.
      → Resolved: first-writer-wins is the general invariant mechanism, but for
      the headline abort race interrupt-first is **deterministic**: the interrupt
      path enumerates only *still-pending* ids and records them synchronously at
      abort, so a still-in-flight tool's real result can only arrive after and is
      suppressed. New Desired-Behaviour determinism bullet + D1 Mechanism bullet;
      Open Question 3 resolved.
- [x] Specify the **fate of a suppressed real result for synchronous tools**
      (`bash`, `psi-tool`), which appear in the Root Cause evidence but may have
      no async/background completion path. State whether their real result is
      silently dropped on abort and whether that is the intended model-visible
      behaviour.
      → Resolved: synchronous tools have no background path; their suppressed
      real result is **silently dropped** on abort, which is the intended
      model-visible behaviour (the user aborted). New Desired-Behaviour
      sync-tool bullet.
- [x] Renumber the **"Remaining Open Question"** list (currently starts at 2 with
      no item 1) so it no longer implies a missing item 1; the guard-location
      question is resolved in D1.
      → Resolved: replaced "Remaining Open Question" with a "Resolved Questions"
      section (items 1–3, no gap); all questions now resolved, none open.

# Design follow-up — inconsistency review

- [x] Reconcile the **two interrupt-result producer citations**. Root Cause
      step 2 + Evidence attribute the interrupt to `:on-agent-done`
      (`statechart_actions.clj:129/149`) → `:runtime/record-pending-tool-call-interrupts`
      effect (`dispatch_effects.clj:127`), while Desired Behaviour + D1 Mechanism
      attribute it to `turn.clj:223 record-pending-tool-call-interrupts!`
      (`abort-in!`). The code has **both** distinct producers, each enumerating
      `:pending-tool-calls` and dispatching `:session/tool-agent-record-result`
      with an `"interrupted"` toolResult. State explicitly that there are two
      interrupt producers, that the single-chokepoint guard at
      `:session/tool-agent-record-result` covers both, and fix D1's determinism
      wording ("recorded **synchronously at abort time**, `turn.clj`") so it
      grounds in the actual reproduced `:user-abort` path (which can run through
      the `:on-agent-done` effect, not only the literal synchronous `abort-in!`
      call). Also update D1's "`:pending-tool-calls` retained for enumeration
      only (`turn.clj:220`)" to note the second enumeration site
      (`dispatch_effects.clj`).
      → Resolved: Root Cause step 2 rewritten to state **two distinct interrupt
      producers** — statechart-effect path (`:on-agent-done`
      `statechart_actions.clj:129/149` → `:runtime/record-pending-tool-call-interrupts`
      → `dispatch_effects.clj:127`) and synchronous abort path (`abort-in!`
      `turn.clj:233` → `record-pending-tool-call-interrupts!` `turn.clj:217`) —
      both enumerating `:pending-tool-calls` and converging on the single event
      `:session/tool-agent-record-result` (chokepoint covers both); the
      reproduced `:user-abort` can flow through the effect path, not only the
      literal synchronous call. Fixed D1 + Desired-Behaviour determinism wording
      (no longer "synchronously at abort time, `turn.clj`"; now "either producer
      records still-pending interrupts before the real-result re-dispatch"), and
      D1's retention bullet now cites **both** enumeration sites (`turn.clj:220`
      and `dispatch_effects.clj:131`). Code verified before editing.

# Design follow-up — ambiguity review (second pass)

- [x] Disambiguate the **defensive projection de-dup location and keying
      source**. Scope + Desired Behaviour require "at most one `tool_result` per
      tool-call-id" across two named sites — `journal->provider-messages`
      (`prompt_request.clj`, journal-derived) **and** the conversation rebuild
      (`turn_runtime/conversation.clj` `agent-messages->ai-conversation`, derived
      from agent-core in-memory history) — and qualify it "purely derived from
      the journal, first occurrence wins". Two interpretations remain open:
      (a) implement the guard independently at **both** sites, or (b) one shared
      downstream chokepoint suffices (note: code shows the conversation rebuild,
      not `journal->provider-messages`, is what emits provider `tool_result`
      blocks via `conv/add-tool-result`). Also reconcile "purely derived from the
      journal" with the conversation-rebuild site, whose input is in-memory
      agent-core history, not the journal — state the de-dup keying source for
      each site. (Distinct from resolved item 1, which fixed only the
      in/out-of-scope question.)
      → Resolved: **interpretation (b), single upstream chokepoint at
      `journal->provider-messages`.** Verified the production provider-request
      pipeline in code: journal → `session->provider-messages` /
      `journal->provider-messages` (`prompt_request.clj:111`, emits
      `toolResult`-role message maps) → `agent-messages->ai-conversation`
      (`conversation.clj:136`, the only production emitter of provider
      `tool_result` blocks, one per `toolResult` message via `conv/add-tool-result`
      `conversation.clj:95`). The rebuild's only production caller
      (`build-provider-conversation` `request.clj:54/60`) reads `:turn/messages`
      (`prompt_request.clj:296`), which is the **journal-derived** list — so the
      rebuild consumes `journal->provider-messages`' output, it is not fed a
      separate in-memory history in this path. Because the mapping is one
      `toolResult` message → one block, de-duping `toolResult` messages once
      upstream removes the duplicate before it can become a second block; no
      independent guard at the rebuild. **Keying source: the journal**, by
      `:tool-call-id`, first occurrence wins — so "purely derived from the
      journal" is now accurate (the guard lives at the journal projection; the
      rebuild has no separate keying source). Updated design.md Desired Behaviour
      (rewrote the final journal-tolerance bullet + added two location/keying
      bullets), Scope in-scope bullet, Acceptance criterion, and Resolved
      Question 2. Distinct from resolved item 1.

# Design follow-up — inconsistency review (second pass)

- [x] Fix the **Root Cause "Result:" line** that attributes provider
      `tool_result` *block* emission to `journal->provider-messages`. The closing
      "Result:" arrow ("two journal `toolResult` entries with one tool-call-id →
      `journal->provider-messages` → two `tool_result` blocks for one `tool_use`")
      contradicts (a) Root Cause step 4, which says the **conversation rebuild**
      (`turn-runtime/conversation.clj`) emits one `tool_result` block per
      `toolResult` message, and (b) the Desired-Behaviour De-dup Location bullet,
      which states `journal->provider-messages` emits only `toolResult`-role
      *message maps* and "the rebuild is the only place provider `tool_result`
      blocks are emitted" (code-confirmed: `conversation.clj:95`
      `conv/add-tool-result`). Rewrite the arrow to route through the rebuild,
      e.g. "two journal `toolResult` entries → `journal->provider-messages` (two
      duplicate message maps) → conversation rebuild → two `tool_result` blocks
      for one `tool_use` → provider 400", so Root Cause, step 4, and the De-dup
      Location bullet agree on which projection emits the blocks. (Distinct from
      the resolved second-pass ambiguity item, which fixed the de-dup
      location/keying bullets but left this Root Cause arrow uncorrected.)
      → Resolved: rewrote the Root Cause "Result:" arrow to route block emission
      through the conversation rebuild — "two journal `toolResult` entries →
      `journal->provider-messages` (two duplicate `toolResult`-role *message
      maps*) → conversation rebuild (`agent-messages->ai-conversation`, one
      `tool_result` *block* per message via `conv/add-tool-result`
      `conversation.clj:95`) → two `tool_result` blocks → provider 400" — and
      added an explicit note that the block-emitting projection is the rebuild,
      not `journal->provider-messages`. Root Cause now agrees with step 4 and the
      De-dup Location bullet. Code verified: `journal->provider-messages`
      (`prompt_request.clj:111`) emits provider message maps;
      `append-tool-result-msg` → `conv/add-tool-result` (`conversation.clj:95`)
      is the sole block emitter, one per `toolResult` message.

# Design follow-up — ambiguity review (third pass)

- [x] Disambiguate whether the at-most-once guarantee **depends on an exhaustive
      producer enumeration** or only on the **general funnel property** (every
      producer dispatches the single event `:session/tool-agent-record-result`,
      so the chokepoint guard covers any producer regardless of how many exist).
      Root Cause step 2 ("There are **two distinct interrupt producers**") and
      D1's atomicity bullet ("the **two interrupt producers** … and the
      **real-result path** … The single chokepoint covers all three producers")
      read as an exhaustive count, while the same text also asserts the general
      funnel property — a reader cannot tell which the invariant relies on. The
      enumeration is in fact **incomplete**: code has **three** interrupt
      producers (four dispatch sites total), not two —
      `dispatch_effects.clj:127/134` (statechart-effect path),
      `turn.clj:217/223` (synchronous abort path), and the omitted
      **`session_close.clj:55/61` `repair-pending-tool-calls-before-close!`**
      (session-close path, called by `close-session-in!`), all enumerating
      `:pending-tool-calls` and dispatching the `"interrupted"` toolResult, plus
      the real-result path `tool_runtime_adapter.clj:114`; all converge on the
      handler `session_mutations.clj:529`. Resolve by either (a) stating the
      guarantee rests on the funnel property and dropping the exhaustive-count
      language ("two interrupt producers" / "covers all three producers"), or
      (b) completing the enumeration to include the session-close producer.
      Also acknowledge that the close path can itself produce a duplicate for one
      pending id (abort-in!'s interrupt at `turn.clj:223` plus
      `repair-pending-tool-calls-before-close!` both enumerate the same
      `:pending-tool-calls` during close), which the recorded-ids guard
      suppresses but the design never mentions.
      → Resolved: chose **(a) — the guarantee rests on the funnel property**
      (every producer dispatches the single event
      `:session/tool-agent-record-result`; the chokepoint guard covers any
      producer regardless of count), and **also completed the enumeration** for
      accuracy. Root Cause step 2 now lists three interrupt producers
      (statechart-effect `dispatch_effects.clj:127/131`, synchronous abort
      `turn.clj:217/220/233`, session-close
      `repair-pending-tool-calls-before-close!` `session_close.clj:55/58/61/106`)
      under an explicit **"Funnel property (load-bearing)"** heading stating the
      list is illustrative, not exhaustive. D1's atomicity bullet drops the
      "two interrupt producers"/"covers all three producers" exhaustive-count
      language in favour of the funnel framing (known producers listed
      illustratively, invariant does not depend on completeness). D1's
      `:pending-tool-calls` retention bullet now cites all three enumeration
      sites (`turn.clj:220`, `dispatch_effects.clj:131`, `session_close.clj:58`).
      Duplicate-across-producers (incl. session-close re-enumerating an
      already-recorded id) is explicitly acknowledged as suppressed by the
      recorded-ids guard. Code verified, including that session-close's
      `agent/abort-in!` (agent-core) *clears* `:pending-tool-calls` rather than
      recording — so the review's specific "abort-in! `turn.clj:223` + repair
      both enumerate during close" duplicate mechanism is inaccurate; the funnel
      framing makes the precise interaction non-load-bearing.

# Design follow-up — inconsistency review (third pass)

- [x] Fix the design's claim that the reproduced `:user-abort` evidence can flow
      through the **statechart-effect interrupt producer**; code shows it cannot.
      `:on-agent-done` (`statechart_actions.clj:132`) reads session-data
      `:interrupt-reason`, whose **only** writer is the `:session/request-interrupt`
      handler (`session_mutations.clj:638`, `(or reason :deferred-interrupt)`)
      and whose **only** dispatcher passes `:reason :deferred-interrupt`
      (`turn.clj:189/193`). `:user-abort` appears **only** at `turn.clj:233` as
      the interrupt *message* reason on the synchronous `abort-in!` inline path
      and is **never** written to `:interrupt-reason` (no code assigns
      `:interrupt-reason :user-abort`; schema enum `:session-close`/`:context-shutdown`
      are also unwired). So the statechart-effect producer
      (`:on-agent-done` → `:runtime/record-pending-tool-call-interrupts`,
      `dispatch_effects.clj:127`) fires only for `:deferred-interrupt`, never for
      `:user-abort`. Correct all three contradicting sites:
      (a) Root Cause step 2 statechart-effect bullet — change the example
      "sees `:interrupt-reason` (e.g. `:user-abort`)" to `:deferred-interrupt`;
      (b) the Funnel-property paragraph — remove/rewrite "The reproduced
      `:user-abort` Evidence below can flow through the statechart-effect
      producer ... not only the literal synchronous `abort-in!` call" (false);
      state the `:user-abort` evidence flows exclusively through the synchronous
      path, and the statechart-effect path is the producer for the distinct
      `:deferred-interrupt` race;
      (c) the Desired-Behaviour and D1 Mechanism determinism bullets — stop
      attributing the headline `:user-abort` race to "either producer
      (statechart-effect ... or synchronous `abort-in!`)"; for `:user-abort` the
      first writer is deterministically the synchronous inline recording in
      `abort-in!`. Keep the funnel property (general invariant) intact — it is
      unaffected — but the evidence/determinism attribution must distinguish
      `:user-abort` ⇒ synchronous path vs `:deferred-interrupt` ⇒
      statechart-effect path. (Introduced by the first-pass inconsistency
      resolution, which incorrectly asserted the reproduced `:user-abort` "can
      run through the `:on-agent-done` effect".)
      → Resolved: corrected all three contradicting sites; the `:user-abort`
      evidence flows **exclusively** through the synchronous `abort-in!` path,
      the statechart-effect producer fires only for `:deferred-interrupt`.
      Code verified: `:interrupt-reason` is written only by
      `:session/request-interrupt` (`session_mutations.clj:638`,
      `(or reason :deferred-interrupt)`), whose sole dispatcher (`turn.clj:189/193`)
      passes `:deferred-interrupt`; `:user-abort` (`turn.clj:233`) is only a
      message reason and is never written to `:interrupt-reason`. (a) Root Cause
      statechart bullet now says "non-nil `:interrupt-reason` (only ever
      `:deferred-interrupt`)"; (b) added a "Which producer fires for which
      reason" paragraph stating `:user-abort` ⇒ synchronous, `:deferred-interrupt`
      ⇒ statechart-effect, funnel unaffected; (c) Desired-Behaviour and D1
      Mechanism determinism bullets now attribute the headline `:user-abort` race's
      first writer deterministically to the synchronous inline `abort-in!`
      recording. Funnel property (general invariant) left intact.

# Design follow-up — ambiguity review (fourth pass)

- [x] Disambiguate the **headline-race determinism claim**, which conflates
      "still pending at abort (enumeration)" with "the interrupt's record-event
      is dispatched before the real result's". Desired Behaviour ("Interrupt-first
      is guaranteed for the headline abort race, not left to dispatch
      tie-breaking") and D1 Mechanism assert the interrupt is "deterministically
      the first writer for any tool that was still pending at abort", justified by
      "a real result for a still-in-flight tool necessarily arrives *after* the
      abort that enumerated it as pending." But `:pending-tool-calls` is cleared
      only inside `record-tool-result-in!` (`agent_core/core.clj:407`, `disj`),
      which runs via the **effect** `:runtime/agent-record-tool-result`
      (`dispatch_effects.clj:125`) — strictly *after* the
      `:session/tool-agent-record-result` handler returns and applies. So a real
      result's record-event can be **enqueued first** while its id is **still in**
      `:pending-tool-calls` (clearing effect not yet run); abort
      (`turn.clj:217/220/233`) then enumerates that id as pending and dispatches
      the interrupt *after* the real-result record event, and dispatch
      serialization keeps the **real** result (suppressing the interrupt) — the
      opposite of the claimed "interrupt is the one kept." The design's
      parenthetical exclusion ("the real result could win only if it … recorded
      *before* the abort enumerated it … no longer pending") is keyed to the
      clearing effect having run, not to record-event enqueue order, so this
      enqueued-but-not-yet-cleared window is unaddressed. "Arrives after" /
      "recorded before" / "still pending at abort" admit two readings
      (dispatch-enqueue order vs effect-apply/pending-clear order) that yield
      opposite model-visible winners. The at-most-once invariant is unaffected
      (still exactly one result); only the asserted determinism of *which* result
      the model sees is at issue. Resolve by either (a) restating the
      headline-race guarantee as "at most one result, first-writer-wins by
      dispatch order" and dropping the stronger "interrupt-first is deterministic
      for any tool pending at abort" claim, or (b) defining "still pending at
      abort" precisely in dispatch-enqueue terms and justifying why a real
      result's record-event cannot already be enqueued ahead of the abort's
      interrupt dispatch for an id abort still sees as pending.
      → Resolved: chose **(a)** — the deterministic guarantee is **at-most-once**
      (exactly one result per id); the model-visible winner is
      **first-writer-wins by dispatch order**. Dropped the over-strong
      "interrupt-first is deterministic for any tool pending at abort" claim.
      Code-verified the window: `:pending-tool-calls` is cleared only inside the
      **effect** `:runtime/agent-record-tool-result` → `record-tool-result-in!`
      (`agent_core/core.clj:407` `disj`, via `dispatch_effects.clj:125`), strictly
      after the real result's `:session/tool-agent-record-result` handler applies
      and adds the id to recorded-ids; `record-pending-tool-call-interrupts!`
      (`turn.clj:217/220`) reads `:pending-tool-calls` synchronously. So in a
      concurrent-completion window a real-result record-event can be serialized
      first while the id is still enumerable as pending — abort then dispatches an
      interrupt that is suppressed and the **real** result wins (acceptable: a
      real result is valid, still exactly one result). Distinguished "still
      pending" (apply-state) from dispatch-enqueue order. Rewrote three sites:
      Desired-Behaviour determinism bullet (now "deterministic guarantee is
      at-most-once" with typical-headline-case / concurrent-window /
      no-regression sub-bullets), D1 Mechanism determinism bullet, and Resolved
      Question 3. At-most-once invariant explicitly unaffected.

# Design follow-up — ambiguity review (fifth pass)

- [x] Align the Desired-Behaviour bullet "An aborted, still-in-flight tool keeps
      its `"interrupted"` result for the model-visible turn" (design.md:118) with
      the first-writer-wins / concurrent-window framing the fourth pass installed.
      As written it reads as an **unconditional** interrupt-wins claim, whereas
      the later determinism bullet (:130+), D1 Mechanism (:314+), and Resolved
      Question 3 (:376+) say the model-visible winner is **first-writer-wins by
      dispatch order** and that in the concurrent-completion window the **real**
      result may win — those three sites were rewritten to qualify "**genuinely**
      still in-flight (real result not yet produced/dispatched)", but :118 still
      uses the bare phrase "still-in-flight," which admits two opposite readings
      (any id pending at abort ⇒ interrupt kept, vs the narrow "genuinely
      in-flight" reading that excludes the concurrent window). Reading (a) is
      literally false in the concurrent window (model sees the real result).
      Resolve by either qualifying :118 to "genuinely still in-flight — real
      result not yet produced/dispatched" with a cross-reference to the
      determinism bullet, or restating it as "keeps its interrupted result in the
      typical headline case; in the concurrent-completion window the real result
      may win (still exactly one result)". Distinct from the resolved fourth-pass
      item, which rewrote the determinism bullet / D1 / Resolved-Q3 but never
      touched :118.
      → Resolved: qualified :118 to "An aborted, **genuinely still in-flight**
      tool — one whose real result has **not yet been produced or dispatched** at
      abort time — keeps its `"interrupted"` result," matching the narrow phrasing
      the fourth pass installed at the determinism bullet (:130+), D1 Mechanism
      (:314+), and Resolved Question 3 (:376+). Added an inline qualifier that
      this is the typical headline case and that in the concurrent-completion
      window the real result may win (still exactly one result), with a
      cross-reference to those three sites. Removes the false reading (a) (any id
      pending at abort deterministically keeps its interrupt result); all four
      sites now use the same "genuinely still in-flight" framing.
