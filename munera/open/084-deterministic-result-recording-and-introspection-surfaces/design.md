Goal: define and implement coherent result-recording and introspection surfaces for deterministic workflow invoke steps.

## Intent

Task `083` makes deterministic invoke steps executable. This task ensures their execution becomes visible, inspectable, and referenceable in a way that is structurally coherent with the rest of the workflow runtime.

Boundary note:

- task `087` owns which step-local output keys exist and how `:output` refs validate against them
- task `088` owns how source refs and projections resolve values from workflow/prior-step data
- this task owns how invoke execution artifacts are recorded in runtime state and exposed through introspection/query surfaces after those semantics are already defined

The focus is on the runtime surfaces that let developers, workflow authors, and debugging tools understand what happened during deterministic execution:

- attempt records
- accepted/terminal results
- step-local outputs
- yielded values
- diagnostics and failure details
- introspection/query surfaces
- history/event recording where applicable

## Problem statement

A deterministic invoke step is only fully integrated once its runtime artifacts are consistently recorded and exposed.

Without this slice:

- invoke-step execution may work operationally but remain hard to inspect or debug
- downstream reference semantics may drift from what attempt/result surfaces actually store
- introspection could remain biased toward session-oriented execution
- deterministic steps might expose ad hoc result shapes instead of stable canonical surfaces

## Scope

In scope:

- define the runtime recording shape for invoke attempts and accepted results
- record already-defined canonical invoke outputs (`:data`, `:summary`, optional `:result`) in runtime state coherently
- define how yielded values derived from invoke steps appear in runtime state and introspection
- expose deterministic result/diagnostic data through existing or extended workflow introspection surfaces
- add focused tests proving recording and query behavior for representative invoke success/failure cases
- keep recording/introspection surfaces aligned with `doc/workflow-ir.md` and task `077`

Out of scope:

- redesigning all workflow introspection from scratch
- broad UI work beyond what is needed to expose coherent canonical surfaces
- adding many real operations or large end-user documentation expansions

## Desired outcome

After an invoke step runs, the runtime exposes one coherent story for:

- what arguments were effectively invoked
- what result was returned
- what outputs are available for downstream references
- what yielded value the step produced
- what failure/diagnostic information exists when the step does not succeed

## Acceptance

- invoke attempt/result recording is explicit and coherent in runtime state
- canonical invoke outputs and yielded values are visible through workflow runtime surfaces
- diagnostics/failure details are preserved in a structured, inspectable way
- introspection/query surfaces expose invoke-step result data consistently enough for debugging and downstream reasoning
- focused tests prove representative success and failure recording/query cases
- the implemented surfaces align with task `077`, task `083`, and `doc/workflow-ir.md`
