# 229 — Design/plan review follow-up steps

Actionable follow-ups raised by review passes. Tick when resolved in
plan.md / design.md (steps.md is read-only review context).

## Ambiguity review

- [x] **Summary-template PASS_STATUS emission is underspecified vs. the strict
      parser and the PASS_STATUS-bearing contributions (Slice 2/3, D1/DI-1).**
      The lifecycle gates read each summary's `:yield :text` through
      `workflow/pass-status-routing`
      (`agent_session/workflow/routing.clj` `parse-pass-status-routing`), which
      is strict: it errors `:ambiguous-pass-status` when **more than one** line
      begins with `PASS_STATUS:`, and only treats a line as a valid status when
      it is *exactly* `PASS_STATUS:<space><TOKEN>` (column 0, single space, bare
      token, nothing else — `exact-known?` = `(= raw-value (str " " trimmed))`).
      Both summary steps' `:contributions` include the design-review (resp.
      plan-review) per-prompt `:final-llm-reply` outputs, and every review prompt
      (`review-task-design-ambiguity-review.md` etc.) ends with its own
      `PASS_STATUS: …` line — so the summary LLM's context contains 3 (design) /
      2 (plan) PASS_STATUS lines. The plan says to "emit a required PASS_STATUS
      line, **replacing** the existing 'do not output REPEAT/DONE/control tokens'
      instruction", but (a) the converged `final-summary` templates contain *two*
      anti-control-token guards ("Respond with a concise summary …, not an
      internal control token" **and** "Do not output REPEAT or DONE unless
      quoting prior workflow behavior") and "replace the instruction" is
      ambiguous about which/both, and keeping the first while mandating a
      PASS_STATUS line is self-contradictory; and (b) removing the anti-echo
      guard while feeding the LLM context that contains PASS_STATUS lines invites
      the summary to echo/quote them, yielding >1 PASS_STATUS line → gate
      `:ambiguous-pass-status` error → lifecycle hard-fail (the very failure mode
      this task removes). Resolve in plan.md: specify the exact required line
      format the parser accepts (single `PASS_STATUS: <TOKEN>` line, column 0,
      sole occurrence, placed last), explicitly retain an anti-echo instruction
      so the summary never reproduces the contributed review replies' PASS_STATUS
      lines, and reconcile both existing anti-control-token sentences with the
      new mandatory line. Applies to the converged `final-summary` and the new
      `final-summary-not-converged` in **both** `review-task-design.edn` and
      `review-task-plan.edn`.

- [x] **Terminal-yield resolution for two summary steps is underspecified
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

- [x] **`N follow-up iterations` source unspecified (Slice 2/3 not-converged
      summaries).** The template is to say "design/plan review did not converge
      after N follow-up iterations", but no contribution/source for `N` is
      defined and the not-converged summary step's contributions list does not
      include an iteration count. Decide and record in plan.md: emit the literal
      `:max-iterations` cap (e.g. 3 / 5), source an actual count if one is
      available, or drop the count from the template wording.

- [ ] **`task-lifecycle.edn` insertion position / fall-through of the new
      gate + handback steps is unspecified, and the DI-1 fall-through hazard is
      not addressed for the lifecycle (Slice 2/3).** DI-1/DI-2/R1 reason about
      `:next` fall-through (`statechart.clj` `next-step-target` /
      `compile-leaf-step` `:actor/done → next-step-target`) **only** for the
      `review-task-design.edn` / `review-task-plan.edn` summary steps. But the
      same hazard applies to `task-lifecycle.edn`: its `:delegate` steps
      (`create-task-plan`, `review-task-plan`, `implement-task`, …) are
      non-judged leaf steps that route `:actor/done` to the **next step in
      `:steps` order**, and the plan (Slice 2 line ~216, Slice 3 line ~287)
      specifies the new gates' `:on` routing and the test count bumps
      (9→11→13) but never pins **where** `check-design-review-status`,
      `final-summary-design-not-converged`, `check-plan-review-status`, and
      `final-summary-plan-not-converged` are placed within `:steps`. The
      placement is correctness-critical: e.g. if `final-summary-design-not-converged`
      is inserted immediately after `create-task-plan`, then on the converged
      (DONE) path `create-task-plan` falls through into the not-converged
      handback summary — the exact silent wrong-path bug DI-1 fixes for the
      review workflows, here reintroduced in the lifecycle. (This is distinct
      from the existing positional-`task-lifecycle-test` follow-up, which is
      about updating the *test* and presupposes the positions are already
      known.) Resolve in plan.md: specify the exact `:steps` insertion
      positions for all four new lifecycle steps — each gate immediately after
      its delegate (`review-task-design` / `review-task-plan`) so the delegate
      falls through into the gate and the gate's DONE goto continues the main
      flow, and both `final-summary-*-not-converged` handbacks placed so no
      preceding leaf step can fall through into them (e.g. appended after the
      existing terminal summaries, mirroring `final-summary-without-extraction`
      being last) — and state the resulting ordered name/type vectors the
      updated `task-lifecycle-test` must assert.

## Inconsistency review

- [x] **Plan assumes a green `review-task-design-test` baseline, but it is
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

- [x] **`task-lifecycle-test` is positionally hard-coded; the plan's
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

- [ ] **`steps.md` execution checklist is out of sync with the hardened
      `plan.md` mandates (Slice 2/3).** Prior plan-review passes hardened
      `plan.md` with several MUST-do test items, but `steps.md` (the execution
      surface) was never re-synced, so an implementer ticking `steps.md` alone
      would omit mandatory work and produce an implementation that violates
      `plan.md`. Concretely, `steps.md` Slice 2/3 is missing: (a) the
      **pre-existing RED `review-task-design-test` `:max-iterations` 6→3 fix**
      that `plan.md` Slice 2 requires in the same `review-task-design-test` edit
      (`steps.md:40` only says "step-order + `:on-max-iterations` +
      terminal/PASS_STATUS assertions"); (b) the **converged standalone
      result-text runtime test** (DI-2) mandated by `plan.md` Slice 2 *and*
      Slice 3 — absent from `steps.md` entirely; (c) the constraint that the
      **existing `task-lifecycle-test` MUST be updated in place** (count 9→11→13,
      name/type vectors, positional `nth`, `repeat` counts) with a separate `229`
      test additive-only — `steps.md` only says the generic "task-lifecycle
      definition coverage for the design/plan gate + handback routing", leaving
      the positional-update mandate (R3) implicit; and (d) the **DI-3 "no literal
      iteration count" wording constraint** on the not-converged summaries
      (`steps.md:30`/Slice 3 just say "PASS_STATUS: ACTIONABLE_FEEDBACK,
      explicit-terminal"). Resolve by updating `steps.md` Slice 2/3 to enumerate
      these four mandated sub-tasks so the execution checklist matches `plan.md`.


- [ ] **`design.md` scope item 6 "replaces the instruction" contradicts the
      superseding `plan.md` DI-4 "keep (a), rewrite (b)" contract (Slice 2/3).**
      `design.md` scope item 6 (and item 7 by "identical treatment") states the
      summary change "**replaces** the existing 'do not output
      REPEAT/DONE/control tokens' instruction in those summaries with a single
      required `PASS_STATUS:` line". `plan.md` DI-4 explicitly **supersedes** this
      ("This DI supersedes the looser D1 phrasing … 'replacing the existing …
      instruction'") and mandates the opposite mechanism: do **not** replace —
      **keep** sentence (a) ("concise summary … not an internal control token")
      and **rewrite** sentence (b) into the precise single-line + anti-echo rule.
      `design.md` (the authority for *what/why*) was never reconciled, so it and
      `plan.md` now prescribe contradictory template edits ("replace" vs
      "keep+rewrite"); an implementer cross-referencing the authority gets the
      wrong mechanism, risking removal of the anti-control-token guard the DI-4
      contract requires. (Distinct from the resolved loop-2 ambiguity item, which
      only hardened `plan.md` DI-4 and did not touch `design.md`.) Resolve by
      updating `design.md` items 6/7 (per change_chain: intent/decision lives in
      design) to drop the "replaces the existing instruction" phrasing and align
      with DI-4 — or replace it with an explicit pointer to `plan.md` DI-4 as the
      authoritative template-contract.
