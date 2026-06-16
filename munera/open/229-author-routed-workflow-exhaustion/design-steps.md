# 229 — Design/plan review follow-up steps

Actionable follow-ups raised by review passes. Tick when resolved in
plan.md / design.md (steps.md is read-only review context).

## Ambiguity review

- [ ] **Terminal-yield resolution for two summary steps is underspecified
      (Slice 2/3, DI-1).** DI-1 makes both `final-summary` and
      `final-summary-not-converged` explicitly terminal, which fixes internal
      `:next` fall-through — but it does not specify (a) the *relative order* of
      the two summary steps in `:steps`, nor (b) how each consumer resolves the
      workflow's terminal `:yield :text` to the **executed** summary. Two code
      paths diverge: the lifecycle **delegate gate** path
      (`statechart_runtime/delegate.clj` → `terminal_contract/terminal-result-envelope`)
      prefers `:terminal-outcome :result-envelope` and reads the *actually-executed*
      terminal step (works regardless of order), but the **standalone `/delegate`
      result-text** path (`agent_session/mutations/canonical_workflows.clj`, and
      `terminal_contract/terminal-yielded-text`) keys strictly off
      `(last (:step-order …))`. If `final-summary-not-converged` is appended last,
      a *converged* standalone run surfaces the never-run not-converged step's
      empty text — contradicting D5 ("standalone output accepted as useful").
      Resolve in plan.md: specify summary-step ordering and state which
      resolution path each consumer (lifecycle gate vs standalone `/delegate`)
      uses, and confirm the converged path surfaces the converged summary's
      `PASS_STATUS: REVIEW_COMPLETE` text in *both* paths. Add a test that locks
      the converged standalone result text (not just definition-level routing).

- [ ] **`N follow-up iterations` source unspecified (Slice 2/3 not-converged
      summaries).** The template is to say "design/plan review did not converge
      after N follow-up iterations", but no contribution/source for `N` is
      defined and the not-converged summary step's contributions list does not
      include an iteration count. Decide and record in plan.md: emit the literal
      `:max-iterations` cap (e.g. 3 / 5), source an actual count if one is
      available, or drop the count from the template wording.

## Inconsistency review

- [ ] **Plan assumes a green `review-task-design-test` baseline, but it is
      already RED (Slice 2).** Verified: `review-task-design-test`
      (`workflow_definitions_test.clj:121`) asserts
      `{:goto "design-review" :max-iterations 6}` while the current
      `review-task-design.edn` (post `de19cc5bf` "lower loop cap to 3") is
      `:max-iterations 3` → 1 failure (`-6 +3`). The plan's Slice 2 "extend the
      existing `review-task-design-test`" instruction and its "focused
      workflow-loader Scry green" exit criterion both presume the test baseline
      is green/accurate. Resolve in plan.md: note the pre-existing stale
      `:max-iterations` assertion (6→3) must be corrected as part of Slice 2's
      `review-task-design-test` edit (alongside the new step-order +
      `:on-max-iterations` assertions), and that the Slice-2 baseline is not
      green to begin with. (Check `review-task-plan-test` / `review-step-test`
      `:max-iterations` assertions for the same drift while there.)

- [ ] **`task-lifecycle-test` is positionally hard-coded; the plan's
      "extend OR add a new 229 test" alternative is insufficient (Slice 2/3).**
      `task-lifecycle-test` (`workflow_definitions_test.clj:602`) hard-asserts
      `(= 9 (count steps))`, the exact ordered `:name` vector, the exact `:type`
      vector `(concat (repeat 5 :delegate) [:invoke :delegate :session :session])`,
      positional `(nth steps 5/6/7/8)`, and `(= (repeat 9 {}) ...)` for
      `:yields`/`:terminal-contract`. Adding `check-design-review-status` +
      `final-summary-design-not-converged` (Slice 2) and
      `check-plan-review-status` + `final-summary-plan-not-converged` (Slice 3)
      breaks every one of these. Plan Slice 2 offers "extend the existing
      task-lifecycle definition test, **or** add a `229` definition test" — the
      "add a separate test" option alone leaves the existing
      `task-lifecycle-test` failing, contradicting Risk R3 ("update them in the
      same slice"). Resolve in plan.md: the existing `task-lifecycle-test` MUST
      be updated in Slices 2 and 3 (count, name vector, type vector, positional
      indices, yields-repeat); a separate 229 test is additive-only, not a
      substitute.
