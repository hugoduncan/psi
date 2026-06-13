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
