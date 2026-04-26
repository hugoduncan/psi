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
| 5 | Loop-back input bindings | No special source | Goto target uses normal bindings; reviewer output available via `:step-output` if wired |
| 6 | Judge retry limit | Fixed at 2 retries (3 total) | Simple; not configurable per judge in first cut |
| 7 | Judge system prompt | Author-provided | No auto-generation; author controls judge instructions via optional `:judge {:system-prompt ...}`. `:judge {:prompt ...}` is the user-turn question. |
| 8 | Statechart events Phase B vs A | Phase B = history markers; Phase A = transition events | Phase B adds `:verdict/*` for observability only |
| 9 | Judge fields placement | On attempt, not step-run | Consistent with `:execution-error`; each attempt gets own judge result |
| 10 | `:iteration-count` semantics | Starts at 0, incremented on every entry including first | `:max-iterations 3` = at most 3 total entries |
| 11 | `:on` requires `:judge` | Validation error if `:on` without `:judge` | Avoids confusing half-state |
| 12 | `:goto :previous` on first step | Fail with `:no-previous-step` | Definition error |
| 13 | Judge retry mechanism | Multi-turn `prompt-in!` on same session | Session is idle after each response |
| 14 | Judge tools | None — empty tool-defs | Pure text classification |
| 15 | Recording actor result (judged steps) | New `record-actor-result` helper | Writes envelope without advancing `current-step-id` |

## Phase B implementation — completed 2026-04-25

All 8 slices landed in sequential commits:

1. **Slice 1** — Model schemas: projection, judge, routing directive, routing table schemas; step-def/step-run/attempt extensions
2. **Slice 2** — Projection extraction: `project-messages` with `:none`, `:full`, `{:type :tail}` + tool stripping
3. **Slice 3** — Routing evaluation: `match-signal`, `resolve-goto-target`, `check-iteration-limit`, `evaluate-routing`
4. **Slice 4** — Judge session execution: `execute-judge!` with retry (max 2 retries, feedback injection)
5. **Slice 5** — Progression: `increment-iteration-count`, `record-actor-result`, `submit-judged-result` with verdict history events
6. **Slice 6** — Execution wiring: `execute-current-step!` judge branch, `execute-run!` loop test proving plan→build→review→REVISE→build→review→APPROVED
7. **Slice 7** — Compiler: `compile-multi-step` threads `:judge`/`:on`, resolves goto workflow names to step-ids, `validate-judge-routing`
8. **Slice 8** — Full suite green: 1397 unit tests (10499 assertions), 142 extension tests (563 assertions), 0 failures

### Key implementation decision during execution

- **Iteration count ownership**: `increment-iteration-count` is called only in `execute-current-step!` (on step entry), not in `submit-judged-result` (on goto routing). This avoids double-counting when a goto routes to a step that then executes. The design's "incremented on every entry" maps to the execution entry point, not the routing directive.

### Files changed

**New:**
- `components/agent-session/src/psi/agent_session/workflow_judge.clj` — projection, routing, judge execution
- `components/agent-session/test/psi/agent_session/workflow_judge_test.clj` — 13 focused tests

**Modified:**
- `components/agent-session/src/psi/agent_session/workflow_model.clj` — +4 schemas, extended step-def/step-run/attempt
- `components/agent-session/src/psi/agent_session/workflow_progression.clj` — +3 functions (increment, record-actor, submit-judged)
- `components/agent-session/src/psi/agent_session/workflow_execution.clj` — judge branch in execute-current-step!, iteration count at start
- `components/agent-session/src/psi/agent_session/workflow_file_compiler.clj` — judge/on threading, goto resolution, validate-judge-routing
- `components/agent-session/test/psi/agent_session/workflow_model_test.clj` — +7 schema tests
- `components/agent-session/test/psi/agent_session/workflow_progression_test.clj` — +6 judge progression tests
- `components/agent-session/test/psi/agent_session/workflow_execution_test.clj` — +2 judge execution tests
- `components/agent-session/test/psi/agent_session/workflow_file_compiler_test.clj` — +4 compiler tests
