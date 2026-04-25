# 056 — Implementation notes

## Provenance

Designed in conversation 2026-04-25. Key design evolution:

1. Started from "loops as the most valuable next workflow extension"
2. Explored result-driven vs definition-driven vs judge-step approaches
3. Judge-as-separate-agent selected for separation of concerns and actor reusability
4. Projection model introduced so judge can control context window into actor session
5. Projection elevated from inline keys to named strategy (`:none`, `:full`, `{:type :tail ...}`)
6. Routing table (`:on`) introduced — maps judge signals to directives
7. Key insight: every step already has an implicit routing table; judge+`:on` just makes it explicit
8. Linear pipeline is the zero-configuration degenerate case — full backward compatibility
9. Rigorous statechart ↔ workflow correspondence established:
   - states = steps in phases (acting, judging)
   - events = actor/done, judge verdict signals
   - entry actions = spawn and prompt sessions
   - exit actions = record results into context
   - guards = iteration checks, signal matching
   - extended state = workflow context (inputs, outputs, counts, session refs)
   - agent/judge sessions = external resources, referenced not held
   - prompts/projections = computed functions of context, not state
10. Current statechart is a status tracker; target architecture is statechart as execution controller
11. Two-phase implementation: B (progression-layer extension, ships capability) then A (statechart-driven execution, ships architecture)

## Resolved decisions

| # | Question | Decision | Rationale |
|---|----------|----------|-----------|
| 1 | `:max-iterations` exhaustion | Fail | Predictable; human can restart |
| 2 | Judge signal matching | Exact (trimmed) | Predictable; author controls judge prompt |
| 3 | Judge failure (no match) | Limited retries with feedback injection, then fail | Gives the judge a chance to correct; bounded |
| 4 | Iteration counting | Per-step | All gotos to same step share one counter; simpler mental model |
