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

- [x] **`task-lifecycle.edn` insertion position / fall-through of the new
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

- [x] **DI-2 converged-standalone result-text test conflates the synthetic
      proof harness with the real loaded `.edn`, leaving the test's own
      construction ambiguous (Slice 2/3, DI-2).** DI-2's test note says to "drive
      `review-task-design` (resp. `review-task-plan`) standalone to a converged
      terminal via the `workflow_review_step_routing_test` harness (stub
      `psi.agent-session.turn/prompt-execution-result-in!` to return
      `REVIEW_COMPLETE` for the review step, then the converged summary text), run
      it through `execute-workflow-run`, and assert `:psi.workflow/result`
      contains `PASS_STATUS: REVIEW_COMPLETE`". Verified against code, that
      instruction is ambiguous on three coupled points, and the most natural
      reading does **not** prove what the test claims to prove:
      (a) **Which definition?** The named harness drives *synthetic* proof
      definitions (`conditional-review-design-definition` /
      `-plan-definition`, registered as `review-task-design-proof` etc.) whose
      converged `final-summary` is just `{:type :template :text "final-summary"}`
      — it carries no `PASS_STATUS:` line. The test's stated purpose is to lock
      that the **DI-4 template wording** in the *real* `.psi/workflows/
      review-task-design.edn` / `review-task-plan.edn` converged `final-summary`
      yields `PASS_STATUS: REVIEW_COMPLETE`. Only the **real loaded `.edn`**
      definition (via workflow-loader) can lock that; the synthetic harness
      definition cannot. The note does not say which to use.
      (b) **Which execution entry point?** `:psi.workflow/result` is produced by
      the `canonical_workflows/execute-workflow-run` mutation, but the harness's
      `execute-conditional-review-proof!` / `execute-run!` path does not go
      through that mutation (it calls `workflow-execution/execute-run!` with a
      custom `:workflow-execute-actor-turn-fn`). "via the harness … run it through
      `execute-workflow-run`" names two different mechanisms without saying how
      the harness's actor stub plugs into the mutation path (or whether the test
      should bypass the harness entirely and stub at `prompt-execution-result-in!`
      under `execute-workflow-run`).
      (c) **Multi-prompt convergence underspecified.** "stub … to return
      `REVIEW_COMPLETE` for the review step" treats the review step as a single
      turn, but the real `design-review` is a 3-prompt (resp. `plan-review` a
      2-prompt) step judged by `workflow/pass-feedback-routing`, which routes DONE
      only when **every** per-prompt `:final-llm-reply` carries exactly one
      `PASS_STATUS: REVIEW_COMPLETE` line. The stub must therefore supply a
      convergent `PASS_STATUS: REVIEW_COMPLETE` reply for **each** per-prompt turn
      (keyed by per-prompt prompt text, per the existing
      `design-review-full-pass-routing-test` pattern), then the converged
      `final-summary` text — not one combined `REVIEW_COMPLETE`.
      Resolve in plan.md: specify that the converged-standalone result-text test
      loads the **real** `review-task-design.edn` / `review-task-plan.edn`
      definitions and drives them through `execute-workflow-run` (stubbing the
      per-prompt actor turns to all-`REVIEW_COMPLETE` plus the converged summary
      text), rather than reusing the synthetic `conditional-review-*` proof
      definitions; or, if a synthetic definition is intended, state that the
      synthetic converged `final-summary` template must replicate the DI-4
      `PASS_STATUS: REVIEW_COMPLETE` wording for the assertion to be meaningful.

- [x] **Slice 1 engine change is incomplete: runtime exhaustion is decided
      judge-side in `workflow-judge/evaluate-routing`, not in the statechart
      transition the plan modifies (Slice 1, D2).** The plan (design "Context",
      Slice 1, D2) treats exhaustion routing as governed solely by
      `statechart.clj` `compile-routing-transitions` (the `:judge/signal`
      exhaustion transition guarded by `iter >= max`, target `:failed`, with
      `judged-routing-transition` dispatching `:iteration/exhausted`), and
      explicitly states "No change to `judged-routing-transition`" and changes
      only `compile-routing-transitions`. But there is a **second, parallel
      exhaustion decision** the plan never mentions:
      `components/workflow-judge/src/psi/workflow_judge.clj` `evaluate-routing`
      returns `{:action :fail :reason :iteration-exhausted :step-id target ...}`
      when `check-iteration-limit` sees the target step's `:iteration-count` ≥
      `:max-iterations`. In `statechart_runtime.clj` `:judge/enter`, that
      `:action :fail` is queued as **`:judge/failed`** (not `:judge/signal`), and
      the `compile-judged-step` `:judge/failed` transition runs `:judge/record`,
      whose `else` branch marks the run `:status :failed`
      `:reason :iteration-exhausted`. Verified against the **integration runtime**
      test `workflow_review_step_routing_test/review-pass-loop-iteration-limit-failure-test`
      (`:689`/`:703`): the real exhausted run terminates with
      `:terminal-outcome {:reason :iteration-exhausted :step-id "design-follow-up"}`
      — i.e. the judge-side path, keyed off the `:max-iterations`-bearing step,
      **not** the statechart `:iteration/exhausted` action (whose reason is
      `:iteration-limit-reached`, exercised only by the isolated
      `statechart_test/iteration-exhaustion-fires-action-test`, which feeds
      `:judge/signal` directly and bypasses `evaluate-routing`). Consequence: as
      planned, redirecting the statechart `:judge/signal` exhaustion transition to
      the `:on-max-iterations` target is **dead code at runtime** — the
      `:judge/signal` exhaustion guard never fires for these review workflows
      because `evaluate-routing` short-circuits to `:fail`→`:judge/failed` first,
      so `review-task-design`/`-plan` would still hard-fail with
      `:iteration-exhausted` and never reach `final-summary-not-converged`. AC-3
      ("routes the exhaustion transition to the resolved `:on-max-iterations`
      target") and AC-4/AC-5/AC-6 are not achievable from the planned change set.
      Resolve in plan.md (and reconcile design "Context"/D2 in design.md): either
      (a) spike-confirm which site governs runtime exhaustion and, if it is
      `evaluate-routing` (as the runtime test shows), extend Slice 1 to thread
      `:on-max-iterations` through `evaluate-routing` so judge-side exhaustion
      returns `{:action :goto :target <on-max-iterations-target>}` instead of
      `{:action :fail :reason :iteration-exhausted}` when the directive carries
      `:on-max-iterations` (and confirm the resulting `:judge/signal`/`:judge/record`
      path routes to the author target without marking the run failed); or
      (b) explicitly document why the statechart-only change suffices end-to-end,
      backed by an integration (not pure-statechart) test that drives a real
      exhausting review loop through `execute-workflow-run` and asserts it reaches
      the author target rather than `:status :failed`. The Slice 1 "exit:
      focused workflow-runtime Scry green" criterion must include such an
      integration-level exhaustion-routing assertion, since the existing
      `statechart_test` cannot detect this gap.

- [x] **DI-2 converged-standalone runtime test cannot lock the DI-4 template
      wording it claims to verify, because the model reply is stubbed (Slice
      2/3, DI-2/DI-4).** DI-2 (loop-4 resolution) states the converged-standalone
      result-text test "lock[s] that the **DI-4 template wording** in the real
      `.psi/workflows/review-task-design.edn` / `review-task-plan.edn` converged
      `final-summary` yields a parser-accepted `PASS_STATUS: REVIEW_COMPLETE`",
      and uses that as the justification for loading the real `.edn` rather than a
      synthetic def. But the prescribed mechanism stubs
      `psi.agent-session.turn/prompt-execution-result-in!` (the **model
      response**) for the converged `final-summary` turn — so the asserted
      `PASS_STATUS: REVIEW_COMPLETE` line is whatever the **stub returns**, not
      what the template instructs. The `final-summary` template text is the
      *prompt* sent to the model, never the model's *output*; stubbing the output
      bypasses the template entirely. Loading the real `.edn` therefore does not
      lock the DI-4 wording at all (a synthetic def with a bare
      `"final-summary"` template would yield the identical assertion, since the
      stub supplies the PASS_STATUS line either way). What the runtime test
      actually locks is the **ordering/plumbing** invariant: that the converged
      `final-summary`, ordered last per DI-2, is the step whose yielded text
      surfaces through the standalone `(last :step-order)` path as
      `:psi.workflow/result`. Resolve in plan.md: (1) restate the DI-2 runtime
      test's purpose to "lock summary-step ordering + that the converged
      summary's yielded text surfaces via the standalone `(last :step-order)`
      path" (not "lock the DI-4 template wording"); and (2) make the
      **definition-level** `review-task-design-test`/`review-task-plan-test` the
      authority that locks the DI-4 template **text** — assert the converged
      `final-summary` template string contains exactly the required
      `PASS_STATUS: REVIEW_COMPLETE` line in the DI-4 column-0/single-space/
      sole-occurrence form (and the not-converged template
      `PASS_STATUS: ACTIONABLE_FEEDBACK`), since the existing Slice 2/3 bullet's
      vague "carry their PASS_STATUS lines" does not pin the exact DI-4 format
      the strict `parse-pass-status-routing` requires.

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

- [x] **R3/DI-5's enumerated `task-lifecycle-test` update scope is incomplete —
      it omits the `(take 5 steps)`-shaped assertions that also break (Slice
      2/3).** The resolved positional-`task-lifecycle-test` item and DI-5
      enumerate the assertions to update as "count, name vector, type vector,
      positional `nth` indices, yields-`repeat`" (R3) / "count 9→11→13,
      name/type vectors, index shifts and `repeat` count bumps" (DI-5). But the
      live `task-lifecycle-test` (`workflow_definitions_test.clj`) contains
      **three further assertions keyed off `(take 5 steps)`** that this
      enumeration never names and that also break once an `:invoke` gate is
      inserted at `:steps` index 1 (Slice 2) and index 4 (Slice 3):
      (1) `(is (= first-five-targets (mapv :target (take 5 steps))))` (`:643`) —
      `check-design-review-status`/`check-plan-review-status` have **no
      `:target`**, so `take 5` no longer yields five delegate targets;
      (2) `(is (= (repeat 5 standard-prompt) (mapv :prompt-string (take 5 steps))))`
      (`:645-646`) — the gate steps have **no `:prompt-string`**;
      (3) `(is (= (repeat 6 [{:type :source :from :workflow-original}])
      (mapv :context (concat (take 5 steps) [extraction-step]))))` (`:667-668`) —
      the gate steps have **no `:context`**, and `extraction-step`'s `nth` index
      also shifts. These are not mechanical `nth`-index/`repeat`-count edits: the
      whole `(take 5 steps)` "first five are delegates" assumption is invalidated
      by the index-1/index-4 insertions, so the assertions must be **restructured**
      (e.g. select the delegate steps by name/filter rather than positional
      `take 5`). Because the plan presents R3/DI-5 as the exhaustive list of what
      must change in `task-lifecycle-test`, an implementer following it literally
      leaves these three assertions RED, contradicting the "focused
      workflow-loader Scry green" exit criterion. Resolve in plan.md: extend the
      R3/DI-5 task-lifecycle-test update scope to also cover the three
      `(take 5 steps)`-based assertions (`first-five-targets` targets,
      `(repeat 5 standard-prompt)` prompt-strings, `(repeat 6 …)` contexts),
      noting they require restructuring away from the `take 5` positional
      assumption — not just index/`repeat`-count bumps — for both Slice 2 (gate at
      index 1) and Slice 3 (gate at index 4).

- [ ] **`design.md` D5 "standalone output accepted as useful" contradicts
      `plan.md` R5/DI-2 "not-converged standalone surfaces empty/degraded result
      text" (Slice 2/3).** `design.md` D5 ("Standalone output") states that
      "Adding the `PASS_STATUS:` line to the review-task-design / review-task-plan
      final summaries also changes their output when run directly via `/delegate`.
      **Accepted as useful**" — framed across **both** summaries (and scope item 6
      repeats the D5 note that "this PASS_STATUS line also appears when
      review-task-design is run standalone … accepted"). But `plan.md` DI-2 +
      **R5** establish the opposite for the **not-converged** summary: because the
      standalone result-text path keys off `(last :step-order)` (verified:
      `canonical_workflows/execute-workflow-run` reads
      `(last (:step-order …))`) and the converged `final-summary` is ordered last,
      a standalone non-converging `/delegate review-task-design`/`-plan` run
      surfaces **empty** result text — the `final-summary-not-converged`'s
      `PASS_STATUS: ACTIONABLE_FEEDBACK` line never reaches the user, "Accepted" by
      R5 as a **degradation** (the handback is a lifecycle-only concern), not a
      useful standalone feature. So `design.md` (authority for what/why) tells an
      implementer/reviewer that the not-converged standalone PASS_STATUS output is
      useful, while `plan.md` says it is empty/degraded-and-accepted. The loop-1
      DI-2 ambiguity item only reconciled the *converged* standalone case (by
      ordering converged last); `design.md` D5 was never updated for the
      not-converged degradation R5 introduces. (Distinct from the open
      design.md item-6/7 inconsistency, which is about "replaces the instruction"
      vs DI-4 keep+rewrite.) Resolve by reconciling `design.md` D5 (per
      change_chain, intent/decision lives in design): scope "accepted as useful"
      to the **converged** standalone summary, and record that the **not-converged**
      standalone path surfaces empty result text (degraded, accepted) per R5 — or
      replace D5's standalone framing with an explicit pointer to plan.md R5/DI-2
      as the authoritative standalone-output contract.
