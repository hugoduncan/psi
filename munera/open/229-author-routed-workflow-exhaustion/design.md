# 229 — Author-routed workflow exhaustion + review-did-not-converge handback

## Intent (why)

Today, when a judged workflow loop exhausts its `:max-iterations`, the
statechart hardcodes the exhaustion transition to `:failed`. The whole workflow
run hard-fails. There is no way for a workflow author to say "if this loop never
converges, route somewhere clean instead of crashing".

Concretely: `review-task-design` loops `design-review ↔ design-follow-up` up to
`:max-iterations 3`. If the design never converges, the run dies with a hard
failure, and `task-lifecycle` either crashes or (worse) has no clean place to
stop. We want an unconverged design review to **stop the lifecycle at the design
stage and hand control back to the human** with a useful summary — not proceed
to plan on a bad design, and not hard-fail.

This requires two layered changes:

1. **Engine**: let routing directives name an author-chosen exhaustion target
   (`:on-max-iterations`) instead of always routing exhaustion to `:failed`.
2. **Workflows**: use that capability so `review-task-design` (and
   `review-task-plan`) route non-convergence to a clean
   "did-not-converge" summary, and `task-lifecycle` gates on the design-review
   (and plan-review) outcome — mirroring the existing
   `check-implementation-review-status` → `final-summary-without-extraction`
   pattern — to stop and hand back rather than continue.

## Context (current behaviour)

Routing flows through four layers in `components/workflow-runtime`:

- `model.clj` `routing-directive-schema` — schema for the **authored** EDN
  routing directive: `{:goto <target> (:max-iterations N)?}`. `:goto` is
  `[:or [:enum :next :previous :done] :string]`.
- `target_ir_compiler.clj` `compile-routing-table` — translates an authored
  routing table into IR directives (currently copies `:goto` and, when present,
  `:max-iterations`).
- `ir.clj` `routing-directive-schema` — IR schema: `{:goto <target>
  (:max-iterations N)?}`.
- `statechart.clj` `compile-routing-transitions` — when a directive has
  `:max-iterations`, it emits **two** guarded `:judge/signal` transitions for
  that signal: the success path (`iter-count < max`) to the goto target, and the
  exhaustion path (`iter-count >= max`) hardcoded to `:target :failed`.
  `judged-routing-transition` then detects the `:failed` target and dispatches
  the `:iteration/exhausted` action.

`pass-status-routing` (`components/agent-session/.../workflow/routing.clj`) maps
a `PASS_STATUS:` line value to a `DONE`/`REPEAT` route via the `status->route`
table (`REVIEW_COMPLETE`/`IMPLEMENTATION_COMPLETE` → `DONE`;
`ACTIONABLE_FEEDBACK`/`MORE_WORK_REMAINS` → `REPEAT`). `task-lifecycle`'s
existing `check-implementation-review-status` invoke-step uses this to route
`review-task-implementation`'s yield to either `extract-task-knowledge` (DONE) or
`final-summary-without-extraction` (REPEAT).

`review-task-design` / `review-task-plan` final-summary steps currently emit a
prose summary and are explicitly told **not** to emit a control token / PASS_STATUS.

## Scope

In scope (single repo, `psi-main` worktree):

### A. Engine — author-routed exhaustion target

1. **`ir.clj`** — add an optional `:on-max-iterations` key to
   `routing-directive-schema`, valued like `:goto`
   (`[:or [:enum :next :previous :done] step-name-schema]`). Only meaningful when
   `:max-iterations` is present.
2. **`model.clj`** — mirror the same optional `:on-max-iterations` key on the
   authored `routing-directive-schema` so workflow EDN can declare it and pass
   model validation. (Listed implicitly: without this, authored `.edn` consumers
   below cannot validate.)
3. **`target_ir_compiler.clj`** `compile-routing-table` — thread
   `:on-max-iterations` from the authored directive into the IR directive when
   present.
4. **`statechart.clj`** `compile-routing-transitions` — when a directive carries
   `:max-iterations`, resolve the exhaustion-path target:
   - if `:on-max-iterations` is present → resolve it via the same goto→target
     resolution used for `:goto` (`:next`/`:previous`/`:done`/step-name) and
     route the exhaustion transition there;
   - else → preserve current behaviour (route to `:failed`).
   Resolve interaction with `judged-routing-transition`'s `:failed`-detection:
   - exhaustion → `:failed` keeps dispatching `:iteration/exhausted`;
   - exhaustion → an author target should dispatch the normal
     `:judge/record` action (treated like an ordinary routed transition), not
     `:iteration/exhausted` → `:failed`. Confirm/decide the exact dispatch for
     the author-routed exhaustion path during planning.
5. **Tests** for ir/model schema acceptance, `target_ir_compiler` threading, and
   `statechart` routing (exhaustion → author target vs default `:failed`),
   extending existing `ir_test`, `model_test`, `target_ir_compiler_test`,
   `statechart_test`.

### B. Workflows — review-did-not-converge handback

6. **`review-task-design.edn`** — `design-follow-up`'s `:on` gains
   `:on-max-iterations` pointing at a new clean summary step (e.g.
   `final-summary-not-converged`) that produces a "design review did not
   converge" user-facing summary. Both the converged `final-summary` and the
   not-converged summary must emit a recognizable `PASS_STATUS:` line so the
   lifecycle gate can route on the design-review outcome.
7. **`review-task-plan.edn`** — identical treatment (`plan-follow-up`
   `:on-max-iterations` → not-converged summary; PASS_STATUS on both summaries).
8. **`task-lifecycle.edn`** — add a `check-design-review-status` invoke-step
   gate after `review-task-design`, mirroring `check-implementation-review-status`:
   - DONE (design converged) → proceed to `create-task-plan`;
   - REPEAT (did not converge) → route to a `final-summary` style stop step that
     hands back to the human **without proceeding to plan and without
     hard-failing**.
   Add the analogous `check-plan-review-status` gate after `review-task-plan`
   (DONE → `implement-task`; REPEAT → handback stop).
9. **Tests/coherence** — workflow-loader / definition tests locking the new
   routing in the three EDN definitions; docs (`doc/workflows.md`) and CHANGELOG
   updated for the new `:on-max-iterations` directive key and the lifecycle gate
   behaviour.

Out of scope:

- No change to actor-retry / `:max-attempts` semantics.
- No change to the `:iteration/exhausted` accounting itself (only where the
  exhaustion transition is routed).
- No new generic routing operations unless planning shows one is required.

## Key design decisions (to settle during refinement)

1. **PASS_STATUS reuse vs new statuses.** Preferred: reuse existing
   `REVIEW_COMPLETE` (converged → DONE → proceed) and `ACTIONABLE_FEEDBACK`
   (not converged → REPEAT → handback) so `routing.clj` `status->route` is
   untouched and the gate matches the implementation-review gate exactly.
   Alternative: introduce design/plan-specific statuses
   (`DESIGN_REVIEW_INCOMPLETE`, etc.) — clearer intent but adds workflow-specific
   labels to `routing.clj`. **Recommend reuse; confirm with user.**
2. **Workflow-runtime boundary.** `:on-max-iterations` is a generic
   parameterized primitive (an author-supplied goto-target for the exhaustion
   edge) — it belongs in runtime code. The concrete targets / summary step names
   / PASS_STATUS strings are authored policy and live in the `.edn` definitions
   and prompts, not runtime code. The change must not push workflow-specific
   labels into `compile-routing-transitions`.
3. **Exhaustion dispatch action** for the author-routed path (see A.4): normal
   `:judge/record` vs a distinct marker. Default recommendation: treat as an
   ordinary routed transition (`:judge/record`).
4. **Handback "stop" shape in `task-lifecycle`.** Whether the not-converged
   branch reuses a single shared stop-summary step or distinct per-stage summary
   steps. Mirror the implementation-review precedent
   (`final-summary-without-extraction`) for consistency.

## Acceptance criteria

- AC-1: An authored routing directive may declare
  `{:goto X :max-iterations N :on-max-iterations Y}`; it validates in both
  `model.clj` and `ir.clj` schemas, and `:on-max-iterations` without
  `:max-iterations` is rejected/ignored per the settled rule.
- AC-2: `target_ir_compiler` threads `:on-max-iterations` from authored model to
  IR.
- AC-3: `compile-routing-transitions` routes the exhaustion transition to the
  resolved `:on-max-iterations` target when present, and to `:failed`
  (current behaviour, dispatching `:iteration/exhausted`) when absent.
- AC-4: `review-task-design` routes a non-converging design loop to a clean
  "design review did not converge" summary that emits a routable `PASS_STATUS`;
  no hard failure.
- AC-5: `review-task-plan` has the equivalent non-convergence handback.
- AC-6: `task-lifecycle` stops at the design stage (and at the plan stage) and
  hands back to the human when the corresponding review did not converge, and
  proceeds normally when it did — verified by definition/loader tests.
- AC-7: Focused workflow-runtime + workflow-loader Scry suites green; clj-kondo
  clean; `doc/workflows.md` + CHANGELOG updated.
