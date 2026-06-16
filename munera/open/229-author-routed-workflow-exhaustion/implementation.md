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

## Plan-review loop-2 follow-up execution (2026-06-16)

Batch baseline: `8a415a5ac` (parent of oldest loop-2 commit `0c3f5e343`; the
previous plan-follow-up completion). HEAD `ff2653f72`. `git diff 8a415a5ac..HEAD`
on design-steps.md added exactly two unchecked checklist items — the loop-2
ambiguity item (summary PASS_STATUS emission) and the loop-2 inconsistency item
(steps.md out of sync). Both were the candidate work set.

- **Ambiguity-loop2 (summary PASS_STATUS emission) — RESOLVED in plan.md.**
  Added DI-4 "summary PASS_STATUS line contract": exactly one column-0
  `PASS_STATUS: <TOKEN>` line, sole occurrence, last line; retain an explicit
  anti-echo instruction (summary must not reproduce the contributed review
  replies' PASS_STATUS lines, else >1 line → `:ambiguous-pass-status` hard-fail);
  reconcile the two existing anti-control-token sentences by keeping the "concise
  summary … not an internal control token" guard and rewriting the
  "do not output REPEAT/DONE" guard into the precise anti-echo + single-line rule.
  Applies to converged `final-summary` + new `final-summary-not-converged` in both
  `review-task-design.edn` and `review-task-plan.edn`. Updated Slice 2/3 D1
  references to point at DI-4 and sharpened R2 with the echo-risk mitigation.
  Verified the two anti-control-token sentences against the live converged
  template in `review-task-design.edn` before specifying. Marked [x].

- **Inconsistency-loop2 (steps.md out of sync) — BLOCKED, left unchecked.** The
  item's prescribed resolution is "update steps.md Slice 2/3 to enumerate the four
  mandated sub-tasks". That conflicts with two governing constraints: (1) the
  design-steps.md header states "steps.md is read-only review context" (this
  merged review-task-plan workflow routes follow-ups through design-steps.md and
  treats steps.md as read-only), and (2) this follow-up run is explicitly
  instructed not to touch steps.md beyond read-only context. The literal
  resolution is therefore out of bounds; resolving it would require either
  editing steps.md (forbidden) or unilaterally relocating the plan→steps sync
  contract (a workflow-convention change, out of scope). Per the batch
  evidence/disposition rule, left unchecked rather than guessing. Needs a human
  decision: either authorize a steps.md edit for plan-review sync, or restate the
  item to resolve the plan↔steps mandate sync within plan.md only.

## Plan-review ambiguity pass — loop 3 (2026-06-16)

ACTIONABLE. 1 new ambiguity → design-steps.md. DI-1/DI-2/R1 fall-through
analysis is scoped only to review-task-design/-plan summary steps; the plan
specifies the new task-lifecycle.edn gates' `:on` routing + test count bumps
(9→11→13) but never pins the `:steps` insertion position of the four new
lifecycle steps. Verified against code: lifecycle `:delegate` steps are
non-judged leaf steps (`statechart.clj` `compile-leaf-step`:
`:actor/done → next-step-target`), so e.g. placing
`final-summary-design-not-converged` right after `create-task-plan` makes the
converged path fall through into the handback — the DI-1 wrong-path bug
reintroduced in the lifecycle. Distinct from the existing positional
`task-lifecycle-test` item (test mechanics, presupposes known positions).
Prior batch items (DI-2/DI-3/DI-4, stale baseline, positional test) remain
resolved; steps.md-sync inconsistency item still open/BLOCKED — not duplicated.

## Plan-review inconsistency pass — loop 3 (2026-06-16)

ACTIONABLE. 1 new inconsistency → design-steps.md. `design.md` scope item 6
(and item 7) still says the summary change "replaces the existing 'do not output
REPEAT/DONE/control tokens' instruction … with a single required PASS_STATUS
line", but `plan.md` DI-4 explicitly supersedes that and mandates the opposite
("do not replace; keep sentence (a), rewrite sentence (b)"). DI-4 reconciled
`plan.md` but `design.md` (authority for what/why) was never updated → the two
files prescribe contradictory template edits. Verified item-6/7 text and DI-4
point 3 directly. Distinct from the resolved loop-2 ambiguity item (hardened
plan only). Prior inconsistency items (stale baseline, positional test resolved;
steps.md-sync open/BLOCKED) and the loop-3 ambiguity item (task-lifecycle
placement) not duplicated.

## Plan-review loop-3 follow-up execution (2026-06-16)

Batch baseline: `d1fce01cf` (parent of the oldest loop-3 commit `5d6341803`; the
previous plan-follow-up completion). HEAD `bf8468207`. `git diff d1fce01cf..HEAD`
on design-steps.md added exactly two unchecked checklist items — the loop-3
ambiguity item (task-lifecycle.edn placement) and the loop-3 inconsistency item
(design.md item 6 vs DI-4). Those two are the candidate work set. The earlier
steps.md-sync inconsistency item predates this batch (added loop-2, `ff2653f72`)
and stays excluded/BLOCKED.

- **Ambiguity-loop3 (task-lifecycle.edn placement) — RESOLVED in plan.md.**
  Added DI-5 "lifecycle step placement & fall-through": each gate inserted
  immediately after its delegate (`check-design-review-status` after
  `review-task-design`, index 1; `check-plan-review-status` after
  `review-task-plan`, index 4) so the delegate falls through into the gate and
  the gate's DONE goto continues the main flow; both
  `final-summary-*-not-converged` handbacks appended last after the already
  explicit-terminal summaries so no converged leaf falls through into them.
  Pinned the exact resulting ordered name + type vectors the updated
  `task-lifecycle-test` must assert after Slice 2 (9→11) and Slice 3 (11→13),
  including the index shifts and `repeat` count bumps. Cross-referenced DI-5 from
  Slice 2/3 task-lifecycle bullets and test bullets, and widened R1 to cover the
  lifecycle fall-through. Verified the current 9-step order/types against
  `.psi/workflows/task-lifecycle.edn` before pinning. Marked [x].

- **Inconsistency-loop3 (design.md item 6/7 vs DI-4) — BLOCKED, left unchecked.**
  The item's resolution is to edit `design.md` items 6/7 (drop the "replaces the
  existing instruction" phrasing / point at DI-4). Both alternatives touch
  `design.md`, which this plan-profile follow-up (`review-follow-up-plan`)
  explicitly treats as read-only ("read the task's steps.md and design.md as
  read-only context"; "Do not touch steps.md or design.md beyond read-only
  context"). Resolving it in plan.md alone does not satisfy the item, which
  targets the design authority. Per the batch evidence/disposition rule, left
  unchecked rather than editing an out-of-bounds file. Needs a human decision:
  authorize a design.md edit (or a design-review/design-follow-up pass) to
  reconcile items 6/7 with the superseding plan.md DI-4 contract.

## Plan-review ambiguity pass — loop 4 (2026-06-16)

ACTIONABLE. 1 new ambiguity → design-steps.md. DI-2's converged-standalone
result-text test note conflates the synthetic `workflow_review_step_routing_test`
proof harness with the real loaded `.edn`: (a) the harness drives synthetic
`conditional-review-design/plan-definition`s whose converged `final-summary` is a
bare `"final-summary"` template (no PASS_STATUS), so it cannot lock the DI-4 real
template wording the test claims to prove — only the real loaded
`review-task-design.edn`/`review-task-plan.edn` can; (b) `:psi.workflow/result`
comes from `canonical_workflows/execute-workflow-run`, but the harness path uses
`execute-run!` with a custom actor-turn fn, so "via the harness … run through
execute-workflow-run" names two non-composed mechanisms; (c) "return
REVIEW_COMPLETE for the review step" underspecifies the multi-prompt
`pass-feedback-routing` convergence (every per-prompt reply must carry
`PASS_STATUS: REVIEW_COMPLETE`). Verified against code (harness uses synthetic
proof defs at :393-447/:524+; `execute-workflow-run` at canonical_workflows.clj;
`design-review-full-pass-routing-test` shows the per-prompt all-REVIEW_COMPLETE
pattern). Prior loop-1..3 items (DI-2 ordering, DI-3, DI-4, DI-5, stale baseline,
positional task-lifecycle-test) remain resolved; steps.md-sync + design.md item
6/7 inconsistencies still open/BLOCKED — not duplicated.
