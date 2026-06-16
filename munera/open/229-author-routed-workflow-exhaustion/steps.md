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

- [x] `review-task-design.edn`: `design-follow-up` `:on` +
      `:on-max-iterations "final-summary-not-converged"` (and 6→3 already in edn)
- [x] `review-task-design.edn`: new `final-summary-not-converged` step placed
      **before** the converged `final-summary` (DI-2: keep converged last);
      design-review per-prompt sources; explicit-terminal
      judge+`:on {"DONE" {:goto :done}}`; DI-4 template contract
      (`PASS_STATUS: ACTIONABLE_FEEDBACK`, kept prose guard (a) / rewrote anti-echo
      guard (b) without literal `PASS_STATUS:` token, sole column-0 final line);
      **DI-3: no literal iteration count** ("did not converge within the
      configured follow-up iteration limit")
- [x] `review-task-design.edn`: converged `final-summary` → explicit-terminal
      judge+`:on {"DONE" {:goto :done}}` + DI-4 contract
      (`PASS_STATUS: REVIEW_COMPLETE`, keep (a)/rewrite (b), sole line)
- [x] `task-lifecycle.edn`: `check-design-review-status` gate after
      `review-task-design` (DONE→`create-task-plan`,
      REPEAT→`final-summary-design-not-converged`)
- [x] `task-lifecycle.edn`: `final-summary-design-not-converged` handback step
      appended last (`:goto :done`, no extraction)
- [x] `workflow_definitions_test.clj` `review-task-design-test`: (i) step-order
      incl. `final-summary-not-converged` before converged `final-summary`;
      (ii) `design-follow-up` `:on-max-iterations` + 6→3; (iii) both summaries
      explicit-terminal; (iv) DI-4 template-text authority via
      `assert-sole-final-pass-status-line` (sole, exact-form, final `PASS_STATUS:`)
- [x] **converged standalone result-text runtime test (DI-2)**: real
      `review-task-design.edn` driven through `execute-workflow-run` with stubbed
      `prompt-execution-result-in!`; asserts converged `final-summary` is last in
      step-order and `:psi.workflow/result` carries exactly one
      `PASS_STATUS: REVIEW_COMPLETE` (locks ordering/plumbing)
- [x] task-lifecycle coverage: **updated existing `task-lifecycle-test` in place**
      (count 9→11, name/type vectors, restructured the three `(take 5 steps)`
      assertions to name/type-filtered `delegate-steps` per DI-5, design gate +
      handback assertions, `repeat 9→11`)
- [x] focused workflow-loader (design+lifecycle vars) + DI-2 runtime Scry green;
      clj-kondo clean
- [x] pre-existing RED `review-task-plan-test` / `review-task-prompt-artifact-targets-test`
      (stale "steps.md" vs authored #177 "design-steps.md") → resolved in Slice 3
- [x] commit `⚒ workflows: route unconverged design review to lifecycle handback`

## Slice 3 — review-task-plan handback + lifecycle plan gate (symmetric)

- [x] `review-task-plan.edn`: `plan-follow-up` `:on-max-iterations`; new
      `final-summary-not-converged` placed **before** converged `final-summary`
      (DI-2); both explicit-terminal; DI-4 contract
      (`PASS_STATUS: REVIEW_COMPLETE` converged / `ACTIONABLE_FEEDBACK`
      not-converged, keep (a)/rewrite (b), sole line); **DI-3: no literal
      iteration count**
- [x] `task-lifecycle.edn`: `check-plan-review-status` gate after
      `review-task-plan` (DONE→`implement-task`,
      REPEAT→`final-summary-plan-not-converged`) + `final-summary-plan-not-converged`
      appended last
- [x] `workflow_definitions_test.clj` `review-task-plan-test`: step-order incl.
      not-converged before converged; `:on-max-iterations`; both terminal; DI-4
      template-text authority (sole exact-form `PASS_STATUS:` line per summary)
- [x] **converged standalone result-text runtime test (DI-2)** for plan review
- [x] task-lifecycle coverage: **updated existing `task-lifecycle-test` in place**
      (count 11→13, name/type vectors, plan gate + handback assertions,
      `repeat 13`); delegate-step selection already name/type-filtered (DI-5)
- [x] **resolved pre-existing #177 test-debt** in `review-task-plan-test` +
      `review-task-prompt-artifact-targets-test` (stale "steps.md"-only vs
      authored shared `design-steps.md`) — test-only, matched authored content
- [x] focused workflow-loader Scry green; clj-kondo clean
- [x] commit `⚒ workflows: route unconverged plan review to lifecycle handback`

## Slice 4 — docs + coherence

- [x] `doc/workflows.md`: new "Author-routed loop exhaustion (`:on-max-iterations`)"
      section; design/plan review sections show the exhaustion edge + 6→3 fix;
      task-lifecycle design/plan gate handback documented
- [x] `CHANGELOG.md` `[Unreleased]`: Added (`:on-max-iterations`) + Changed
      (lifecycle stops/hands back on unconverged design/plan review + standalone
      PASS_STATUS line)
- [x] `mementum/state.md` workflow-routing bullet updated
- [x] coherence re-read of edited files (`sync`); full focused suites green
      (666 assertions / 66 tests + 2 DI-2 live tests)
- [x] commit `⚒ docs: :on-max-iterations + review-handback lifecycle behaviour`

## Implementation review follow-ups (2026-06-16)

- [x] Remove the stray transient test-output artifact committed in `19b41b2ea`:
      `.scry-results/psi.agent-session.workflow-delegate-review-step-live-test__delegate-review-task-implementation-completes-with-nullable-local-model-test.edn`.
      `git rm` it and add `.scry-results/` to `.gitignore` so scry run outputs
      are never tracked. (Scry writes failures under `.scry-results/` per
      `bb.edn`; the dir is currently un-ignored and one file leaked into the
      tree.)

## Test-shaper review follow-ups (2026-06-16)

- [x] Deduplicate the two near-identical converged-standalone live tests in
      `workflow_delegate_review_step_live_test.clj`
      (`review-task-design-converged-standalone-surfaces-review-complete-result-test`
      and `review-task-plan-converged-standalone-surfaces-review-complete-result-test`):
      ~50 lines each that vary only by `:definition-id`, `:run-id`, and the
      summary reply prefix ("Design"/"Plan review completed cleanly."). Extract a
      parameterized helper carrying the shared models-path / context /
      `with-redefs` / create+execute ceremony + assertions so each call site
      expresses only the single varying axis (test-shaper: economical /
      helpers_that_compress / minimal incidental variation).

- [x] Strengthen `review-pass-loop-on-max-iterations-routes-to-author-target-test`
      (`workflow_review_step_routing_test.clj`): it asserts only negatives
      (`not= :failed`, `not= :iteration-exhausted`) plus a `some?` existence
      check, so a regression routing exhaustion to a non-failed-but-wrong
      terminal state (e.g. `:blocked`, stuck `:running`) would still pass. Mirror
      the positive shape of the sibling `review-pass-loop-iteration-limit-failure-test`
      (which asserts `= :failed` / `= :iteration-exhausted`) by asserting the
      positive terminal outcome — e.g. `(= :completed (:status run))` plus the
      handback step's accepted-result — for meaningful failure signal
      (test-shaper: meaningful_failures / behavior_focused).

- [x] (minor) `count-substring` is copy-pasted identically into
      `workflow_definitions_test.clj` and `workflow_delegate_review_step_live_test.clj`
      (different components). Consider hoisting to a shared test-support util, or
      fold its single use into the live-test helper above, to remove the
      cross-namespace duplicate.

## Test-shaper review follow-ups — pass 2 (2026-06-16)

- [x] Lock exhaustion-at-cap in
      `review-pass-loop-on-max-iterations-routes-to-author-target-test`
      (`workflow_review_step_routing_test.clj`). The test now asserts the
      positive terminal outcome (`:completed` + handback `:accepted-result`) but
      never proves the judged loop actually iterated to its configured cap
      (`:max-iterations 2`) before routing to the handback. A regression where
      `:on-max-iterations` fires *prematurely* (e.g. on the first follow-up
      instead of at exhaustion) would still surface `:completed` + an accepted
      handback result and pass. Mirror the sibling
      `review-pass-loop-iteration-limit-failure-test`'s attempt-count assertion
      (`(= 6 (count (get-in run [:step-runs "design-follow-up" :attempts])))`) by
      asserting the on-max run's `design-follow-up` attempt count equals the
      configured cap (2) — so the test proves the handback was reached *via
      exhaustion at the cap*, not via an early route (test-shaper:
      meaningful_failures / behavior_focused). Distinct from the resolved
      positive-terminal-outcome item above, which did not pin iteration count.

## Test-shaper review follow-ups — pass 3 (2026-06-16)

- [x] Tie the standalone result text to the converged `final-summary` step in
      `assert-converged-standalone-surfaces-review-complete`
      (`workflow_delegate_review_step_live_test.clj`). The helper injects a
      per-call `reply-prefix` into the `final-summary` stub reply
      (`"<prefix>\n\nPASS_STATUS: REVIEW_COMPLETE"`) but **no assertion references
      `reply-prefix`** — so it is an unasserted varying axis (test-shaper:
      minimal_incidental_variation). The only result-text assertion,
      `(= 1 (count (re-seq #"PASS_STATUS: REVIEW_COMPLETE" result-text)))`, is
      *also* satisfied by any review-prompt's bare `PASS_STATUS: REVIEW_COMPLETE`
      reply, so the helper does not actually prove `:psi.workflow/result` is the
      converged `final-summary`'s text (its docstring claims "the converged
      `final-summary` … is the step whose yielded text surfaces as
      `:psi.workflow/result`"). A regression surfacing some *other* step's
      REVIEW_COMPLETE reply as the standalone result would still pass. Add
      `(is (str/includes? result-text reply-prefix) …)` (the prefix is unique to
      the final-summary reply) so the `reply-prefix` axis carries assertion
      meaning and the ordering/plumbing claim has meaningful failure signal
      (test-shaper: meaningful_failures / behavior_focused; assertion matches the
      helper's stated contract).

## Docs review follow-ups (2026-06-16)

- [x] Document the new `:on-max-iterations` routing-directive key in the
      grammar/IR **reference** docs, not just the `doc/workflows.md` prose guide.
      The authored grammar (`model.clj`) and IR (`ir.clj`)
      `routing-directive-schema` now carry an optional `:on-max-iterations`
      target (valued like `:goto`) with a cross-field constraint that it is only
      valid alongside `:max-iterations`, but the canonical reference productions
      still document only `:max-iterations`:
      - `doc/workflow-grammar.md`: `transition-map ::= {:goto goto-target
        max-iterations-clause?}` (and the `control-flow` block) omit
        `:on-max-iterations`.
      - `doc/workflow-ir.md`: the IR `transition-map ::= {:goto goto-target
        :max-iterations? pos-int}` production, the "The IR uses:" key list
        (`:judge`/`:on`/`:max-iterations`), and the Routing-rules section omit
        `:on-max-iterations` and its exhaustion-routing semantics
        (present → route to target with `:status :running`; absent → hard-fail
        `:reason :iteration-exhausted`).
      Add the optional key + the `:max-iterations`-required constraint to both
      reference grammars so a workflow author reading the grammar/IR reference
      can discover the directive. (Optionally note it in
      `doc/workflow-grammar-concepts.md` §`:max-iterations`, which currently
      describes only the iteration bound.)
