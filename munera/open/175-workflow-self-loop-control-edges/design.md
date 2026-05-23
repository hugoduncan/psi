# 175 workflow self-loop control edges

## Intent

Allow deterministic workflows to express self-looping control flow at the step-transition level, so a step may `:goto` itself through judged control edges without requiring an artificial intermediate status step.

This task exists because the current workflow compiler rejects self references as a blanket rule, even when the reference is only a control-transfer edge and not a data dependency. That restriction forces awkward workflow shapes like `implement-pass -> implementation-status -> implement-pass` when the intended structure is simply `implement-pass` with a judge that may repeat the same step.

## Problem

The current compiler error treats all self references as invalid:

- self `:goto` control edges are rejected
- self data dependencies are also rejected

Those are not the same thing.

A self-loop control edge is a valid workflow pattern when bounded by runtime loop controls such as `:max-iterations`. A self data dependency, by contrast, is a real invalid dependency because the step would need its own future output as input.

The current blanket prohibition weakens the workflow DSL and creates unnecessary extra steps whose only job is to satisfy compilation constraints.

## Desired outcome

The workflow model should distinguish between:

- **self-loop control edges** — allowed
- **self data dependencies** — still forbidden
- **forward data dependencies on later steps** — still forbidden

After this task:

- a step may `:goto` itself in `:on`
- a step may still have runtime loop guards such as `:max-iterations`
- a step may not source its own yield as an input/contribution dependency
- the compiler error surface should become more precise so valid self-loop control flow compiles cleanly while invalid self-data references still fail clearly

## Scope

This task includes:

- auditing the workflow IR/compiler path that currently rejects self references
- distinguishing control-edge validation from data-dependency validation
- allowing self-loop `:goto` edges in workflow transitions
- preserving rejection of invalid self/future data dependencies across every canonical `step-source-refs` data-flow surface, not only representative contribution/template cases
- updating or adding focused workflow compiler tests
- updating one representative workflow, likely `implement-task`, back to the simpler intended self-loop form once compiler support exists

## Out of scope

This task does not include:

- removing runtime iteration guards
- changing the semantics of `:max-iterations`
- allowing arbitrary cyclic data dependencies
- redesigning workflow judge semantics generally
- changing unrelated workflow runtime execution behavior beyond what is required to support valid self-loop control edges

## Required behavior

The implementation must preserve these rules:

1. A step may transition to itself through `:on` / `:goto`.
2. Self-loop control edges remain subject to existing runtime loop controls, including `:max-iterations` where present.
3. A step may not depend on its own future yield in any canonical IR data-flow source-ref surface gathered by `step-source-refs`. For this task that uniformly includes step `:invoke` arg refs, step `:session` contributions/template vars, delegate target source-spec refs, delegate prompt-string template/map refs, delegate context contributions, and judge-owned source refs (`:llm` session contributions/template vars or `:invoke` args).
4. References to later steps in those same data-flow positions remain invalid.
5. Compiler errors should continue to distinguish invalid data dependencies from other transition/IR issues, but this task does not require separate self-vs-forward data-dependency classes/messages. The existing shared `:non-prior-step-ref` semantic error/message for non-prior data refs remains acceptable so long as valid self-loop `:goto` control edges compile cleanly and non-data-flow transition/IR issues still surface through their own error cases.

## Representative target use case

`implement-task` should be able to express:

- one `implement-pass` step
- a judge attached to that step
- `REPEAT -> implement-pass`
- `DONE -> final-summary`

without requiring a separate `implementation-status` session step.

## Acceptance

This task is complete when:

- the workflow compiler accepts self-loop `:goto` control edges
- the compiler still rejects self or forward data dependencies
- focused tests prove the distinction
- `implement-task` or another representative workflow uses the simpler self-loop control form successfully
- the resulting workflow compiles and can be reloaded without IR compilation failure
