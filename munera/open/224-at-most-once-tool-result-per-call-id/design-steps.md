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

- [ ] Resolve the **defensive projection de-dup scope** inconsistency. Desired
      Behaviour states it as a "must", Scope omits it, Open Question 2 calls it
      open. Pick one: in-scope (move the requirement into Scope) or deferred to a
      follow-up task (downgrade the Desired-Behaviour "must" to a non-goal /
      reference), and make all three sections agree.
- [ ] Disambiguate **how the late real result is prevented from producing a
      second `tool_result`**. Reconcile Desired Behaviour ("delivered through the
      async/background completion path, not as a second `tool_result`") with Root
      Cause step 3 + D1 Mechanism (real result still dispatches
      `:session/tool-agent-record-result` and is suppressed by the recorded-ids
      guard). Code confirms the adapter `:record-result!` re-dispatches the
      event. State explicitly whether the guard suppresses the re-dispatched real
      result, the real result is rerouted away from the record handler, or both.
- [ ] Specify the **recorded-ids reset/persistence boundary** in design.md (not
      only plan.md), because it determines whether the invariant holds for the
      headline cross-turn race. An aborted tool's real result arrives after the
      turn that recorded the interrupt; if recorded-ids resets at the turn
      boundary the late result is no longer suppressed. State the required
      lifetime (id persists until the late result is resolved) or the assumption
      that the late result never re-dispatches the record event (link to the
      item above).
- [ ] Reconcile **"first-writer-wins" (nondeterministic by dispatch order)**
      with **"an aborted async tool keeps its `interrupted` result"
      (deterministic)**. State whether the interrupt result is guaranteed to be
      recorded first (and how) or whether the model-visible outcome is genuinely
      whichever producer races first. Fold Open Question 3's "confirm intended
      behaviour" into this resolution.
- [ ] Specify the **fate of a suppressed real result for synchronous tools**
      (`bash`, `psi-tool`), which appear in the Root Cause evidence but may have
      no async/background completion path. State whether their real result is
      silently dropped on abort and whether that is the intended model-visible
      behaviour.
- [ ] Renumber the **"Remaining Open Question"** list (currently starts at 2 with
      no item 1) so it no longer implies a missing item 1; the guard-location
      question is resolved in D1.
