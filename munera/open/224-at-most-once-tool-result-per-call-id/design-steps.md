# Design follow-up — architecture-fit review

- [ ] Resolve Design Question 1 (guard location B vs C) **explicitly in
      design.md** rather than leaving a lean. Judge B against doc/architecture.md
      "State boundary: canonical root vs runtime handles" — `:pending-tool-calls`
      is an agent-core runtime handle, not canonical `:state*`. If choosing B,
      record it as a deliberate, documented deviation from the project's
      "project queryable status into canonical state through dispatch" direction
      (deviation requires a documented design decision).
- [ ] Reconcile the guard mechanism with the Dispatch sequencing contract
      (pure handler result → apply → effects last). A test-and-set against
      `:pending-tool-calls` to gate effect emission is a state mutation deciding
      effects, not pure-result + effects-as-data. Specify how the design keeps
      effect emission (in-memory record + journal append) as a pure both-or-
      neither decision — e.g. a canonical at-most-once predicate (recorded
      tool-result ids / pending set) readable purely in
      `:session/tool-agent-record-result`.
- [ ] Address cross-component layering: the journal append is an agent-session
      effect while `:pending-tool-calls` ownership is in agent-core. Specify that
      the agent-session pure dispatch handler owns the applied?/effects decision
      (from canonical state agent-core projects), so a lower component does not
      gate a higher component's effect.
