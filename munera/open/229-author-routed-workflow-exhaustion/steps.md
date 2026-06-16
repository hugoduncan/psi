# 229 — Steps

Execution checklist. Tick items as completed; note sha / decision / snag inline.
Slices are independently committable; keep each commit `small`.

## Slice 1 — `:on-max-iterations` engine primitive (inert)

- [x] `model.clj` `routing-directive-schema`: add optional `:on-max-iterations`
      + reject-without-`:max-iterations` cross-field constraint (D3)
      — `[:and [:map …] [:fn on-max-iterations-requires-max-iterations?]]`
- [x] `ir.clj` `routing-directive-schema`: same optional key + same reject
      constraint (D3)
- [x] `target_ir_compiler.clj` `compile-routing-table`: thread
      `:on-max-iterations` model→IR (additive `cond->` clause)
- [x] `workflow_judge.clj` `evaluate-routing` (**governing site, DI-6**): when
      `check-iteration-limit` is `:exhausted` and the directive carries
      `:on-max-iterations`, return `{:action :goto/:complete}` via
      `resolve-goto-target` instead of `{:action :fail :reason :iteration-exhausted}`;
      absent → unchanged `:fail`
- [x] `statechart.clj`: extracted goto→target resolution to private
      `resolve-goto-acting-target`; used for both `:goto` and the new
      `:on-max-iterations` exhaustion target in `compile-routing-transitions`;
      default exhaustion stays `:failed`
- [x] `model_test.clj` / `ir_test.clj`: accept-with / reject-without
      `:max-iterations`
- [x] `target_ir_compiler_test.clj`: threading assertion (AC-2)
- [x] `statechart_test.clj`: `:judge/signal` exhaustion→author target
      (`:judge/record`, not failed; `:step/handback.acting`) + regression-lock
      exhaustion→`:failed` (`:iteration/exhausted`) preserved
- [x] **integration test (DI-6)** (`workflow_review_step_routing_test`): a
      real exhausted design loop with `:on-max-iterations` routes to the author
      target (`final-summary-not-converged`), run NOT `:failed`, terminal-outcome
      not `:iteration-exhausted`; existing failure test (no key) is the
      regression-lock — the pure-statechart test cannot cover this path
- [x] focused workflow-runtime + workflow-judge + routing Scry green; clj-kondo clean
- [x] commit `⚒ workflow-runtime: add :on-max-iterations author-routed exhaustion target`

## Slice 2 — review-task-design handback + lifecycle design gate

- [ ] `review-task-design.edn`: `design-follow-up` `:on` +
      `:on-max-iterations "final-summary-not-converged"`
- [ ] `review-task-design.edn`: new `final-summary-not-converged` step placed
      **before** the converged `final-summary` (DI-2: keep converged last);
      design-review per-prompt sources; explicit-terminal
      judge+`:on {"DONE" {:goto :done}}`; DI-4 template contract
      (`PASS_STATUS: ACTIONABLE_FEEDBACK`, keep prose guard (a) / rewrite anti-echo
      guard (b), sole column-0 line); **DI-3: no literal iteration count** in the
      wording
- [ ] `review-task-design.edn`: converged `final-summary` → explicit-terminal
      judge+`:on {"DONE" {:goto :done}}` + DI-4 contract
      (`PASS_STATUS: REVIEW_COMPLETE`, keep (a)/rewrite (b), sole line)
- [ ] `task-lifecycle.edn`: `check-design-review-status` gate after
      `review-task-design` (DONE→`create-task-plan`,
      REPEAT→`final-summary-design-not-converged`)
- [ ] `task-lifecycle.edn`: `final-summary-design-not-converged` handback step
      (`:goto :done`, no extraction)
- [ ] `workflow_definitions_test.clj` `review-task-design-test`: (i) step-order
      incl. `final-summary-not-converged` before converged `final-summary`;
      (ii) `design-follow-up` `:on-max-iterations`; (iii) **fix the pre-existing
      RED `:max-iterations` 6→3 assertion** in the same edit; (iv) DI-4
      template-text authority — assert each summary body contains its sole,
      exact-form `PASS_STATUS:` line
- [ ] **converged standalone result-text runtime test (DI-2)**: converged
      `final-summary` (ordered last) is the step whose yielded text surfaces via
      the standalone `(last :step-order)` path (locks ordering/plumbing)
- [ ] task-lifecycle coverage: **update existing `task-lifecycle-test` in place**
      (count 9→11, name/type vectors, positional `nth`, `repeat` counts) for the
      design gate + handback; any new `229` test additive-only (R3/DI-5)
- [ ] focused workflow-loader + affected runtime Scry green; clj-kondo clean
- [ ] commit `⚒ workflows: route unconverged design review to lifecycle handback`

## Slice 3 — review-task-plan handback + lifecycle plan gate (symmetric)

- [ ] `review-task-plan.edn`: `plan-follow-up` `:on-max-iterations`; new
      `final-summary-not-converged` placed **before** converged `final-summary`
      (DI-2); both explicit-terminal; DI-4 contract
      (`PASS_STATUS: REVIEW_COMPLETE` converged / `ACTIONABLE_FEEDBACK`
      not-converged, keep (a)/rewrite (b), sole line); **DI-3: no literal
      iteration count** in not-converged wording
- [ ] `task-lifecycle.edn`: `check-plan-review-status` gate after
      `review-task-plan` (DONE→`implement-task`,
      REPEAT→`final-summary-plan-not-converged`) + `final-summary-plan-not-converged`
- [ ] `workflow_definitions_test.clj` `review-task-plan-test`: step-order incl.
      not-converged before converged; `:on-max-iterations`; DI-4 template-text
      authority (sole exact-form `PASS_STATUS:` line per summary)
- [ ] **converged standalone result-text runtime test (DI-2)** for plan review
- [ ] task-lifecycle coverage: **update existing `task-lifecycle-test` in place**
      (count 11→13, name/type vectors, positional `nth`, `repeat` counts) for the
      plan gate + handback; new `229` test additive-only (R3/DI-5)
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
