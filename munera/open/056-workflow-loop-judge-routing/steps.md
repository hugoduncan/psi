# 056 — Steps

## Phase B: Progression-layer extension

### Slice 1 — Model schemas
- [ ] Add `projection-schema` to `workflow_model.clj`
- [ ] Add `judge-schema` to `workflow_model.clj` (`:prompt`, optional `:system-prompt`, optional `:projection`)
- [ ] Add `routing-directive-schema` to `workflow_model.clj`
- [ ] Add `routing-table-schema` to `workflow_model.clj`
- [ ] Extend `workflow-step-definition-schema` with optional `:judge` and `:on`
- [ ] Extend `workflow-step-run-schema` with `:iteration-count`
- [ ] Extend `workflow-step-attempt-schema` with `:judge-session-id`, `:judge-output`, `:judge-event`
- [ ] Tests: schema validation (valid/invalid judge, projection, routing table, enriched step defs)
- [ ] Verify existing model tests pass unmodified

### Slice 2 — Projection extraction
- [ ] Create `workflow_judge.clj` with `project-messages` (pure)
- [ ] Implement `:none` projection
- [ ] Implement `:full` projection
- [ ] Implement `{:type :tail :turns N}` projection
- [ ] Implement `{:type :tail :turns N :tool-output false}` — strip tool blocks
- [ ] Tests: realistic message sequences — full, tail-1, tail-3, tool stripping, empty, edge cases

### Slice 3 — Routing evaluation
- [ ] `match-signal` — exact match after trim
- [ ] `resolve-goto-target` — returns `{:action :goto :target id}` or `{:action :complete}` or `{:action :fail}`; handles `:next` (including last-step = complete), `:previous` (including first-step = fail), `:done`, named step-id
- [ ] `check-iteration-limit` — checks **target** step-run's iteration count vs directive's `:max-iterations`
- [ ] `evaluate-routing` — signature takes `step-runs` map for target iteration lookup; compose match → resolve → check → action (`:no-match` consumed only by `execute-judge!` retry loop)
- [ ] Tests: each function + `evaluate-routing` integration (including `:next` from last step = complete, `:previous` from first step = fail)

### Slice 4 — Judge session execution
- [ ] `execute-judge!` — create judge session with empty tool-defs (no tools), optional system-prompt, projected preloaded messages; prompt via `prompt-in!`; extract signal; match via `evaluate-routing`
- [ ] Judge retry on no-match — send new user message to same session via `prompt-in!` with feedback; up to 2 retries (3 total attempts)
- [ ] Return `{:judge-session-id :judge-output :judge-event :routing-result}`
- [ ] Tests: successful match, retry-then-match, retry exhaustion (with-redefs on session/prompt)

### Slice 5 — Progression: iteration tracking and routing
- [ ] `record-actor-result` — writes envelope + accepted-result on step-run without advancing (extracted from recording part of `submit-result-envelope`)
- [ ] `increment-iteration-count` on step-run (starts at 0, incremented on every entry including first)
- [ ] `submit-judged-result` — records judge fields on attempt, applies routing
- [ ] `:goto` named step — set `current-step-id`, increment target iteration count
- [ ] `:goto :next` / `:goto :done` — advance or complete
- [ ] `:goto :previous` — resolve and set
- [ ] Iteration exhaustion → `:failed`
- [ ] Add `:verdict/advance`, `:verdict/goto`, `:verdict/exhausted` to statechart event catalog
- [ ] Existing `:ok` → advance path unchanged for steps without judge
- [ ] Tests: pure state transitions for all routing outcomes

### Slice 6 — Execution: wire judge into step execution
- [ ] After actor `:ok` completion, check step def for `:judge`
- [ ] If judge: call `record-actor-result` (not `submit-result-envelope`), then call `execute-judge!`, then call `submit-judged-result` (records judge on attempt, applies routing)
- [ ] If no judge: existing advance path unchanged (call `submit-result-envelope`)
- [ ] `execute-run!` loop handles goto naturally (different `current-step-id`)
- [ ] Tests: `execute-current-step!` with judge, `execute-run!` with loop

### Slice 7 — Compiler: thread judge and routing from file format
- [ ] `compile-multi-step`: thread `:judge` from step config to canonical step def
- [ ] `compile-multi-step`: thread `:on` from step config — resolve `:goto` workflow names to compiled step-ids; keywords (`:next`, `:previous`, `:done`) pass through
- [ ] Validate: `:on` without `:judge` is a compilation error
- [ ] Extend `validate-step-references` to check resolved `:goto` string targets in `:on` directives
- [ ] Tests: compile with judge+on (verify workflow-name→step-id resolution), compile without (unchanged), invalid goto target, `:on` without `:judge` (error)

### Slice 8 — End-to-end integration and backward compatibility
- [ ] End-to-end: plan→build→review with judge loop (REVISE→build→review→APPROVED→done)
- [ ] Verify iteration counts, judge results, step-run state at each stage
- [ ] Run full existing test suite — zero regressions
- [ ] Verify existing workflow files compile and validate without modification

## Phase A: Statechart-driven execution (follow-on task)

- [ ] Compile definitions into hierarchical statecharts (leaf states for simple steps, compound `.acting`/`.judging` for judged steps)
- [ ] Entry actions own: iteration count increment, session creation, prompting
- [ ] Exit actions own: result recording into extended state
- [ ] Model `:actor/failed` event with retry guards (matches existing `record-execution-failure`)
- [ ] Model `:workflow/cancel` transitions from all non-terminal states
- [ ] Model judge retry as internal transition (no exit/entry — same session continues)
- [ ] Decide context shape: flatten existing nested workflow-run structure or adapt statechart to nested shape
- [ ] Extended state replaces workflow-run state map as accumulator
- [ ] Guards own iteration checks (target step count) and signal matching
- [ ] `execute-run!` becomes "start statechart, process events until quiescent"
- [ ] Imperative execution loop removed
- [ ] Existing tests refactored to drive statechart
- [ ] Full suite green
