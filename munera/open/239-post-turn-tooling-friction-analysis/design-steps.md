# Design steps — architectural review (design-review session, turn 1)

- [ ] Reconsider the "Home" decision's reuse of a bare `defonce` recursion-guard
      atom (explicitly modeled on `helper-session-ids`) for tracking this
      analyzer's own helper sessions. `ramora/META.md` states: "psi runtime
      owns process-scoped managed services on ctx for long-lived subprocesses
      and similar runtime resources" and "managed services are keyed by
      logical identity and reused within ctx rather than extension-local
      hidden state." Adding a second such atom compounds an already-noted
      anti-pattern rather than moving toward the ctx-keyed managed-service
      model. Design should note (even if implementation defers the actual
      migration) whether this new guard state should be ctx-keyed instead of
      another top-level extension atom, so the plan/implementation stage
      makes a deliberate choice rather than defaulting to copy-paste of the
      existing pattern.

# Design steps — ambiguity review (design-review session, turn 2)

- [ ] Acceptance criterion 6 says "Task creation is capped per analysis run,"
      but the Constraints section only offers a range as a suggestion
      ("suggest: 1–2"), not a decided value. Resolve to a single stated cap
      (or explicitly state the cap is a planning-stage decision) so the
      acceptance criterion has one unambiguous number to test against.

- [ ] The recursion-guard decision excludes only "the extension's own helper
      sessions." It's unclear whether other extensions' or the runtime's own
      non-substantive helper/infra sessions (e.g. entity-resolution helper
      sessions, other workflow helper sessions) are in-scope inputs for
      friction analysis, or should also be excluded as noise/non-representative
      sessions. Clarify whether "every session" is meant literally (any
      session other than this analyzer's own helpers) or is intended to
      exclude other known helper/infra session categories too.
