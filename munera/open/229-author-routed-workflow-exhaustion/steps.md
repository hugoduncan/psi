# 229 — Steps

Execution checklist. Tick items as completed; note sha / decision / snag inline.
Slices are independently committable; keep each commit `small`.

## Slice 1 — `:on-max-iterations` engine primitive (inert)

- [ ] `model.clj` `routing-directive-schema`: add optional `:on-max-iterations`
      + reject-without-`:max-iterations` cross-field constraint (D3)
- [ ] `ir.clj` `routing-directive-schema`: same optional key + same reject
      constraint (D3)
- [ ] `target_ir_compiler.clj` `compile-routing-table`: thread
      `:on-max-iterations` model→IR
- [ ] `statechart.clj`: extract goto→target resolution to a private helper; use
      it for both `:goto` and the new `:on-max-iterations` exhaustion target in
      `compile-routing-transitions`; default exhaustion stays `:failed`
- [ ] `model_test.clj` / `ir_test.clj`: accept-with / reject-without
      `:max-iterations`
- [ ] `target_ir_compiler_test.clj`: threading assertion
- [ ] `statechart_test.clj`: exhaustion→author target (`:judge/record`, not
      failed) + regression-lock exhaustion→`:failed` (`:iteration/exhausted`)
- [ ] focused workflow-runtime Scry green; clj-kondo clean
- [ ] commit `⚒ workflow-runtime: add :on-max-iterations author-routed exhaustion target`

## Slice 2 — review-task-design handback + lifecycle design gate

- [ ] `review-task-design.edn`: `design-follow-up` `:on` +
      `:on-max-iterations "final-summary-not-converged"`
- [ ] `review-task-design.edn`: converged `final-summary` → explicit-terminal
      judge+`:on {"DONE" {:goto :done}}` + `PASS_STATUS: REVIEW_COMPLETE` (DI-1/D1)
- [ ] `review-task-design.edn`: new `final-summary-not-converged` step
      (design-review per-prompt sources, `PASS_STATUS: ACTIONABLE_FEEDBACK`,
      explicit-terminal)
- [ ] `task-lifecycle.edn`: `check-design-review-status` gate after
      `review-task-design` (DONE→`create-task-plan`,
      REPEAT→`final-summary-design-not-converged`)
- [ ] `task-lifecycle.edn`: `final-summary-design-not-converged` handback step
      (`:goto :done`, no extraction)
- [ ] `workflow_definitions_test.clj` `review-task-design-test`: step-order +
      `design-follow-up` `:on-max-iterations` + terminal/PASS_STATUS assertions
- [ ] task-lifecycle definition coverage for the design gate + handback routing
- [ ] focused workflow-loader + affected runtime Scry green; clj-kondo clean
- [ ] commit `⚒ workflows: route unconverged design review to lifecycle handback`

## Slice 3 — review-task-plan handback + lifecycle plan gate (symmetric)

- [ ] `review-task-plan.edn`: `plan-follow-up` `:on-max-iterations`; converged
      `final-summary` explicit-terminal + `PASS_STATUS: REVIEW_COMPLETE`; new
      `final-summary-not-converged` + `PASS_STATUS: ACTIONABLE_FEEDBACK`
- [ ] `task-lifecycle.edn`: `check-plan-review-status` gate after
      `review-task-plan` (DONE→`implement-task`,
      REPEAT→`final-summary-plan-not-converged`) + `final-summary-plan-not-converged`
- [ ] `workflow_definitions_test.clj` `review-task-plan-test`: step-order +
      routing updates
- [ ] task-lifecycle definition coverage for the plan gate + handback routing
- [ ] focused workflow-loader Scry green; clj-kondo clean
- [ ] commit `⚒ workflows: route unconverged plan review to lifecycle handback`

## Slice 4 — docs + coherence

- [ ] `doc/workflows.md`: `:on-max-iterations` key + design/plan non-convergence
      handback behaviour
- [ ] `CHANGELOG.md` `[Unreleased]`: Added (`:on-max-iterations`) + Changed
      (lifecycle stops/hands back on unconverged design/plan review)
- [ ] `mementum/state.md` workflow-routing bullet if warranted
- [ ] coherence re-read of edited files (`sync`); full focused suites green
- [ ] commit `⚒ docs: :on-max-iterations + review-handback lifecycle behaviour`
