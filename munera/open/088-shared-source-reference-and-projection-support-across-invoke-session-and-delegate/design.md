Goal: implement one shared source/reference/projection resolution substrate across deterministic invoke args, inline-session contributions, delegated context, and related workflow data-flow consumers.

## Intent

Task `077` chose one shared data-reference family rather than separate mini-languages for each execution form. This task makes that decision real by defining and implementing the common resolution machinery used across:

- invoke-step `:args`
- session-step `:source` contributions
- session-step template vars
- delegate-step `:context`
- delegate-step templated `:prompt-string`
- judge args where invoke-style judges are used
- any adjacent runtime/compiler consumers that intentionally reuse the same workflow source semantics

Boundary note:

- this task owns selection and resolution of values from workflow/prior-step sources, including `:path` and `:projection`
- task `087` owns whether a referenced `:output` or `:yield` surface is valid for a given step form before resolution proceeds
- task `084` owns how already-resolved invoke execution artifacts are recorded and exposed through introspection after execution has happened

## Problem statement

The target workflow model now relies on shared refs such as:

- `:workflow-input`
- `:workflow-original`
- `{:step ... :output ...}`
- `{:step ... :yield ...}`
- optional `:path`
- optional richer `:projection`

Without one canonical shared implementation:

- each execution form may drift into its own resolution semantics
- projection behavior may differ across invoke/session/delegate usage sites
- bugs in downstream data flow will be harder to reason about and test
- compilers and runtime execution may duplicate logic or disagree subtly

## Scope

In scope:

- define the shared runtime/compiler resolution contract for workflow source refs and source specs
- implement canonical resolution for `:workflow-input`, `:workflow-original`, prior step outputs, and prior step yields after surface-validity checks have selected a valid source target
- implement first-cut `:path` and `:projection` handling with the documented exclusivity rule
- make invoke/session/delegate consumers use this shared substrate rather than ad hoc local resolution code
- add focused tests for representative valid/invalid source resolution across mixed workflow forms

Out of scope:

- inventing arbitrary transformation/scripting in projections
- broad redesign of the projection language beyond the first-cut documented semantics
- adding new source kinds beyond what task `077` defines unless a clear runtime necessity forces a documented change

## Desired outcome

Workflow data-flow resolution means the same thing everywhere it appears.

A source spec written for invoke args, session contributions, or delegated context should resolve through one shared set of rules and helpers.

## Acceptance

- one canonical resolution substrate exists for workflow source refs and source specs
- `:workflow-input`, `:workflow-original`, `{:step ... :output ...}`, and `{:step ... :yield ...}` resolve consistently across consumers
- first-cut `:path` and `:projection` handling is shared and enforces the documented exclusivity rule
- invoke/session/delegate execution paths use the shared substrate rather than divergent local implementations
- focused tests prove representative mixed-form resolution behavior and invalid-source handling
- implemented behavior aligns with task `077` and `doc/workflow-ir.md`
