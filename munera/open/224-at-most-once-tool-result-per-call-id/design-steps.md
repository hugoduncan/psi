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
