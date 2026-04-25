# 056 — Steps

## Phase B: Progression-layer extension

- [ ] Design review — resolve remaining open questions (loop-back bindings, judge retry limit, judge system prompt)
- [ ] Model: judge schema, projection schema, routing directive schema in `workflow_model.clj`
- [ ] Model: step-run judge fields (`:judge-session-id`, `:judge-output`, `:judge-event`, `:iteration-count`)
- [ ] Model: step definition additions (`:judge`, `:on`)
- [ ] Projection: new `workflow_judge.clj` — extract projected messages from actor session history
- [ ] Projection: `:none`, `:full`, `{:type :tail}` strategies
- [ ] Judge execution: create judge session with projected preloaded messages, prompt, extract signal
- [ ] Judge retry: on no-match, inject feedback into judge session, continue, bounded retries then fail
- [ ] Routing: evaluate `:on` table — match signal, resolve directive, check iteration guard
- [ ] Routing: `:goto :next`, `:goto :done`, `:goto :previous`, `:goto "step-id"`
- [ ] Routing: `:max-iterations` per-step counting, fail on exhaustion
- [ ] Execution: wire judge+routing phase into `execute-current-step!` between actor completion and progression
- [ ] Execution: steps without `:judge`/`:on` follow existing path unchanged
- [ ] Compiler: thread `:judge` and `:on` from workflow file step config to canonical step definitions
- [ ] Compiler: validate `:goto` targets reference known step ids
- [ ] Statechart: add verdict events for observability (`:verdict/advance`, `:verdict/goto`, `:verdict/exhausted`)
- [ ] Tests: projection extraction (`:none`, `:full`, `:tail`, `:tail` without tool output)
- [ ] Tests: judge signal matching (exact, trimmed, no-match retry, retry exhaustion)
- [ ] Tests: routing directive evaluation (`:next`, `:done`, `:previous`, named step, iteration guard)
- [ ] Tests: end-to-end loop (plan→build→review with REVISE looping back to build)
- [ ] Tests: end-to-end linear (no judge, identical to today)
- [ ] Tests: max-iterations exhaustion → workflow fails
- [ ] Backward compatibility: existing workflow files and full test suite green

## Phase A: Statechart-driven execution (follow-on)

- [ ] Compile workflow definitions into hierarchical statecharts (leaf states for simple steps, compound for judged)
- [ ] Entry actions own session creation, prompt materialization, prompting
- [ ] Exit actions own result recording into extended state (context)
- [ ] Extended state (context) replaces workflow-run state map as accumulator
- [ ] Guards own iteration-count checks and signal matching
- [ ] `execute-run!` becomes "start statechart, process events until quiescent"
- [ ] Progression layer thins to context updates and event emission
- [ ] Imperative execution loop removed
- [ ] Existing tests refactored to drive statechart directly
- [ ] Full test suite green
