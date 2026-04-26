# 056 — Steps

## Phase B: Progression-layer extension

### Slice 1 — Model schemas
- [x] Add `projection-schema` to `workflow_model.clj`
- [x] Add `judge-schema` to `workflow_model.clj` (`:prompt`, optional `:system-prompt`, optional `:projection`)
- [x] Add `routing-directive-schema` to `workflow_model.clj`
- [x] Add `routing-table-schema` to `workflow_model.clj`
- [x] Extend `workflow-step-definition-schema` with optional `:judge` and `:on`
- [x] Extend `workflow-step-run-schema` with `:iteration-count`
- [x] Extend `workflow-step-attempt-schema` with `:judge-session-id`, `:judge-output`, `:judge-event`
- [x] Tests: schema validation (valid/invalid judge, projection, routing table, enriched step defs)
- [x] Verify existing model tests pass unmodified

### Slice 2 — Projection extraction
- [x] Create `workflow_judge.clj` with `project-messages` (pure)
- [x] Implement `:none` projection
- [x] Implement `:full` projection
- [x] Implement `{:type :tail :turns N}` projection
- [x] Implement `{:type :tail :turns N :tool-output false}` — strip tool blocks
- [x] Tests: realistic message sequences — full, tail-1, tail-3, tool stripping, empty, edge cases

### Slice 3 — Routing evaluation
- [x] `match-signal` — exact match after trim
- [x] `resolve-goto-target` — returns `{:action :goto :target id}` or `{:action :complete}` or `{:action :fail}`; handles `:next` (including last-step = complete), `:previous` (including first-step = fail), `:done`, named step-id
- [x] `check-iteration-limit` — checks **target** step-run's iteration count vs directive's `:max-iterations`
- [x] `evaluate-routing` — signature takes `step-runs` map for target iteration lookup; compose match → resolve → check → action (`:no-match` consumed only by `execute-judge!` retry loop)
- [x] Tests: each function + `evaluate-routing` integration (including `:next` from last step = complete, `:previous` from first step = fail)

### Slice 4 — Judge session execution
- [x] `execute-judge!` — create judge session with empty tool-defs (no tools), optional system-prompt, projected preloaded messages; prompt via `prompt-in!`; extract signal; match via `evaluate-routing`
- [x] Judge retry on no-match — send new user message to same session via `prompt-in!` with feedback; up to 2 retries (3 total attempts)
- [x] Return `{:judge-session-id :judge-output :judge-event :routing-result}`
- [x] Tests: successful match, retry-then-match, retry exhaustion (with-redefs on session/prompt)

### Slice 5 — Progression: iteration tracking and routing
- [x] `record-actor-result` — writes envelope + accepted-result on step-run without advancing (extracted from recording part of `submit-result-envelope`)
- [x] `increment-iteration-count` on step-run (starts at 0, incremented on every entry including first)
- [x] `submit-judged-result` — records judge fields on attempt, applies routing
- [x] `:goto` named step — set `current-step-id`
- [x] `:goto :next` / `:goto :done` — advance or complete
- [x] `:goto :previous` — resolve and set
- [x] Iteration exhaustion → `:failed`
- [x] Add `:verdict/advance`, `:verdict/goto`, `:verdict/exhausted` to statechart event catalog
- [x] Existing `:ok` → advance path unchanged for steps without judge
- [x] Tests: pure state transitions for all routing outcomes

### Slice 6 — Execution: wire judge into step execution
- [x] After actor `:ok` completion, check step def for `:judge`
- [x] If judge: call `record-actor-result` (not `submit-result-envelope`), then call `execute-judge!`, then call `submit-judged-result` (records judge on attempt, applies routing)
- [x] If no judge: existing advance path unchanged (call `submit-result-envelope`)
- [x] `execute-run!` loop handles goto naturally (different `current-step-id`)
- [x] Tests: `execute-current-step!` with judge, `execute-run!` with loop

### Slice 7 — Compiler: thread judge and routing from file format
- [x] `compile-multi-step`: thread `:judge` from step config to canonical step def
- [x] `compile-multi-step`: thread `:on` from step config — resolve `:goto` workflow names to compiled step-ids; keywords (`:next`, `:previous`, `:done`) pass through
- [x] Validate: `:on` without `:judge` is a compilation error
- [x] Extend `validate-step-references` to check resolved `:goto` string targets in `:on` directives
- [x] Tests: compile with judge+on (verify workflow-name→step-id resolution), compile without (unchanged), invalid goto target, `:on` without `:judge` (error)

### Slice 8 — End-to-end integration and backward compatibility
- [x] End-to-end: plan→build→review with judge loop (REVISE→build→review→APPROVED→done) — proven in execute-run-with-loop-test
- [x] Verify iteration counts, judge results, step-run state at each stage
- [x] Run full existing test suite — zero regressions (1397 tests, 10499 assertions, 0 failures)
- [x] Run extension test suite — zero regressions (142 tests, 563 assertions, 0 failures)
- [x] Verify existing workflow files compile and validate without modification

### Slice 9 — Review fixes

- [x] Fix `submit-judged-result` docstring: remove "increment target iteration count" from `:goto` description (iteration count is owned by `execute-current-step!` only)
- [x] Wire `validate-judge-routing` into `workflow_file_loader/load-workflow-files` alongside existing `validate-step-references` and `validate-no-name-collisions` calls; add loader test proving `:on` without `:judge` surfaces as a load error
- [x] Eliminate `extract-assistant-text` duplication: made `workflow-execution/assistant-message-text` public; aligned `workflow-judge` private copy to same shape with dep-direction comment; `execute-judge!` now separates retrieval from extraction
- [x] Reduce `execute-judge!` positional params: grouped routing context (`current-step-id`, `step-order`, `step-runs`) into a `routing-context` map parameter
- [x] Harden `evaluate-routing` iteration-count lookup: replaced `(:iteration-count target-run 0)` with explicit `(get target-run :iteration-count 0)` so intent is clear when `target-run` is nil
- [x] Run full suite green after fixes (1397 unit tests / 10577 assertions, 142 extension tests / 563 assertions)

### Slice 10 — Code shaper fixes

- [x] Remove redundant `str/trim` on `:judge-event` in `execute-judge!` — `last-output` is already trimmed at extraction
- [x] Extract shared `step-result-map` helper in `workflow_execution.clj` — reduces 3 result-map constructions to `assoc` on shared base
- [x] Replace `(some #{goto} step-order)` with `(contains? (set step-order) goto)` in `resolve-goto-target` for idiomatic membership check
- [x] Run full suite green after fixes (1398 unit tests / 10579 assertions, 0 failures)

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
