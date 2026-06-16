# 229 — Plan

Approach, sequencing, and decisions for implementing the author-routed workflow
exhaustion primitive and the review-did-not-converge handback. Reads `design.md`
as the authority for *what* and *why*; this file is *how*. Decision tags `D1–D5`
refer to the settled decisions in `design.md`.

## Strategy

Vertical slices, **engine before workflows**. Slice 1 lands the
`:on-max-iterations` primitive end-to-end through the four routing layers with
its own unit tests, and is **behaviour-inert** for every existing workflow
(nothing authors it yet) — independently shippable and reviewable. Slices 2–3
then consume it (design review, then the symmetric plan review), each adding the
lifecycle gate and handback. Slice 4 is docs/coherence.

Each slice keeps the change `small` (one intent, one rule + test cluster) and
preserves behaviour for code paths it does not explicitly change.

## Implementation decision — terminal fall-through (DI-1)

A non-judged leaf session step routes `:actor/done → next-step-target`, which is
`:completed` **only when the step is last in `:steps` order**
(`statechart.clj` `next-step-target`). `review-task-design` /
`review-task-plan` currently rely on their `final-summary` being the last step
to terminate. Appending a second summary (`final-summary-not-converged`) would
make the converged `final-summary` no longer last, so its `:next` would fall
through into the not-converged summary — a silent wrong-path bug.

Resolution: make **both** summary steps in each review workflow explicitly
terminal using the established `task-lifecycle` idiom —
`:judge {:type :invoke :operation "workflow/constant-routing" :args {:route "DONE"}}`
plus `:on {"DONE" {:goto :done}}` — so termination is order-independent. This
also gives both summaries a judge turn, which is where the required
`PASS_STATUS:` line (D1) is produced and read by the lifecycle gate.

## Slice 1 — `:on-max-iterations` engine primitive (inert)

Files:
- `components/workflow-runtime/src/psi/workflow_runtime/model.clj`
  `routing-directive-schema` — add optional `:on-max-iterations`
  (`[:or [:enum :next :previous :done] :string]`) and wrap the directive map in
  `[:and <map> [:fn require-max-iterations-when-on-max]]` so
  `:on-max-iterations` without `:max-iterations` is rejected with a clear error
  (D3).
- `components/workflow-runtime/src/psi/workflow_runtime/ir.clj`
  `routing-directive-schema` — same optional key
  (`[:or [:enum :next :previous :done] step-name-schema]`) and the same
  cross-field reject constraint (D3).
- `components/workflow-runtime/src/psi/workflow_runtime/target_ir_compiler.clj`
  `compile-routing-table` — in the per-outcome `cond->`, additionally
  `(contains? transition :on-max-iterations) (assoc :on-max-iterations (:on-max-iterations transition))`.
- `components/workflow-runtime/src/psi/workflow_runtime/statechart.clj`
  `compile-routing-transitions` — in the `(if max-iterations …)` branch, compute
  the exhaustion target: when the directive has `:on-max-iterations`, resolve it
  through the same goto→target logic used for `:goto`
  (`:next`/`:previous`/`:done`/step-name) and use it as the exhaustion
  transition `:target`; otherwise keep `:target :failed`. No change to
  `judged-routing-transition` (D2): an exhaustion target ≠ `:failed` already
  dispatches `:judge/record` and does not mark the run failed.

Refactor note: the goto→target resolution currently lives inline in
`compile-routing-transitions` (the `(case goto …)` block). Extract it to a small
named helper so both the `:goto` target and the `:on-max-iterations` target use
one implementation (avoid divergence). Keep it private to `statechart.clj`.

Tests:
- `ir_test.clj` / `model_test.clj` — `:on-max-iterations` with `:max-iterations`
  validates; without `:max-iterations` is rejected (both schemas).
- `target_ir_compiler_test.clj` — `:on-max-iterations` is threaded model→IR.
- `statechart_test.clj` — extend the existing CHANGED/max-iterations cases: with
  `:on-max-iterations`, an exhausted signal routes to the author target's acting
  state and dispatches `:judge/record` (run not `:failed`); without it, exhausted
  still routes to `:failed` + `:iteration/exhausted` (regression-lock current
  behaviour).

Exit: focused workflow-runtime Scry suites green; clj-kondo clean; no existing
workflow definition changes.

## Slice 2 — review-task-design handback + lifecycle design gate

Files:
- `.psi/workflows/review-task-design.edn`:
  - `design-follow-up` `:on` gains
    `:on-max-iterations "final-summary-not-converged"` alongside the existing
    `{:goto "design-review" :max-iterations 3}`.
  - Existing `final-summary` (converged): add the explicit-terminal judge + `:on`
    (DI-1) and update its template to emit a required
    `PASS_STATUS: REVIEW_COMPLETE` line (replacing the current "do not output
    REPEAT/DONE/control tokens" instruction, D1/D5).
  - New `final-summary-not-converged` session step: contributions sourced from
    `:workflow-original` + the three `design-review` per-prompt
    `:final-llm-reply` outputs; template produces a "design review did not
    converge after N follow-up iterations" user summary and emits a required
    `PASS_STATUS: ACTIONABLE_FEEDBACK` line; explicit-terminal judge + `:on`
    (DI-1).
- `.psi/workflows/task-lifecycle.edn`:
  - New `check-design-review-status` invoke-step after `review-task-design`,
    mirroring `check-implementation-review-status`:
    `:operation "workflow/constant-routing" :args {:route "DONE"}`,
    `:judge {:type :invoke :operation "workflow/pass-status-routing"
             :args {:text {:from {:step "review-task-design" :yield :text}}
                    :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}`,
    `:on {"DONE" {:goto "create-task-plan"}
          "REPEAT" {:goto "final-summary-design-not-converged"}}`.
  - New `final-summary-design-not-converged` session step (mirrors
    `final-summary-without-extraction`): contributions from `:workflow-original`
    + `review-task-design` yield; template explains the lifecycle stopped at the
    design stage because design review did not converge, hands back to the human,
    does not extract knowledge; `:on {"DONE" {:goto :done}}`.

Tests:
- `components/workflow-loader/test/.../workflow_definitions_test.clj`
  `review-task-design-test`: update the step-order vector to include
  `final-summary-not-converged`; assert `design-follow-up` `:on` now carries
  `:on-max-iterations "final-summary-not-converged"`; assert both summaries are
  explicitly terminal and carry their PASS_STATUS lines.
- Add task-lifecycle definition coverage (extend the existing task-lifecycle
  definition test, or add a `229` definition test under `workflow-loader/test`)
  asserting the `check-design-review-status` gate routes DONE→`create-task-plan`
  and REPEAT→`final-summary-design-not-converged`, and that
  `final-summary-design-not-converged` terminates with `:goto :done`.
- If a runtime routing test exists for the lifecycle gate pattern
  (`workflow_review_step_routing_test`), add a design-gate analogue; otherwise
  rely on definition-level coverage plus Slice 1 runtime coverage.

Exit: focused workflow-loader + affected runtime suites green; clj-kondo clean.

## Slice 3 — review-task-plan handback + lifecycle plan gate (symmetric)

Mirror Slice 2 for the plan review:
- `.psi/workflows/review-task-plan.edn`: `plan-follow-up` `:on` gains
  `:on-max-iterations "final-summary-not-converged"`; converged `final-summary`
  becomes explicit-terminal + `PASS_STATUS: REVIEW_COMPLETE`; new
  `final-summary-not-converged` (sourced from the two `plan-review` per-prompt
  replies) + `PASS_STATUS: ACTIONABLE_FEEDBACK` + explicit-terminal.
- `.psi/workflows/task-lifecycle.edn`: new `check-plan-review-status` gate after
  `review-task-plan` → `{"DONE" {:goto "implement-task"}
  "REPEAT" {:goto "final-summary-plan-not-converged"}}`; new
  `final-summary-plan-not-converged` handback step (`:goto :done`).
- Tests: `review-task-plan-test` step-order + routing updates; task-lifecycle
  plan-gate coverage.

Exit: focused workflow-loader suites green; clj-kondo clean.

## Slice 4 — docs + coherence

- `doc/workflows.md`: document the `:on-max-iterations` routing-directive key and
  the design/plan non-convergence handback behaviour in `task-lifecycle`.
- `CHANGELOG.md` `[Unreleased]`:
  - `Added`: `:on-max-iterations` author-routed exhaustion target on workflow
    routing directives.
  - `Changed`: `task-lifecycle` stops and hands back at the design/plan stage
    when the corresponding review does not converge (was hard-fail).
- Coherence sweep: `mementum/state.md` workflow-routing bullet mentions the new
  primitive if warranted; re-read edited files (`sync`).

## Risks

- **R1 — terminal fall-through (DI-1).** Mitigated by making both summaries
  explicitly terminal; locked by definition tests asserting termination.
- **R2 — PASS_STATUS reliability.** The gate parses an LLM-produced
  `PASS_STATUS:` line. Mitigated: each summary template hardcodes exactly one
  required status string (structurally determined by which step ran, D1), and
  `pass-status-routing` errors on missing/ambiguous status — failing loud, not
  silent. Same reliability profile as the existing implementation-review gate.
- **R3 — definition-test drift.** Step-order assertions in
  `workflow_definitions_test.clj` are brittle to added steps; update them in the
  same slice that changes each `.edn`.
- **R4 — standalone behaviour change (D5).** Standalone `/delegate
  review-task-design`/`-plan` now emits a `PASS_STATUS:` line. Accepted; noted in
  docs.

## Out of scope (restate)

No change to actor-retry/`:max-attempts`, to `:iteration/exhausted` accounting
itself, or new generic routing operations.
