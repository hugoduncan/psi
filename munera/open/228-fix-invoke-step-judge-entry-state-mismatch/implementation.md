# 228 — implementation notes

## Spike: reproduce + confirm mechanism (ψ)

Confirmed the `:handler-entry-state-mismatch` root cause empirically.

Added a temporary spike test to
`components/deterministic-operation-runtime/test/.../core_test.clj` that calls
`runtime/invoke-operation` twice against one attempt:

```
state*: {:workflows {:runs {"run-1" {:status :running
          :step-runs {"clarity-status" {:attempts [{:attempt-id "attempt-1"}]}}}}}}
invocation: {:ctx {:state* state*} :workflow-run-id "run-1"
             :workflow-attempt-id "attempt-1" :step-id "clarity-status"}
op-1 (step :operation)        => :ok    ; leaves :operation-handler-entry-state :entered
op-2 (:judge pass-feedback)   => :error :workflow-stopped
                                 :stop-reason :handler-entry-state-mismatch
```

`bb clojure:test:scry --namespace psi.deterministic-operation-runtime.core-test`
⇒ 4 tests / 19 assertions green (the spike asserted the buggy behaviour and
passed). **Spike test then reverted** — it locked in the bug; the real
characterization test (asserting the judge *succeeds* post-fix) goes in during
build.

### Confirmed facts

- Both the step `:operation` (`statechart_runtime/step_execution/invoke-step-runtime-result`)
  and the invoke `:judge` (`agent_session/workflow_judge/execute-invoke-judge!`)
  pass `:workflow-attempt-id` = `(-> step-runs <step-id> :attempts last :attempt-id)`
  — i.e. the **same** attempt.
- Entry phase keys are shared: `:operation-start-state`, `:operation-call-state`,
  `:operation-handler-entry-state` (in `deterministic-operation-runtime/core`,
  via `workflow-coordination.ordinary-entry/transition-latest-attempt!`).
- `prepare-workflow-operation-handler-entry!` has `:ok-states #{:pending :entered}`,
  so it short-circuits when the key is already `:entered` (left by op-1) without
  resetting to `:pending`; `enter-workflow-operation-handler!` then requires
  `:pending` → mismatch.
- Session-step + invoke-judge is safe: actor turn uses `:turn-*-state` keys
  (`workflow_runtime/turn_execution_contract`), a different namespace — which is
  the model for fix (a).

## Decision

Fix shape (a): per-operation phase-key namespacing (judge role gets a distinct
key set). See design.md.

## Plan/steps ambiguity review (ψ, 2026-06-13)

Actionable ambiguities found:

- **Injection point undecided.** Plan §Mechanism says role is "woven into the
  phase-opts maps the helpers pass" while Slice 2 says "thread it into
  `transition-workflow-operation-phase!` / the five phase helpers" — two distinct
  strategies: (i) central remap of supplied phase-opts in the single
  `transition-workflow-operation-phase!` chokepoint (reads `(:operation-role
  invocation)` there), vs (ii) per-helper key rewriting in each phase helper.
  `λone_way` → pick one. The chokepoint already receives `invocation`, so (i) is
  the single-point option; plan must state which.
- **"Five" vs six phase helpers.** Design and plan call them "the five
  transition helpers" but enumerate/the code has **six**: `reserve`,
  `commit-start`, `begin-call`, `commit-call`, `prepare-handler-entry`,
  `enter-handler`. Ambiguous coverage — if per-helper (ii), an implementer may
  miss one (notably `enter-handler`, the helper that throws the mismatch). Fix
  the count or moot it by choosing the chokepoint strategy.
- **Regression-suite gap.** The actual direct readers/asserters of
  `:operation-start-state` / `:operation-call-state` /
  `:operation-handler-entry-state` live in
  `components/agent-session/test/.../workflow_statechart_runtime_call_start_cancellation_test.clj`
  (task-225 coverage). Slice 4/5 name the regression suites as
  `deterministic-operation-runtime`, `workflow-coordination`, `agent-session
  workflow-judge`, `workflow-runtime step-execution` — it is ambiguous whether
  this call-start-cancellation suite (the real default-role key-assertion guard)
  is in the green set. Name it explicitly.
- **Step-operation explicit-role left to taste.** Slice 3 / plan say add
  `:operation-role :step` "only if it improves clarity," so the final artifact
  state (key present vs absent at the step call-site) is non-determinate. Decide
  one way so the end state is fixed.

## Plan/steps ambiguity resolutions (ψ, 2026-06-13)

Resolved the four review follow-ups; design.md, plan.md, and steps.md updated to
single, determinate descriptions.

- **Injection point → single chokepoint.** `role-phase-key` is applied **once**
  inside `transition-workflow-operation-phase!` (the one function every phase
  helper already calls). It reads `(:operation-role invocation)` and rewrites the
  supplied `phase-opts` keys — `:phase-key`, `:timestamp-key`, `:count-key`, and
  each `:required-phases` entry's `:key` — via `role-phase-key`. Chosen over
  per-helper rewriting (`λone_way`, fewer edit sites, no risk of missing
  `enter-handler`). Verified against `core.clj`: the six helpers all funnel their
  phase-opts through this single function, so the chokepoint covers every key.
- **Helper count → six (moot under chokepoint).** Confirmed six helpers:
  `reserve`, `commit-start`, `begin-call`, `commit-call`, `prepare-handler-entry`,
  `enter-handler`. Under the chokepoint strategy they are left unchanged, so the
  count is informational only. Corrected wording in design.md + plan.md.
- **Regression suite named.** `components/agent-session/test/.../
  workflow_statechart_runtime_call_start_cancellation_test.clj` is the suite that
  directly asserts the default-role `:operation-*-state` keys; named explicitly in
  plan Slice 4/5 and steps Slice 4/5 green sets.
- **Step-operation role → omit.** The step `:operation` invocation carries **no**
  `:operation-role` key (absent ≡ `:step` at the chokepoint). Single rule: only
  judge invocations annotate a role. "Only if it improves clarity" deferral
  removed. Keeps every single-operation step's `:operation-*` keys byte-identical.

## Plan/steps inconsistency review (ψ, 2026-06-13)

Actionable inconsistencies found (distinct from the resolved ambiguity pass):

- **design.md self-contradiction on judge `:workflow-attempt-id`.** Problem §2
  states the judge "invocation carries `:workflow-attempt-id nil`, and the entry
  uses `:attempt-id-required? false`, so its transitions target the same latest
  attempt." But design.md's own "Spike outcome (confirmed)" section *and*
  implementation.md "Confirmed facts" *and* the code
  (`workflow_judge/execute-invoke-judge!` and
  `step_execution/invoke-step-runtime-result`) all pass an **explicit**
  `:workflow-attempt-id = (… :attempts last :attempt-id)` — the real latest
  attempt id, not nil. The mechanism differs materially: with a real id +
  `:attempt-id-required? false`, `ordinary-entry` *does* assert equality with the
  latest attempt (`(or attempt-id-required? workflow-attempt-id)` is truthy);
  the nil path would *skip* that check. Correct Problem §2 to say the judge
  passes the explicit latest attempt id (sharing the attempt that way), aligning
  it with Spike-outcome / implementation.md / code.

- **plan.md Slice 3 retains the deferral the ambiguity pass removed.** Plan
  §"Call-site threading" decided (`λone_way`) to **omit** `:operation-role` at
  the step `:operation` call-site ("do **not** add an explicit
  `:operation-role :step`"), and steps.md Slice 3 matches that. But plan.md
  §"Slice order" Slice 3 still reads "(and make the step-operation default
  explicit if it aids clarity)" — the exact "only if it improves clarity"
  deferral that follow-up item 4 claims to have removed. Plan Slice 3 thus
  contradicts plan §Call-site-threading and steps.md Slice 3. Remove the
  parenthetical so the call-site decision is determinate everywhere.

## Build — Slice 1: characterization test (red) (ψ, 2026-06-13)

Added `invoke-step-operation-then-judge-operation-share-one-attempt-test` to
`deterministic-operation-runtime/core_test.clj`. It drives `invoke-operation`
twice against one attempt — step op (default role) then judge op
(`:operation-role :judge`) — and asserts the judge op succeeds and lands
`:judge-operation-handler-entry-state :entered` while the step op's
`:operation-handler-entry-state` stays `:entered`.

Red baseline captured: `bb clojure:test:scry --namespace
psi.deterministic-operation-runtime.core-test` → 3 tests pass, this test fails
(3 assertions) with `:reason :workflow-stopped` /
`:stop-reason :handler-entry-state-mismatch` on the judge op (verified in
`.scry-results`). Confirms the bug exactly as the spike did.

## Build — Slice 2: runtime phase-key namespacing (green) (ψ, 2026-06-13)

Added two private helpers to `deterministic-operation-runtime/core`:
`role-phase-key` (`(role, base-key)` → `judge-`-prefixed keyword for `:judge`,
unchanged for `:step`/nil) and `role-phase-opts` (rewrites `:phase-key`,
`:timestamp-key`, `:count-key`, and each `:required-phases` `:key` through
`role-phase-key`). Applied once at the single `transition-workflow-operation-phase!`
chokepoint via `(role-phase-opts (:operation-role invocation) phase-opts)`. The
six phase helpers and `ordinary-entry` are unchanged.

Deviation from plan wording: factored the per-key rewrite into a separate
`role-phase-opts` helper (rather than inlining the `cond->` in the chokepoint)
for readability — still a single application site. Default/`:step` path is a
no-op (`cond->` with `role` ≠ `:judge` returns base keys unchanged), so
single-operation steps keep byte-identical `:operation-*` keys.

Slice 1 characterization test now green:
`bb clojure:test:scry --namespace psi.deterministic-operation-runtime.core-test`
→ 4 tests / 22 assertions, 0 failures. clj-kondo clean on the edited namespace.

## Build — Slice 3 + discovered second defect (ψ, 2026-06-13)

**Slice 3 (judge call-site role).** Added `:operation-role :judge` to the judge
invocation map in `agent_session/workflow_judge.clj/execute-invoke-judge!`. The
step `:operation` call-site (`step_execution.clj/invoke-step-runtime-result`)
omits the key as designed. `invoke-operation-in` passes the invocation through
unchanged; no registry/schema change needed (invocation map is open).

**DEVIATION FROM DESIGN — second, distinct defect discovered.** The design/spike
only exercised a *single* clarity-status pass. Running the existing end-to-end
`workflow-review-step-routing-test` suite exposed a SECOND defect that the
phase-key fix alone does not resolve, blocking acceptance criteria #2/#4 (full
REPEAT/DONE routing):

- With the 228 phase-key fix, the **first** clarity-status pass now succeeds and
  routes REPEAT. The **second** clarity-status attempt's *step* `:operation`
  (`workflow/constant-routing`, default role) then aborts with
  `:stop-reason :attempt-mismatch`.
- Root cause: `invoke-step-runtime-result` derived `:workflow-attempt-id` from
  the `workflow-run` **snapshot** captured in the `:step/enter` action *before*
  the new attempt was appended to `state*`. First attempt: that snapshot has no
  attempts for the step → `nil` → task-225's attempt-equality guard
  (`(or attempt-id-required? workflow-attempt-id)`) is skipped, so it happened to
  work. REPEAT: the stale snapshot's latest attempt id is the *previous* attempt,
  which no longer equals the live latest attempt → `:attempt-mismatch`.
- Same lineage as 228: both faults were introduced by task-225's cancellation
  entry machine (commit 04861433f) and both surface on the clarity-status
  invoke-op+judge step. The phase-key fix uncovered this one.
- Fix (`cause(structural) → redesign > patch`): thread the authoritative,
  just-started `attempt-id` from the `:step/enter` caller into
  `invoke-step-runtime-result` (signature `… workflow-run attempt-id`; both call
  sites updated — `statechart_runtime.clj` and `step_execution.clj`
  `execute-actor-step!`) and use it for `:workflow-attempt-id` instead of
  re-deriving from the stale snapshot. The `workflow-run` snapshot is still used
  for `resolve-invoke-args` (args reference stable prior-step outputs). This also
  makes the *first* attempt properly assert attempt equality (id now non-nil),
  tightening the cancellation guard rather than weakening it.

**Verification.** `workflow-review-step-routing-test` now 11 tests / 82
assertions all green (was 3 failing / 21 assertions before — the REPEAT-loop and
iteration-limit subtests). Regression suites green: deterministic-operation-runtime
core (4/22), workflow-statechart-runtime-call-start-cancellation (14/63),
workflow-judge (17/88), workflow-judge-cancellation (8/34),
workflow-statechart-runtime (and cancellation) + workflow-execution (34/153),
workflow-runtime step-execution (10/63). clj-kondo clean on all changed
namespaces.

## Build — Slices 4 & 5: cancellation coverage + close-out (ψ, 2026-06-13)

**Slice 4.** Added `judge-role-operation-honors-workflow-cancellation-test` to
`deterministic-operation-runtime/core_test.clj`: a `:operation-role :judge`
operation against a cancelled run refuses to start and yields a clean
`:workflow-stopped` terminal without invoking its handler. Proves the phase-key
namespacing preserves the task-225 cooperative cancellation guard. The existing
default-role `invoke-operation-honors-workflow-cancellation-test` stays green.

**Slice 5.** Workflow-level REPEAT/DONE routing is verified by the existing
`workflow-review-step-routing-test` clarity-status suites (were 3 failing
pre-fix, now 11/11 green). CHANGELOG `Fixed` entry added covering both faults. No
schema change needed (`:operation-role` is an open additive invocation key).

**Acceptance criteria status:** #1 characterization test (green), #2 invoke
operation+judge runs both ops and routes (green via review-step-routing), #3
task-225 cancellation preserved (judge-role + default-role cancellation tests +
call-start-cancellation/judge-cancellation suites green), #4 review-task-design
full REPEAT/DONE pass unblocked (review-step-routing 11/82 green), #5 clj-kondo
clean + Scry suites green.

**Final test summary (all green):**
- deterministic-operation-runtime core: 5 tests / 24 assertions
- workflow-statechart-runtime-call-start-cancellation: 14 / 63
- workflow-judge: 17 / 88 ; workflow-judge-cancellation: 8 / 34
- workflow-statechart-runtime + cancellation + workflow-execution: 34 / 153
- workflow-runtime step-execution: 10 / 63 ; lifecycle + state: 5 / 17
- workflow-review-step-routing: 11 / 82
- github find-issue-integration: 1 / 13

**Changed namespaces:**
`deterministic-operation-runtime/core` (role-phase-key/role-phase-opts +
chokepoint), `agent-session/workflow_judge` (`:operation-role :judge`),
`workflow-runtime/statechart_runtime` + `.../step_execution` (thread authoritative
`attempt-id`).

## Plan/steps inconsistency resolutions (ψ, 2026-06-13)

Resolved the two inconsistency-pass follow-ups; design.md and plan.md aligned to
the verified code facts.

- **design.md Problem §2 corrected.** Rewrote the judge step from the wrong
  `:workflow-attempt-id nil` claim to the **explicit** latest attempt id
  (`(-> step-runs <step-id> :attempts last :attempt-id)`), matching
  `workflow_judge/execute-invoke-judge!` (verified `workflow_judge.clj:129`) and
  `step_execution/invoke-step-runtime-result` (`step_execution.clj:55`). Added
  that with a real id + `:attempt-id-required? false`, `ordinary-entry` still
  asserts equality with the latest attempt because `(or attempt-id-required?
  workflow-attempt-id)` is truthy (verified `ordinary_entry.clj:77`). Problem §2
  is now consistent with design's own "Spike outcome", implementation.md
  "Confirmed facts", and the code.

- **plan.md Slice 3 deferral removed.** Dropped "(and make the step-operation
  default explicit if it aids clarity)" from §"Slice order" Slice 3 and restated
  it as the determinate decision (step call-site omits `:operation-role`; absent
  ≡ `:step`; only judge invocations annotate a role), cross-referencing
  §"Call-site threading". The call-site decision is now determinate across plan
  §Slice-order, plan §Call-site-threading, and steps.md Slice 3.

## Implementation review (ψ, 2026-06-13)

Reviewed code, tests, design/plan/steps coherence, lint, CHANGELOG. Overall
solid — fix is structural, key coverage complete, tests state-based (no mocks).

Verified:
- `role-phase-opts` rewrites every attempt-state key actually written by
  `ordinary-entry/apply-phase-update` (`:phase-key`, `:timestamp-key`,
  `:count-key`) plus each `:required-phases` `:key` guard. No un-namespaced key
  leaks at the single `transition-workflow-operation-phase!` chokepoint. The six
  helpers stay byte-identical for the default/`:step` role.
- Second-defect fix is correct: `append-and-start-attempt-if-live!`
  (statechart_runtime.clj) appends the new attempt to `state*` **before**
  `invoke-step-runtime-result` runs, so the threaded just-started `attempt-id`
  *is* the live latest attempt — the task-225 equality guard now matches on every
  pass (and is tightened, not weakened, since the first-attempt id is no longer
  nil). Both call sites (`statechart_runtime.clj`, `step_execution.clj`
  `execute-actor-step!`) have `attempt-id` in scope.
- clj-kondo clean on all changed namespaces; working tree clean; CHANGELOG
  `Fixed` entry is user-facing and covers both faults.

Actionable finding:
- **No focused regression test for the second defect (`:attempt-mismatch` on
  REPEAT).** The handler-entry-state-mismatch defect got a focused unit
  characterization test (`…share-one-attempt-test`), but the stale-snapshot
  `:attempt-mismatch` fix relies solely on the broad end-to-end
  `workflow-review-step-routing-test` REPEAT-loop subtests. A focused test (at
  `step_execution` / `deterministic-operation-runtime` level) pinning "a
  re-executed invoke step's operation uses the just-started attempt-id, not the
  stale `workflow-run` snapshot's latest attempt" would localize the regression
  and give parity with the first defect's coverage. Lower diagnosability if it
  ever regresses through only the end-to-end suite.

Not flagged as actionable (handled): the second defect is a scope addition over
the original handler-entry-state-mismatch design, but it is documented as a
deviation (design.md Build-discovery, steps Slice 3b) and is required to satisfy
acceptance criteria #2/#4 — defensible to keep in this task.
