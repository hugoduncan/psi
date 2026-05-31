# Implementation

Task created to allow workflow self-loop control edges while keeping invalid self/future data dependencies forbidden.

Motivating case:

- `implement-task` should be expressible as a single judged `implement-pass` step that loops to itself on `REPEAT`
- current compiler behavior rejects that because it treats self control references and self data dependencies as the same class of error

Target design:

- self-loop `:goto` is valid control flow
- self-sourced yields remain invalid data flow
- forward-sourced yields remain invalid data flow

2026-05-22 ambiguity review:

- Actionable ambiguity: design/plan/steps say preserve rejection of self/future data dependencies in contributions/vars/fields and similar data-flow positions, but they do not enumerate the full canonical IR source-ref surfaces that must keep that rule (`session` contributions, `delegate` prompt/context, `invoke` args, judge refs, delegate-target source-specs, etc.). Clarify whether the self/future-data-dependency prohibition applies uniformly to every `step-source-refs` surface, not just representative template/contribution cases.

2026-05-22 ambiguity follow-up execution: re-read `components/workflow-runtime/src/psi/workflow_runtime/ir.clj` and confirmed the canonical semantic-validation gather surface is `step-source-refs`, which uniformly aggregates step `:invoke` arg refs, step `:session` contribution/template refs, delegate target/prompt/context refs, and judge-owned `:llm`/`:invoke` refs before `ref-errors` applies the prior-step rule. Updated `design.md`, `plan.md`, and `steps.md` to make that contract explicit: self/future-step rejection remains a uniform data-flow rule across every canonical `step-source-refs` surface, while self-loop `:goto` control edges are the only self-reference class being relaxed in this task.

2026-05-22 inconsistency review: actionable mismatch. `design.md` requires a more precise compiler error surface that distinguishes invalid self/future data dependencies from other transition/IR issues, but the referenced IR validation/docs still collapse self and forward data refs into the single `:non-prior-step-ref` error/message (`"forward/self references are not allowed"`). Added a `design-steps.md` follow-up to either make the task require separate validation/error cases or explicitly narrow the requirement.

2026-05-22 inconsistency follow-up execution: re-read `components/workflow-runtime/src/psi/workflow_runtime/ir.clj` plus the existing formatting/IR tests and confirmed the current owned semantic surface intentionally uses one `:non-prior-step-ref` case for both self and forward data refs while already separating those failures from other transition/IR errors like `:routing-without-judge`, `:judge-without-routing`, and missing-step/output/yield cases. Updated `design.md`, `plan.md`, and `steps.md` to narrow the requirement accordingly: this task still requires self-loop `:goto` control edges to compile and non-prior data refs to remain invalid, but does not require separate self-vs-forward data-dependency error classes/messages.

2026-05-23 implementation execution: audited the live IR validation path and found the key behavior split was already present in code: `step-source-refs` / `ref-errors` enforce prior-only data dependencies, while `:on` / `:goto` routing is not part of that data-flow validation surface. The remaining gap was proof and the representative workflow shape. Added focused workflow-runtime and agent-session tests proving self-loop control edges validate cleanly while self data refs still fail as `:non-prior-step-ref`, and simplified `.psi/workflows/implement-task.md` back to the intended single-step self-looping `implement-pass` shape by removing the judge's invalid self-yield supporting-context ref. Verified focused namespaces via direct `clojure -M:test-paths` `clojure.test/run-tests` execution (26 tests, 215 assertions, 0 failures) and reloaded workflow definitions from `.` successfully; `implement-task` now loads and compile-validates with no semantic errors.

2026-05-23 task-implementation-review: no new actionable feedback. Re-read task artifacts, `components/workflow-runtime/src/psi/workflow_runtime/ir.clj`, focused workflow-runtime/agent-session IR + target-compiler tests, `.psi/workflows/implement-task.md`, and reran the focused self-loop validation suite (20 tests, 187 assertions, 0 failures). `steps.md` already covers the implemented scope; no additional follow-up items were needed.

2026-05-23 follow-up execution: re-read the preloaded review result plus `steps.md`, `implementation.md`, `design.md`, and `plan.md`; there were no newly added unchecked actionable follow-up items to execute, so no task artifact or code changes were required this pass.

2026-05-23 task-test-review: actionable test gap. The focused self-loop proof covers the allowed self `:goto` case plus a generic non-prior data-ref failure, but it does not yet prove the preserved self/future-data rejection across every canonical `step-source-refs` surface the task artifacts now claim: delegate target source-spec refs, delegate prompt `:map` refs, delegate context refs, and judge-owned `:llm` / `:invoke` refs are not each exercised by a focused self/forward non-prior-step test. Added `steps.md` follow-up coverage items rather than widening implementation scope.

2026-05-23 follow-up execution: completed both newly added unchecked `steps.md` items by extending the mirrored workflow IR semantic-validation tests in `components/workflow-runtime/test/psi/workflow_runtime/ir_test.clj` and `components/agent-session/test/psi/agent_session/workflow_ir_test.clj`. Added focused `:non-prior-step-ref` proof for delegate target source-spec self refs, delegate prompt `:map` future refs, delegate context self refs, and judge-owned future refs for both `:llm` session contributions and `:invoke` args. Verified with focused `clojure -M:test-paths` execution for both namespaces (7 tests, 157 assertions, 0 failures).

2026-05-23 test-shaper review: no new actionable feedback. Re-read `design.md`, `plan.md`, `steps.md`, `implementation.md`, `.psi/workflows/implement-task.md`, and the focused workflow IR/compiler tests in `components/workflow-runtime/test/psi/workflow_runtime/ir_test.clj` plus `components/agent-session/test/psi/agent_session/workflow_ir_test.clj`, then reran the focused validation suite (7 tests, 157 assertions, 0 failures). The proof set is already behavior-partitioned and covers the canonical `step-source-refs` surfaces claimed by the task artifacts without obvious redundant-case sprawl or hidden intent, so `steps.md` remained unchanged.

2026-05-23 follow-up execution: re-read the preloaded review result in `implementation.md` plus `steps.md`, `design.md`, and `plan.md`; there were no newly added unchecked actionable follow-up items in `steps.md`, so no artifact or code changes were required this pass.

2026-05-23 code-shaper review: no new actionable feedback. Re-read `.psi/skills/code-shaper/SKILL.md`, the task artifacts, `components/workflow-runtime/src/psi/workflow_runtime/ir.clj`, `.psi/workflows/implement-task.md`, and the focused workflow IR/compiler tests in `components/workflow-runtime/test/psi/workflow_runtime/ir_test.clj`, `components/workflow-runtime/test/psi/workflow_runtime/target_ir_compiler_test.clj`, `components/agent-session/test/psi/agent_session/workflow_ir_test.clj`, and `components/agent-session/test/psi/agent_session/workflow_target_ir_compiler_test.clj`. The implementation already keeps control-edge routing separate from `step-source-refs` data-flow validation, the representative workflow shape is locally comprehensible, and the focused proof set covers the claimed canonical surfaces without an obvious simplicity/consistency/robustness gap, so `steps.md` remained unchanged and no new actionable feedback was found.

2026-05-23 follow-up execution: re-read the preloaded review result plus `steps.md`, `implementation.md`, `design.md`, and `plan.md`; there were no newly added unchecked actionable follow-up items in `steps.md`, so no task artifact or code changes were required this pass.
