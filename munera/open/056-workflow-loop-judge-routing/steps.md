# 056 — Steps

## Phase B: Progression-layer extension

### Slice 1 — Model schemas
- [ ] Add `projection-schema` to `workflow_model.clj`
- [ ] Add `judge-schema` to `workflow_model.clj`
- [ ] Add `routing-directive-schema` to `workflow_model.clj`
- [ ] Add `routing-table-schema` to `workflow_model.clj`
- [ ] Extend `workflow-step-definition-schema` with optional `:judge` and `:on`
- [ ] Extend `workflow-step-run-schema` with `:judge-session-id`, `:judge-output`, `:judge-event`, `:iteration-count`
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
- [ ] `resolve-goto-target` — `:next`, `:previous`, `:done`, named step-id
- [ ] `check-iteration-limit` — within-limit vs exhausted
- [ ] `evaluate-routing` — compose match → resolve → check → action
- [ ] Tests: each function + `evaluate-routing` integration

### Slice 4 — Judge session execution
- [ ] `execute-judge!` — create judge session, prompt, extract signal, match
- [ ] Judge retry on no-match — inject feedback, continue session, up to 2 retries
- [ ] Return `{:judge-session-id :judge-output :judge-event :routing-result}`
- [ ] Tests: successful match, retry-then-match, retry exhaustion (with-redefs on session/prompt)

### Slice 5 — Progression: iteration tracking and routing
- [ ] `increment-iteration-count` on step-run
- [ ] New progression path for judged results — records judge state, applies routing
- [ ] `:goto` named step — set `current-step-id`, increment target iteration count
- [ ] `:goto :next` / `:goto :done` — advance or complete
- [ ] `:goto :previous` — resolve and set
- [ ] Iteration exhaustion → `:failed`
- [ ] Add `:verdict/advance`, `:verdict/goto`, `:verdict/exhausted` to statechart event catalog
- [ ] Existing `:ok` → advance path unchanged for steps without judge
- [ ] Tests: pure state transitions for all routing outcomes

### Slice 6 — Execution: wire judge into step execution
- [ ] After actor `:ok` completion, check step def for `:judge`
- [ ] If judge: call `execute-judge!`, apply routing via progression
- [ ] If no judge: existing advance path unchanged
- [ ] `execute-run!` loop handles goto naturally (different `current-step-id`)
- [ ] Tests: `execute-current-step!` with judge, `execute-run!` with loop

### Slice 7 — Compiler: thread judge and routing from file format
- [ ] `compile-multi-step`: thread `:judge` from step config to canonical step def
- [ ] `compile-multi-step`: thread `:on` from step config to canonical step def
- [ ] Extend `validate-step-references` to check `:goto` targets in `:on` directives
- [ ] Tests: compile with judge+on, compile without (unchanged), invalid goto target

### Slice 8 — End-to-end integration and backward compatibility
- [ ] End-to-end: plan→build→review with judge loop (REVISE→build→review→APPROVED→done)
- [ ] Verify iteration counts, judge results, step-run state at each stage
- [ ] Run full existing test suite — zero regressions
- [ ] Verify existing workflow files compile and validate without modification

## Phase A: Statechart-driven execution (follow-on task)

- [ ] Compile definitions into hierarchical statecharts (leaf/compound states)
- [ ] Entry actions own session creation + prompting
- [ ] Exit actions own result recording into extended state
- [ ] Extended state replaces workflow-run state map
- [ ] Guards own iteration checks and signal matching
- [ ] `execute-run!` becomes "start statechart, process events until quiescent"
- [ ] Imperative execution loop removed
- [ ] Existing tests refactored to drive statechart
- [ ] Full suite green
