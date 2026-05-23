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
