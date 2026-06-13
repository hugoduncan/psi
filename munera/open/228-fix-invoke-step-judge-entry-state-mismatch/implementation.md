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
