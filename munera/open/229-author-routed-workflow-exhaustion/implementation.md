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

## Plan-review inconsistency pass — loop 4 (2026-06-16)

ACTIONABLE. 1 new inconsistency → design-steps.md. R3/DI-5's enumerated
`task-lifecycle-test` update scope ("count, name/type vectors, positional nth,
yields-repeat / repeat-count bumps") is incomplete: it omits three live
assertions keyed off `(take 5 steps)` that also break when an :invoke gate is
inserted at index 1 (Slice 2) and index 4 (Slice 3) — `first-five-targets`
targets (:643), `(repeat 5 standard-prompt)` prompt-strings (:645-646), and
`(repeat 6 [...])` contexts (:667-668). Gate steps have no :target/:prompt-string/
:context, so these need restructuring away from the `take 5` "first-five-are-
delegates" assumption, not mechanical nth/repeat bumps. Verified line refs and
plan baselines still accurate (review-task-design-test:121 still 6 vs edn 3;
task-lifecycle-test:602 still 9 steps). Distinct from the resolved positional-
test item (which enumerated count/name/type/nth/yields only). Prior open items
(steps.md sync, design.md 6/7) unchanged; loop-4 ambiguity (DI-2 test
harness-vs-edn) not duplicated.

## Plan-review loop-4 follow-up execution (2026-06-16)

Batch baseline: `f76071201` (parent of the oldest loop-4 commit `3d2961483`; the
previous plan-follow-up completion). HEAD `18a1b6931`. `git diff f76071201..HEAD`
on design-steps.md added exactly two unchecked checklist items — the loop-4
ambiguity item (DI-2 test harness-vs-real-edn) and the loop-4 inconsistency item
(R3/DI-5 omits `(take 5 steps)` assertions). Those two are the candidate work
set. The earlier steps.md-sync (loop-2, `ff2653f72`) and design.md item-6/7
(loop-3, `bf8468207`) inconsistency items predate this batch and stay
excluded/BLOCKED. Both candidate items resolve in plan.md (editable) — neither
blocked.

- **Ambiguity-loop4 (DI-2 test harness-vs-real-edn) — RESOLVED in plan.md.**
  Verified against code first: the synthetic harness
  (`workflow_review_step_routing_test`) drives `conditional-review-design/plan-definition`
  whose converged `final-summary` is a bare `{:type :template :text
  "final-summary"}` (no PASS_STATUS, lines 425-427/441-443) via `execute-run!`
  with a custom `:workflow-execute-actor-turn-fn` (`execute-conditional-review-proof!`
  :474-490); `:psi.workflow/result` comes from `canonical_workflows/execute-workflow-run`
  (:126-167) whose `:execute-workflow-run-fn` is `workflow-execution/execute-run!`
  (context.clj:228), the real path that calls
  `psi.agent-session.turn/prompt-execution-result-in!`; the per-prompt
  all-REVIEW_COMPLETE keyed-by-prompt-text pattern is `design-review-full-pass-routing-test`
  (:573-581). Rewrote the DI-2 "Test (Slice 2/3)" note into a
  "converged-standalone result-text construction" spec disambiguating all three
  coupled points: (a) load the **real** `review-task-design.edn`/`review-task-plan.edn`
  (not the synthetic proof def — only the real loaded def can lock the DI-4
  wording); (b) drive through the `execute-workflow-run` mutation, stubbing at
  `prompt-execution-result-in!` (bypass the harness custom actor-turn fn);
  (c) stub **every** per-prompt turn (3 design / 2 plan) with a convergent
  `PASS_STATUS: REVIEW_COMPLETE` keyed by per-prompt prompt text plus the
  converged summary text, then assert `:psi.workflow/result` contains exactly one
  `PASS_STATUS: REVIEW_COMPLETE` line. Synced the Slice 2 + Slice 3 DI-2 test
  bullets to the same real-edn construction. Marked [x].

- **Inconsistency-loop4 (R3/DI-5 omits `(take 5 steps)` assertions) — RESOLVED in
  plan.md.** Verified the three live assertions in
  `workflow_definitions_test.clj`: `(mapv :target (take 5 steps))` (:643),
  `(repeat 5 standard-prompt)` prompt-strings (:646-647), `(repeat 6 …)` contexts
  (:668-669) — the inserted `:invoke` gates have no
  `:target`/`:prompt-string`/`:context`, so `take 5` no longer yields five
  delegate steps. Extended DI-5 with an "Additional `task-lifecycle-test`
  assertions that MUST be restructured" subsection enumerating the three and
  mandating restructuring away from the `take 5` positional assumption (select
  delegate steps by name/type filter) for both Slice 2 (gate at index 1) and
  Slice 3 (gate at index 4). Sharpened R3 to flag the count/name/type/nth/repeat
  list as non-exhaustive, and added the restructuring mandate to both the Slice 2
  and Slice 3 task-lifecycle-test bullets. Marked [x].

Excluded items confirmed still excluded: steps.md-sync (BLOCKED, read-only) and
design.md item-6/7 (BLOCKED, read-only) remain unchecked — both predate the
loop-4 batch and both require editing files this follow-up treats as read-only.

## Plan-review ambiguity pass — loop 5 (2026-06-16)

ACTIONABLE. 2 new ambiguities → design-steps.md. (1) **Slice 1 engine gap:**
runtime exhaustion is decided judge-side in `workflow-judge/evaluate-routing`
(`{:action :fail :reason :iteration-exhausted}` → queued `:judge/failed` →
`:judge/record` else-branch → status :failed), **not** by the statechart
`:judge/signal` exhaustion guard the plan modifies. Verified against integration
test `workflow_review_step_routing_test/review-pass-loop-iteration-limit-failure-test`
(`:689`/`:703`): real exhausted run terminates `:reason :iteration-exhausted`,
`:step-id "design-follow-up"` (the `:max-iterations`-bearing step) — the statechart
`:iteration/exhausted`/`:iteration-limit-reached` action fires only in the
isolated `statechart_test` that feeds `:judge/signal` directly. So the planned
`compile-routing-transitions`-only change is dead code at runtime; `:on-max-iterations`
must also thread through `evaluate-routing` (or the plan must prove statechart-only
suffices via an integration exhaustion-routing test). Threatens AC-3/4/5/6.
(2) **DI-2 test cannot lock DI-4 template wording:** the converged-standalone
runtime test stubs `prompt-execution-result-in!` (the model reply), so the
asserted `PASS_STATUS: REVIEW_COMPLETE` comes from the stub, not the template;
loading the real `.edn` does not lock the wording (a synthetic def yields the
same assertion). The runtime test locks ordering/plumbing only; the
definition-level `review-task-design-test`/`-plan-test` must assert the exact
DI-4 template text. Prior loop-1..4 items unchanged; the loop-4 DI-2 item
(real-vs-synthetic def / entry point / multi-prompt convergence) is distinct and
not duplicated; steps.md-sync + design.md item-6/7 inconsistencies remain
open/BLOCKED.

## Plan-review inconsistency pass — loop 5 (2026-06-16)

ACTIONABLE. 1 new inconsistency → design-steps.md. `design.md` D5 ("standalone
output … accepted as useful", framed across both summaries; echoed by scope item
6's D5 note) contradicts `plan.md` R5/DI-2, which establish the **not-converged**
standalone `/delegate` run surfaces **empty** result text (the converged summary
is ordered last and `execute-workflow-run` reads `(last :step-order)`), accepted
as a degradation rather than a useful feature. The loop-1 DI-2 item reconciled
only the *converged* standalone case (ordering); design.md D5 was never updated
for the not-converged degradation. Distinct from the open design.md item-6/7
inconsistency ("replaces" vs DI-4 keep+rewrite). Verified D5/R5/DI-2 text and the
`(last :step-order)` standalone path against loaded context + canonical_workflows.
Loop-5 ambiguity items (judge-side evaluate-routing exhaustion site; DI-2
stubbed-LLM template-wording) not re-raised here — the engine-site item already
notes reconciling design "Context"/D2. Prior inconsistency items (stale baseline,
positional task-lifecycle-test, (take 5 steps) restructuring resolved; steps.md
sync + design.md item-6/7 open/BLOCKED) unchanged, not duplicated.

## Plan-review loop-5 follow-up execution (2026-06-16)

Batch baseline: `feaa179c7` (parent of the oldest loop-5 commit `276c1b0bc`; the
previous plan-follow-up completion = loop-4 execution `feaa179c7`). HEAD
`cb1775179`. `git diff feaa179c7..HEAD` on design-steps.md added exactly **three**
unchecked checklist items — the two loop-5 ambiguity items (judge-side
`evaluate-routing` exhaustion site; DI-2 stubbed-LLM template-wording) and the
one loop-5 inconsistency item (design.md D5 vs plan.md R5/DI-2). Those three are
the candidate work set. The earlier steps.md-sync (loop-2, `ff2653f72`) and
design.md item-6/7 (loop-3, `bf8468207`) inconsistency items predate this batch
and stay excluded/BLOCKED.

- **Ambiguity-loop5-A (Slice 1 judge-side `evaluate-routing` gap) — RESOLVED in
  plan.md.** Verified the review's claim against psi-main code before resolving:
  `workflow_judge.clj` `evaluate-routing` (`:129`) returns
  `{:action :fail :reason :iteration-exhausted}` via `check-iteration-limit`
  (`:79`) when the target step's `:iteration-count` ≥ directive `:max-iterations`;
  `statechart_runtime.clj` `:judge/enter` (`:383` `routing-table (or (:on
  step-def) {})`; `:416` `:fail → :judge/failed`) → `:judge/record` else-branch
  (`:status :failed`, `:reason :iteration-exhausted`). The statechart
  `:iteration/exhausted` action (`:reason :iteration-limit-reached`, `:483`)
  fires only on direct `:judge/signal`. Integration test
  `review-pass-loop-iteration-limit-failure-test` (`:675`+) confirms real
  exhausted review runs terminate `:reason :iteration-exhausted`,
  `:step-id "design-follow-up"`/`"clarity-status"` — judge-side path, not the
  statechart action. Critically, the judge's `routing-table` IS the full IR
  directive (`(:on step-def)`), so `:on-max-iterations` (threaded by the Slice-1
  `target_ir_compiler` change) is already available at `evaluate-routing`.
  Chose option (a): added **DI-6** ("judge-side exhaustion is the runtime-governing
  site") and extended Slice 1 to thread `:on-max-iterations` through
  `evaluate-routing` — when `:exhausted` AND the directive carries
  `:on-max-iterations`, resolve via existing `resolve-goto-target` and return
  `{:action :goto :target …}`/`{:action :complete}` instead of `:fail`, so the
  non-`:fail` action enqueues `:judge/signal` → `:judge/record` `:goto` branch →
  author target, `:status :running` (run not failed). Kept the
  `compile-routing-transitions` edit for two-site coherence/future direct-signal
  path (now flagged dead-code-at-runtime for review workflows). Added Slice-1
  files entry (`workflow_judge.clj`), a `workflow_judge_test` unit test, and a
  **mandatory integration exhaustion-routing test** (drives a real exhausting
  judged loop with `:on-max-iterations` through `execute-run!`/`execute-workflow-run`,
  asserts author-target termination + run-not-failed); widened Slice-1 Exit to
  require the integration assertion + workflow-judge suite; added a Slice-1
  behaviour-inert note (no shipped `.edn` opts in until Slices 2/3). Updated the
  Strategy "four routing layers" → "+ judge-side `evaluate-routing`". Marked [x].
  **Residual (read-only-blocked):** the item also asks to "reconcile design
  'Context'/D2 in design.md" — `design.md` Context frames exhaustion as
  statechart-only and omits the `evaluate-routing` governing site, and D2 says
  "the only statechart edit is …". D2's *decision* ("no change to
  `judged-routing-transition`") remains valid; only the Context *description* and
  the "only … edit" framing under-describe the change set. That `design.md`
  clarification cannot be made under this plan-profile follow-up (design.md is
  read-only). plan.md now authoritatively carries the two-site mechanism (DI-6),
  and DI-6 records the design.md clarification as a deferred design-pass item, so
  no implementer is misled. Item marked [x] on the strength of the complete
  plan.md resolution (primary directive "Resolve in plan.md"); the design.md
  clarification is folded into the recommended design pass below.

- **Ambiguity-loop5-B (DI-2 runtime test cannot lock DI-4 wording) — RESOLVED in
  plan.md.** The DI-2 converged-standalone runtime test stubs
  `prompt-execution-result-in!` (the model *reply*), so the asserted
  `PASS_STATUS: REVIEW_COMPLETE` is supplied by the stub, not the template (the
  template is the *prompt*, never the *output*). Rewrote the DI-2 test note to
  state explicitly that the runtime test locks the **ordering/plumbing** invariant
  only (converged `final-summary`, ordered last per DI-2, is the step whose
  yielded text surfaces via the standalone `(last :step-order)` path), and that
  loading the real `.edn` is to exercise the real ordering — NOT to lock the
  wording. Updated point (a) purpose + the Assertion paragraph accordingly. Added
  **DI-4 point 4**: the **definition-level** `review-task-design-test` /
  `review-task-plan-test` is the authority that locks the DI-4 template **text** —
  assert the converged `final-summary` template body contains the sole, column-0,
  single-space, last-line `PASS_STATUS: REVIEW_COMPLETE` (and not-converged
  `PASS_STATUS: ACTIONABLE_FEEDBACK`) in the exact `parse-pass-status-routing`
  form. Synced both Slice 2 and Slice 3 definition-test bullets to require this
  DI-4 point-4 template-text assertion, and updated the DI-4 "Test note". Pure
  plan.md edits; not blocked. Marked [x].

- **Inconsistency-loop5 (design.md D5 vs plan.md R5/DI-2) — BLOCKED, left
  unchecked.** The item's resolution is to reconcile `design.md` D5 (scope
  "accepted as useful" to the converged standalone summary; record that the
  not-converged standalone path surfaces empty/degraded result text per R5) — or
  replace D5's standalone framing with a pointer to plan.md R5/DI-2. Both
  alternatives edit `design.md`, which this `review-follow-up-plan` profile
  explicitly treats as read-only ("read design.md as read-only context"; "Do not
  touch … design.md beyond read-only context"). plan.md already carries the
  authoritative standalone-output contract (R5 + DI-2): converged standalone
  surfaces the converged summary's `PASS_STATUS: REVIEW_COMPLETE`; not-converged
  standalone surfaces empty result text (degraded, accepted; the handback is a
  lifecycle-only concern via the order-independent delegate-gate path). Resolving
  in plan.md alone does not satisfy the item, which targets the design authority.
  Per the batch evidence/disposition rule, left unchecked rather than editing an
  out-of-bounds file.

**Recommended design pass (consolidated, read-only-blocked here).** Three
`design.md` reconciliations have now accumulated and should be handled together
in a single design-review/design-follow-up pass (all out of bounds for this
plan-profile follow-up):
1. Items 6/7 (loop-3): drop "replaces the existing instruction"; align with
   DI-4 keep-(a)/rewrite-(b).
2. D5 (loop-5): scope "accepted as useful" to the converged standalone case;
   record the not-converged standalone empty/degraded result text per R5.
3. Context/D2 (loop-5 item-1 residual): note the judge-side `evaluate-routing`
   governing site (DI-6) so the Context current-behaviour description and the
   "only … edit" framing match the actual change set; D2's decision is unchanged.

---

## Design pass (post-move, 229 on `exhaustion-routing`)

Executed the consolidated design pass the plan-review profile had deferred
(design.md was read-only there). Verified DI-6 against the code first:
`workflow_judge.clj` `evaluate-routing` returns
`{:action :fail :reason :iteration-exhausted}` on `:exhausted` — confirmed the
governing runtime exhaustion site, with `resolve-goto-target` already present for
reuse. `design.md` reconciled:

1. **Context + Intent + scope A.4 + AC-3 (DI-6).** Added the `evaluate-routing`
   governing-site description; reframed exhaustion as two sites (governing judge
   side + parallel statechart side); scope item 4 now mandates the
   `evaluate-routing` `:on-max-iterations` edit (governing) alongside the
   `compile-routing-transitions` edit (coherence); item 5 + AC-3 require
   integration-level coverage of the governing path.
2. **Scope item 6 (DI-4).** Dropped "replaces the existing instruction"; now
   keep prose-guard (a) / rewrite anti-echo guard (b), sole column-0
   `PASS_STATUS:` line.
3. **D2.** Softened "the only statechart edit" → governing edit is
   `evaluate-routing`; decision (`judged-routing-transition` untouched) unchanged.
4. **D5.** Split standalone output: converged surfaces `REVIEW_COMPLETE`
   (useful); not-converged empty/degraded via `(last :step-order)` (accepted,
   lifecycle-only handback) per R5.

`steps.md` Slices 1–3 re-synced to the hardened plan (DI-6 site + integration
test; four mandated sub-tasks a–d; DI-2 ordering; DI-3 wording; DI-4 contract).
All three previously-blocked `design-steps.md` items now `[x]`. design.md ↔
plan.md ↔ steps.md are coherent; task is implementation-ready.

---

## Slice 1 implementation (2026-06-16)

Landed `:on-max-iterations` engine primitive end-to-end, behaviour-inert (no
shipped `.edn` authors it yet).

- **model.clj / ir.clj**: added optional `:on-max-iterations` to
  `routing-directive-schema` (valued like `:goto`; model `:string`, ir
  `step-name-schema`), wrapped each directive map in
  `[:and <map> [:fn on-max-iterations-requires-max-iterations?]]` so
  `:on-max-iterations` without `:max-iterations` is rejected (D3). The schema is
  now `:and`-wrapped — any consumer doing structural `[:map …]` introspection of
  `routing-directive-schema` would need `(second schema)`; none found.
- **target_ir_compiler.clj**: additive `cond->` clause threading
  `:on-max-iterations` model→IR.
- **statechart.clj**: extracted the inline goto→acting-state resolution to
  private `resolve-goto-acting-target`; `compile-routing-transitions` now uses it
  for both the success `:goto` target and the exhaustion target (author target
  when `:on-max-iterations` present, else `:failed`). DI-6: this statechart
  exhaustion guard is dead code at runtime for the review workflows; kept for
  two-site coherence.
- **workflow_judge.clj** (DI-6 governing edit): `evaluate-routing` on `:exhausted`
  now routes via `resolve-goto-target` when the directive carries
  `:on-max-iterations` (returns `:goto`/`:complete`), else unchanged `:fail`.

Tests: model/ir accept+reject (D3); target_ir threading (AC-2); statechart
author-target routing (`:step/handback.acting`, `:judge/record`, not `:failed`)
+ preserved `:failed`/`:iteration/exhausted` regression; workflow_judge unit
(`:goto`/`:complete`/within-limit/absent); DI-6 integration in
`workflow_review_step_routing_test` (real exhausting design loop with
`:on-max-iterations` → handback, run not failed). All green; clj-kondo clean.

Discovery: statechart acting-state ids are namespaced `:step/<name>.acting`
(slash), not `:step.<name>.acting` — corrected the statechart test assertion.

---

## Slice 2 implementation (2026-06-16)

review-task-design handback + lifecycle design gate.

- **review-task-design.edn**: `design-follow-up` `:on` now
  `{"DONE" {:goto "design-review" :max-iterations 3 :on-max-iterations
  "final-summary-not-converged"}}`. New `final-summary-not-converged` session
  step inserted BEFORE the converged `final-summary` (DI-2 ordering); both
  summaries made explicit-terminal (constant-routing DONE judge + `:on {"DONE"
  {:goto :done}}`). DI-4 template contract applied to both: kept guard (a),
  rewrote guard (b) WITHOUT the literal `PASS_STATUS:` token (anti-echo phrased
  as "do not reproduce the status lines from the review replies") so each
  template body contains exactly ONE `PASS_STATUS:` occurrence, as the final
  column-0 line — `REVIEW_COMPLETE` (converged) / `ACTIONABLE_FEEDBACK`
  (not-converged, no iteration count per DI-3).
- **task-lifecycle.edn**: `check-design-review-status` invoke-gate inserted
  immediately after `review-task-design` (DONE→`create-task-plan`,
  REPEAT→`final-summary-design-not-converged`);
  `final-summary-design-not-converged` session handback appended last
  (`:goto :done`, no extraction).
- **workflow_definitions_test.clj**: `review-task-design-test` updated (4-step
  order, `:on-max-iterations`, both-terminal, DI-4 template-text via new
  `assert-sole-final-pass-status-line` helper). `task-lifecycle-test`
  restructured (11 steps; the three `(take 5 steps)` positional assertions
  replaced with name/type-filtered `delegate-steps` selection per DI-5; design
  gate + handback assertions; `repeat 11`).
- **workflow_delegate_review_step_live_test.clj**: DI-2 converged-standalone
  runtime test — loads the real built-in `review-task-design`, stubs
  `prompt-execution-result-in!` (review prompts + final-summary all REVIEW_COMPLETE),
  drives the `execute-workflow-run` mutation, asserts `final-summary` is last in
  step-order and `:psi.workflow/result` has exactly one `PASS_STATUS:
  REVIEW_COMPLETE`.

DEVIATION (out-of-scope pre-existing RED): `review-task-plan-test` and
`review-task-prompt-artifact-targets-test` were already failing at the Slice-1
baseline — they assert review-task-plan prompts contain "steps.md" and NOT
"design-steps.md", but commit `9a0e4a01f` (#177 "route plan-review follow-ups
through shared design-steps.md") intentionally changed the authored content to
"design-steps.md" without updating these tests. This is #177 test-debt, not 229
breakage, but it sits in the review-task-plan test surface Slice 3 rewrites, so
it is reconciled there (update stale assertions to the authored #177 content; no
workflow-content change). Recorded so the AC-7 green-suite gate is met by
Slice 3, not silently.

---

## Slice 3 implementation (2026-06-16)

Symmetric plan-review handback + lifecycle plan gate.

- **review-task-plan.edn**: `plan-follow-up` `:on` gains
  `:on-max-iterations "final-summary-not-converged"` (max-iterations 5 kept); new
  `final-summary-not-converged` before converged `final-summary` (DI-2); both
  explicit-terminal; DI-4 single-final-PASS_STATUS contract
  (REVIEW_COMPLETE / ACTIONABLE_FEEDBACK, DI-3 no count, anti-echo without literal
  token).
- **task-lifecycle.edn**: `check-plan-review-status` invoke-gate immediately after
  `review-task-plan` (DONE→`implement-task`,
  REPEAT→`final-summary-plan-not-converged`); `final-summary-plan-not-converged`
  appended last (`:goto :done`, no extraction).
- **workflow_definitions_test.clj**: `review-task-plan-test` (4-step order,
  `:on-max-iterations`, both terminal, DI-4 template text, not-converged sources);
  `task-lifecycle-test` 11→13 (plan gate + plan handback assertions, `repeat 13`).
- **workflow_delegate_review_step_live_test.clj**: DI-2 plan-review
  converged-standalone runtime test mirroring the design one.

DEVIATION RESOLVED (#177 test-debt): updated the pre-existing RED
`review-task-plan-test` plan-follow-up text assertions (steps.md →
`design-steps.md`; `git diff …/steps.md` → `…/design-steps.md`; dropped the
`(not design-steps.md)` assertion) and rewrote the
`review-task-prompt-artifact-targets-test` plan block to assert plan-review
prompts target the shared `design-steps.md` (per #177
"route plan-review follow-ups through shared design-steps.md"), keeping a
`review-follow-up-steps.md`-owns-steps.md check. Test-only, matched authored
workflow content; no workflow-content change.

Pre-existing unrelated failure (NOT 229): the live
`delegate-review-task-implementation-completes-with-nullable-local-model-test`
in the same ns fails identically with my changes stashed (terminates :failed —
environmental/local-model dependency). Out of scope; left as-is. My two new DI-2
tests in that ns pass (they stub the turn and drive the synchronous mutation).

---

## Verification pass (2026-06-16)

Confirmed all four slices committed (33ce93beb..f4ded07bb). Verified focused
suites green at HEAD:
- `workflow-definitions-test` (loader): 270 assertions, 11 tests, 0 fail.
- `ir`/`model`/`target-ir-compiler`/`statechart`/`workflow-judge`: 280
  assertions, 43 tests, 0 fail.
- `workflow-review-step-routing` + `workflow-delegate-review-step-live`: 137
  assertions, 16 tests pass; the lone failure
  `delegate-review-task-implementation-completes-with-nullable-local-model-test`
  is the documented pre-existing environmental failure — it exercises
  `review-task-implementation` (untouched by 229: `git log --name-only` over the
  229 range shows no `review-task-implementation` edn/clj edit), terminates
  `:failed` under the nullable local test model, and is independent of the 229
  change set. Out of scope; left as-is.

Committed an outstanding test-shaper dedup: extracted shared
`assert-review-summary-handback` helper in `workflow_definitions_test.clj`
(both `review-task-design-test` and `review-task-plan-test` shared the identical
terminal/DI-4-PASS_STATUS/shared-source assertions) — lint clean, suite green.

---

## Implementation review pass (2026-06-16)

ACTIONABLE (1 → steps.md). A transient scry test-output artifact
(`.scry-results/…nullable-local-model-test.edn`) was committed in `19b41b2ea`;
`.scry-results/` is not gitignored, so the run output leaked into the tree.

Independently re-verified the claimed pre-existing/unrelated failure: ran
`delegate-review-task-implementation-completes-with-nullable-local-model-test`
with the five 229 production sources + three workflow `.edn`s reverted to the
pre-Slice-1 baseline (`9a07db27e`) — it fails identically (4 passed / 3 failed,
terminates `:failed` under the nullable local model). Confirms it is independent
of the 229 change set (deftest predates 229, added in `be16dd244`); the 229
runtime edits are behaviour-inert for any directive without `:on-max-iterations`.

Engine/EDN/docs reviewed: `evaluate-routing` governing-site edit, the shared
`resolve-goto-acting-target` extraction, schema D3 cross-field guard, the DI-2
not-converged-before-converged ordering, and the lifecycle gates are coherent
with design. Confirmed the delegate-gate handback resolves order-independently:
the delegate success payload is `terminal-result-envelope` (prefers
`:terminal-outcome :result-envelope`, else reverse-scans `:accepted-result`), so
the not-converged summary's `PASS_STATUS: ACTIONABLE_FEEDBACK` surfaces to
`check-design/plan-review-status` even though the converged `final-summary` is
ordered last. Focused workflow-runtime/judge/loader suites green; clj-kondo clean.

## Implementation-review follow-up (2026-06-16)

Removed the stray transient Scry artifact committed in `19b41b2ea`
(`.scry-results/…nullable-local-model-test.edn`) via `git rm`, and added
`.scry-results/` to `.gitignore` so future focused-Scry run outputs (written
under `.scry-results/` per `bb.edn`) are never tracked. Verified with
`git check-ignore`. No production/test/doc behaviour change.

## Implementation review pass 2 (2026-06-16)

REVIEW_COMPLETE — no new actionable issues. Independently re-ran focused suites
at HEAD `611712b7d`: workflow-definitions-test 270/0; ir+model+target-ir-compiler
+statechart+workflow-judge 280/0; workflow-review-step-routing-test 114/0 (incl.
DI-6 integration exhaustion-routing); workflow-delegate-review-step-live-test —
only the documented pre-existing `…nullable-local-model-test` fails (3 assertions,
229-independent), both DI-2 standalone tests pass. clj-kondo clean; `.scry-results/`
gitignored, none tracked; tree clean.

Implementation matches design (D1–D5, DI-1–DI-6) and the workflow-runtime
boundary (generic `:on-max-iterations` primitive in runtime; targets/wording/
PASS_STATUS strings as authored `.edn`/prompt policy). Lifecycle gates mirror the
existing `check-implementation-review-status` shape (no new pattern); the goto
resolution is shared via `resolve-goto-acting-target` (no divergence).

Non-actionable observations (no follow-up filed): (1) the cross-field predicate
`on-max-iterations-requires-max-iterations?` is duplicated verbatim in model.clj
and ir.clj — consistent with the pre-existing deliberate model/IR
`routing-directive-schema` parallelism, not new debt. (2) The statechart
`compile-routing-transitions` exhaustion edit is runtime-dead-code for the review
loops by design (DI-6 two-site coherence) — intended and documented.

## Test-review pass (2026-06-16)

REVIEW_COMPLETE — no new actionable test issues. Verified the three
task-test-review criteria over the 229 test delta (`git diff 9a07db27e..HEAD`):

- **Coverage** matches the design's chosen verification strategy. AC-1 schema
  accept/reject (ir_test, model_test, D3); AC-2 IR threading; AC-3 both exhaustion
  sites — statechart default→`:failed` regression-lock
  (`iteration-exhaustion-fires-action-test`) + author-target route, workflow_judge
  unit (goto/complete/within-limit/absent), and the governing integration path
  (`review-pass-loop-on-max-iterations-routes-to-author-target-test`) with the
  no-key hard-fail regression-lock; AC-4/5/6 definition-level (4-step review
  topology, `:on-max-iterations`, both summaries terminal + DI-4 sole-final
  PASS_STATUS, 13-step lifecycle gates + handbacks); D5 converged-standalone live
  tests. The synthetic-def-for-routing / real-def-for-template-text split is the
  documented DI-2/DI-6 decision, not a gap.
- **Well-formed**: assertions are on run/result state and template text, not on
  interactions.

Non-compliance / pre-existing (no follow-up filed): the two new converged-standalone
live tests stub the turn boundary (`prompt-execution-result-in!`) via `with-redefs`
— a stub of an infra dep, mildly at odds with the no-mocks standard — but it
follows the established convention of the same namespace's pre-existing
`delegate-…-nullable-local-model-test` (no nullable turn seam exists). Systemic,
not new debt this task should rewrite; left as-is for namespace consistency.

## Test-shaper review pass (2026-06-16)

ACTIONABLE (3 → steps.md). Engine-layer tests (ir/model/target-ir/statechart/
workflow-judge) are narrow, positive, behavior-focused; the definition-level
`assert-review-summary-handback` + `assert-sole-final-pass-status-line` helpers
compress the summary assertions well. Non-compliance: (1) the two
converged-standalone live tests duplicate ~50 lines of ceremony with only a
three-field varying axis; (2) the DI-6 author-target routing integration test
locks only negatives (no positive terminal-outcome assertion), weakening failure
signal vs its `:failed`-asserting sibling; (3) `count-substring` duplicated
across two component test namespaces. Details in steps.md.

## Test-shaper review follow-up execution (2026-06-16)

Executed all three test-shaper-review batch follow-ups (test-only; no
production/EDN/doc change).

- **Item 1 (dedupe converged-standalone live tests) — DONE.** Extracted
  `assert-converged-standalone-surfaces-review-complete` in
  `workflow_delegate_review_step_live_test.clj` carrying the shared
  models-path/context/`with-redefs`/create+execute ceremony + assertions; each
  `deftest` now expresses only the single varying axis (`definition-id` /
  `run-id` / converged `reply-prefix`). ~100 duplicated lines → one helper + two
  3-line call sites.
- **Item 2 (strengthen DI-6 routing test) — DONE.** Replaced the negative-only
  assertions in `review-pass-loop-on-max-iterations-routes-to-author-target-test`
  (`workflow_review_step_routing_test.clj`) with positive terminal-outcome shape
  mirroring the `:failed`-asserting sibling: `(= :completed (:status result))`,
  `(= :completed (:status run))`, and the handback's
  `[:step-runs "final-summary-not-converged" :accepted-result]` `some?` (an
  accepted result, not bare step existence). A regression routing exhaustion to
  a non-failed-but-wrong terminal (`:blocked`, stuck `:running`) now fails.
- **Item 3 (count-substring cross-ns duplicate) — DONE.** Folded the live test's
  only `count-substring` use into the new item-1 helper via
  `(count (re-seq #"PASS_STATUS: REVIEW_COMPLETE" result-text))` (the literal has
  no regex metachars) and removed the live test's standalone `count-substring`
  def. `count-substring` now exists in a single namespace
  (`workflow_definitions_test.clj`, substantive use in
  `assert-sole-final-pass-status-line`). Chose folding over hoisting to a shared
  util: the two namespaces are in different components (agent-session vs
  workflow-loader), so hoisting a trivial 8-line helper would add a
  cross-component test-support dependency edge — heavier than the duplication it
  removes.

Verification: `clj-paren-repair` + `clj-kondo` clean on both edited files.
`workflow-review-step-routing-test` 114/0 (incl. the strengthened DI-6 test).
`workflow-delegate-review-step-live-test`: both refactored DI-2 tests pass; the
sole failure is the documented pre-existing 229-independent
`delegate-…-nullable-local-model-test` (`:reason :missing-pass-status`,
environmental). `.scry-results/` confirmed gitignored.

## Test-shaper review pass 2 (2026-06-16)

ACTIONABLE (1 → steps.md). The DI-6 author-target routing integration test, after
its prior positive-terminal-outcome strengthening, still omits an
exhaustion-at-cap assertion: it proves the run reached the handback `:completed`
but not that the loop iterated to `:max-iterations` first, so premature
`:on-max-iterations` routing would pass. No other new test issues; engine-layer,
definition-level, and live tests remain narrow, behaviour-focused, and economical
after the prior dedup/strengthening passes.

## Test-shaper review follow-up — pass 2 execution (2026-06-16)

Executed the one test-shaper pass-2 follow-up (test-only; no production/EDN/doc
change).

- **Exhaustion-at-cap lock — DONE.** Added an attempt-count assertion to
  `review-pass-loop-on-max-iterations-routes-to-author-target-test`
  (`workflow_review_step_routing_test.clj`) mirroring the sibling
  `review-pass-loop-iteration-limit-failure-test`'s
  `(= N (count (get-in run [:step-runs "design-follow-up" :attempts])))` shape:
  `(= 2 …)` against the on-max proof's configured cap
  (`conditional-review-design-on-max-iterations-definition` sets
  `design-follow-up` `:max-iterations 2`). Proves the judged loop iterated to the
  cap *before* routing to the handback — a premature `:on-max-iterations` route
  (e.g. firing on the first follow-up) would now fail rather than passing on the
  `:completed` + accepted-handback positives alone.

Verification: `clj-paren-repair` + `clj-kondo` clean. Focused var passes
(5/0/0, was 4 assertions). Full `workflow-review-step-routing-test` namespace
green (115 assertions / 12 tests, +1 assertion).

## Test-shaper review pass 3 (2026-06-16)

ACTIONABLE (1 → steps.md). One new test-shaper issue: the deduped
converged-standalone live helper (`assert-converged-standalone-surfaces-review-complete`)
injects a per-call `reply-prefix` that no assertion checks, and its sole
result-text assertion (`count REVIEW_COMPLETE = 1`) is also met by any
review-prompt's bare REVIEW_COMPLETE reply — so it does not prove the converged
`final-summary`'s text is what surfaces as `:psi.workflow/result`, contradicting
the helper's docstring. No other new issues: engine-layer (ir/model/target-ir/
statechart/workflow-judge), definition-level handback/lifecycle, and the DI-6
routing integration test remain narrow, behaviour-focused, and economical after
the prior dedup/strengthening/exhaustion-at-cap passes; prior pass-1/pass-2
follow-ups are not duplicated.

## Test-shaper review pass 3 — follow-up executed (2026-06-16)

Resolved the pass-3 item. Added to `assert-converged-standalone-surfaces-review-complete`
(`workflow_delegate_review_step_live_test.clj`):
`(is (str/includes? result-text reply-prefix) …)`. The `reply-prefix` is unique
to the converged `final-summary` stub reply (non-final-summary stub replies are
bare `PASS_STATUS: REVIEW_COMPLETE`), so the assertion now proves
`:psi.workflow/result` carries the converged final-summary's text — giving the
ordering/plumbing claim meaningful failure signal and matching the helper's
docstring contract. The previously-unasserted `reply-prefix` axis now carries
assertion meaning.

Verification: `clj-paren-repair` + `clj-kondo` clean. Both call-site tests pass
(`review-task-design-converged-…` 5/0/0, `review-task-plan-converged-…` 5/0/0;
+1 assertion each).

Out of scope (pre-existing, untouched): in the same namespace,
`delegate-review-task-implementation-completes-with-nullable-local-model-test`
fails 3 assertions identically at HEAD (verified via `git stash`) — unrelated to
this follow-up item (which targets only the converged-standalone helper); not
addressed here.

## Test-shaper review pass 4 (2026-06-16)

REVIEW_COMPLETE — no new actionable test issues. Re-examined the DI-6 routing
test, the converged-standalone live helper, and the definition-level
review/lifecycle tests against the test-shaper lens (economical, meaningful
failures, behavior-focused, deterministic). Prior passes 1–3 resolved the
substantive items (live-test dedup, folded `count-substring`, positive
terminal-outcome + exhaustion-at-cap routing assertions, and the
result-text↔`reply-prefix` tie). Remaining engine/definition/live coverage is
narrow and behaviour-focused; no duplication of prior follow-ups. The marginal
"assert converged final-summary was bypassed in the on-max test" is structurally
guaranteed by the handback's `:goto :done` and already pinned by `:completed` +
not-`:iteration-exhausted` + handback-accepted — judged over-specification, not
added.

## Docs review pass (2026-06-16)

ACTIONABLE (1 → steps.md). Slice 4 updated only `doc/workflows.md` (prose guide)
+ CHANGELOG, per design AC-7. But `:on-max-iterations` is a new
`routing-directive-schema` key in both `model.clj` (authored grammar) and
`ir.clj` (IR); the canonical grammar/IR **reference** docs
(`doc/workflow-grammar.md` + `doc/workflow-ir.md` `transition-map` productions,
the IR key list, and routing-rules) still document only `:max-iterations`, so a
workflow author reading the reference cannot discover the new directive key or
its `:max-iterations`-required constraint. Verified `git grep` finds
`on-max-iterations` in neither reference doc. `doc/workflows.md` prose +
CHANGELOG entries are accurate and consistent with the EDN (design `:max-iterations 3`,
plan `5`, step names, PASS_STATUS strings, anchor link all match); no other
docs-review gaps found.

## Docs review follow-up execution (2026-06-16)

Executed the one docs-review batch follow-up (docs-only; no production/test/EDN
change). Added `:on-max-iterations` to the canonical grammar/IR **reference**
docs (Slice 4 had touched only the `doc/workflows.md` prose guide + CHANGELOG):

- **`doc/workflow-grammar.md`**: extended the `transition-map` production with an
  `on-max-iterations-clause?` and added the `on-max-iterations-clause ::=
  :on-max-iterations goto-target` production with the "only valid alongside
  `:max-iterations`" constraint inline.
- **`doc/workflow-ir.md`**: (1) added a "Transition directives inside `:on`
  additionally use" note listing transition-local `:max-iterations` +
  `:on-max-iterations`; (2) showed `:on-max-iterations "summary"` in the
  illustrative directive shape; (3) added two routing rules — the
  `:max-iterations`-required constraint (directive without `:max-iterations`
  rejected) and the exhaustion semantics (present → route + `:status :running`;
  absent → hard-fail `:reason :iteration-exhausted`); (4) extended the formal
  `transition-map` production with `:on-max-iterations? goto-target` + constraint
  comment.
- **`doc/workflow-grammar-concepts.md`** (optional per the item): added a
  paragraph after the §`:max-iterations` note describing the optional
  `:on-max-iterations` companion key (valued like `:goto`), its exhaustion-routing
  purpose, and the `:max-iterations`-required constraint.

Placement of `:on-max-iterations` as **transition-local** (inside the `:on`
directive / `transition-map`), not a step-level `control-flow` key, matches the
code: it lives in `routing-directive-schema` (model.clj/ir.clj), valued like
`:goto` (`:next | :previous | :done | step-name`), `:and`-guarded by
`on-max-iterations-requires-max-iterations?`. The item's "control-flow block"
mention is satisfied by documenting the key where it actually belongs
(transition-map) rather than mis-placing it at step level.

## Docs review pass 2 (2026-06-16)

ACTIONABLE (1 → steps.md). `CHANGELOG.md` `[Unreleased]` Changed entry still
says `review-task-design` repeats "up to 6 total passes", but the authored cap
is `:max-iterations 3` and `doc/workflows.md` (Slice 4) says "at most three total
times" — stale 6→3 (`de19cc5bf` lowered the cap without touching CHANGELOG),
now contradicting both the EDN and this task's own prose guide. `review-task-plan`
"5 total passes" in the same sentence is accurate. No other docs gaps: the prior
grammar/IR-reference follow-up is resolved (`doc/workflow-grammar.md`,
`doc/workflow-ir.md`, `doc/workflow-grammar-concepts.md` now carry
`:on-max-iterations`), and `doc/workflows.md` + CHANGELOG `:on-max-iterations`/
handback entries match the EDN (design 3, plan 5, step names, PASS_STATUS
strings, anchor link).

## Docs review pass 2 follow-up execution (2026-06-16)

DONE. Changed `CHANGELOG.md` `[Unreleased]` → Changed: `review-task-design`
"up to 6 total passes" → "up to 3 total passes" (matching authored
`:max-iterations 3` and `doc/workflows.md` "at most three total times").
`review-task-plan` "5 total passes" left intact (matches `:max-iterations 5`).
Docs-only edit; no code/test impact.

## Docs review pass 3 (2026-06-16)

ACTIONABLE (1 → steps.md). User-facing docs document only the *converged*
standalone `/delegate review-task-design`/`-plan` result-text change; the
*not-converged* standalone case (now empty result text — `final-summary-not-converged`
is ordered before the never-run converged `final-summary`, which the
`(last :step-order)` standalone path reads; previously a hard-fail) is
documented nowhere. Design D5 "known degradation". Verified absent from
`CHANGELOG.md` + `doc/workflows.md`. No other docs gaps: grammar/IR/concepts
reference + workflows.md prose + CHANGELOG `:on-max-iterations`/handback entries
match the EDN (design 3, plan 5, step names, PASS_STATUS strings, anchor link);
README has no references to touch.

## Docs review pass 3 follow-up execution (2026-06-16)

DONE. Documented the not-converged standalone result-text behaviour change in
both user-facing surfaces (design D5 "known degradation"):
- `CHANGELOG.md` `[Unreleased]` → Changed: extended the existing
  converged-standalone `PASS_STATUS` entry with the not-converging standalone
  case — handback summary (`final-summary-not-converged`,
  `PASS_STATUS: ACTIONABLE_FEEDBACK`) surfaces only via the `task-lifecycle`
  gate; standalone non-converging runs now yield empty result text instead of
  the prior `:reason :iteration-exhausted` hard failure.
- `doc/workflows.md` `Author-routed loop exhaustion` section: added a
  "Standalone non-converging output" paragraph explaining the step ordering
  (not-converged summary before converged `final-summary`), why `(last
  :step-order)` reads the never-run converged summary → empty result text, the
  replaced hard-fail behaviour, and that the handback summary is observable
  under `task-lifecycle`.
Docs-only edit; no code/test impact.
