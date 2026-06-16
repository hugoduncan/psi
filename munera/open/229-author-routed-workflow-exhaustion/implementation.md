# 229 — Implementation notes

Append-only local memory: decisions, discoveries, review notes.

## Plan-review ambiguity pass (2026-06-16)

ACTIONABLE. 2 ambiguities → design-steps.md. Highest: DI-1 fixes internal
`:next` fall-through but leaves terminal-`:yield :text` resolution for the two
summary steps unspecified — the standalone `/delegate` result-text path
(`canonical_workflows`/`terminal-yielded-text`) keys strictly off
`(last :step-order)`, diverging from the delegate-gate path
(`terminal-result-envelope` via `:terminal-outcome`); converged standalone run
could surface the never-run not-converged summary. Second: `N iterations`
template source undefined. See design-steps.md.

## Plan-review inconsistency pass (2026-06-16)

ACTIONABLE. 2 inconsistencies → design-steps.md. (1) `review-task-design-test`
already RED in psi-main (asserts :max-iterations 6 vs edn 3 after de19cc5bf);
plan Slice 2 presumes a green baseline. Confirmed via focused run
(1 fail, -6 +3). (2) `task-lifecycle-test` is positionally hard-coded
(count/name/type vectors, nth indices, repeat-9 yields); plan's "extend OR add
new 229 test" alternative leaves it failing — existing test must be updated in
Slices 2 & 3. See design-steps.md.

## Plan-review follow-up execution (2026-06-16)

Executed all 4 plan-review batch follow-ups (ambiguity ×2 + inconsistency ×2),
all resolved in plan.md (design.md untouched). Verified each claim against
psi-main code before resolving:

- **DI-2 (terminal-yield, ambiguity-1).** Confirmed two divergent consumers:
  delegate-gate path `terminal_contract/terminal-result-envelope` reverse-scans
  step-order for the actually-run terminal step (order-independent); standalone
  `execute-workflow-run` (`canonical_workflows.clj:149`) + `terminal-yielded-text`
  key off `(last :step-order)`. Resolution: order converged `final-summary` last,
  `final-summary-not-converged` before it → converged text surfaces in *both*
  paths. Not-converged standalone empty-text edge accepted as R5 (lifecycle uses
  the order-independent path). Added converged-standalone result-text runtime
  test note (feasible via `workflow_review_step_routing_test` stub harness).
- **DI-3 (N count, ambiguity-2).** No runtime source for N plumbed to the
  summary step; hardcoding the cap drifts. Decision: drop the numeric count from
  both not-converged templates.
- **Inconsistency-1 (stale design-test baseline).** Verified: edn
  `:max-iterations 3` vs test asserts 6 (`workflow_definitions_test.clj:121`),
  de19cc5bf is ancestor of psi-main → test already RED. Plan Slice 2 now requires
  fixing 6→3 in the same edit. Checked siblings: `review-task-plan-test` (5=5) and
  `review-step-test` (10=10) match their edns — no drift there.
- **Inconsistency-2 (positional task-lifecycle-test).** Verified hard-coded
  count/name/type/`nth`/`repeat-9` assertions (`:602`). Plan now mandates
  updating the existing test in Slice 2 (9→11) and Slice 3 (11→13); separate 229
  test is additive-only (R3 sharpened).

Batch baseline: b9114c8f6 (parent of oldest commit e6ea17538 in the
ambiguity+inconsistency plan-review segment); HEAD 82a62be6f. design-steps.md was
created within the batch, so all 4 items attributable to the just-finished batch.

## Plan-review ambiguity pass — loop 2 (2026-06-16)

ACTIONABLE. 1 new ambiguity → design-steps.md. Summary-template PASS_STATUS
emission underspecified: `parse-pass-status-routing` errors on >1 `PASS_STATUS:`
line and requires exact `PASS_STATUS: <TOKEN>` format, yet both summary steps'
contributions include the review per-prompt replies which each end with a
PASS_STATUS line; plan removes the anti-echo guard ("Do not output REPEAT/DONE")
and the converged templates carry *two* anti-control-token sentences, so "replace
the instruction" is ambiguous and risks echo → ambiguous-pass-status → lifecycle
hard-fail. Prior batch items (DI-2 terminal-yield, DI-3 N-count, stale baseline,
positional task-lifecycle-test) remain resolved; no duplication.

## Plan-review inconsistency pass — loop 2 (2026-06-16)

ACTIONABLE. 1 new inconsistency → design-steps.md. steps.md execution checklist
is out of sync with the hardened plan.md: prior passes hardened plan.md (RED
6→3 baseline fix, converged standalone result-text test (DI-2), in-place
positional task-lifecycle-test update with additive-only 229 test, DI-3 no-N-
count wording) but steps.md Slice 2/3 was never re-synced, so ticking steps.md
alone would omit mandatory work. Verified D2 (judged-routing-transition keys
dispatch off :failed only) and the model `:string` / ir `step-name-schema`
mirror against code — both accurate, no inconsistency there. Loop-1 items
(stale baseline, positional test) remain resolved in plan.md; not duplicated.
