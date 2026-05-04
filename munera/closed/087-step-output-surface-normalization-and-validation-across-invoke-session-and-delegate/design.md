Goal: define and implement canonical output-surface normalization and validation across workflow IR `:type :invoke`, `:type :session`, and `:type :delegate` steps.

## Intent

Task `077` made a strong distinction between:

- step-local output surfaces addressable via `:output`
- the step's resulting value as a whole, expressed via `:yields`

That distinction is central to shared data-flow across invoke, session, and delegate steps. This task makes it executable and enforceable by normalizing what outputs each step form exposes and validating references against those per-type surfaces.

Boundary note:

- this task owns the canonical set of step-local output keys per step type and the validation of `:output` references against those keys
- task `088` owns how refs/projections resolve values once a referenced output or yield surface has been selected
- task `084` owns how invoke-step execution artifacts are recorded and exposed through introspection after output-surface semantics are already defined

## Problem statement

Different workflow step forms expose different natural outputs:

- invoke steps: `:data`, `:summary`, optional `:result`
- session steps: `:final-llm-reply`, `:transcript`, optional `:result`
- delegate steps: first-cut yielded-value-first semantics; they do not re-expose callee step-local outputs, and any step-local outputs must be explicitly modeled on the delegate step itself if introduced later

Without one canonical normalization and validation layer:

- downstream `{:step ... :output ...}` refs may become ambiguous or runtime-fragile
- output names may drift between docs, compilers, runtime execution, and introspection
- yielded values could be confused with outputs again
- mixed-form workflows could fail late instead of being validated clearly

## Scope

In scope:

- define the canonical output surfaces exposed by each step type at runtime
- normalize those output surfaces into one consistent runtime representation
- validate `{:step ... :output ...}` refs against the referenced step type's exposed outputs
- keep `:yield` refs distinct from `:output` refs in validation and runtime semantics
- add focused tests for representative valid and invalid output references across invoke/session/delegate workflows
- align schema/compiler/runtime validation with `doc/workflow-ir.md` and task `077`

Out of scope:

- inventing many optional new outputs beyond first cut
- broad redesign of yielded-value semantics
- broad UI work beyond what validation/introspection coherence requires

## Desired outcome

A developer and workflow author can know exactly which `:output` keys are valid for each step form, and runtime/compiler validation enforces those rules consistently.

## Acceptance

- canonical per-step-type output surfaces are defined in code and runtime semantics
- output surfaces are normalized consistently across invoke/session/delegate execution
- `:output` refs are validated against referenced step-type surfaces
- `:yield` refs remain distinct and validated against yielded-value semantics rather than output surfaces
- focused tests prove representative valid and invalid cross-step references
- implemented behavior aligns with task `077` and `doc/workflow-ir.md`
