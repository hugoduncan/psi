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
