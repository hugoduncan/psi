# 056 — Steps

- [ ] Design review and open-question resolution
- [ ] Model: judge schema, projection schema, routing directive schema, step-run judge fields, iteration counts
- [ ] Projection: `workflow_judge.clj` — extract projected messages from actor session history
- [ ] Judge execution: create judge session, prompt, match signal → event
- [ ] Routing: evaluate routing table directive, apply goto/advance/done
- [ ] Progression: wire judge+routing into `submit-result-envelope` / post-actor phase
- [ ] Statechart: `verdict/advance`, `verdict/goto`, `verdict/exhausted` events
- [ ] Execution loop: wire judge phase between actor completion and progression in `execute-current-step!`
- [ ] Compiler: thread `:judge` and `:on` from workflow file config to canonical step definitions
- [ ] Tests: model, projection, judge execution, routing, progression, compiler, end-to-end loop
- [ ] Backward compatibility verification: existing workflow files and full test suite green
