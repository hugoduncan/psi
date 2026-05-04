Goal: compile the target converged workflow grammar into normalized workflow IR so the new `:type :invoke | :session | :delegate` authoring surface becomes executable on the canonical runtime model.

## Intent

Task `077` defined the target workflow grammar and the IR-first migration architecture. After IR schema definition (`078`), current-grammar compatibility compilation (`079`), and runtime IR execution adoption (`080`), the next enabling slice is to make the target authored grammar executable by compiling it into normalized IR.

## Problem statement

The project now has a documented target grammar in:

- `doc/workflow-grammar.md`
- `doc/workflow-grammar-concepts.md`

But that grammar remains only a design/documentation surface until there is an implemented compiler from target-authored workflow data into `doc/workflow-ir.md`.

Without this slice:

- the target grammar remains aspirational
- new invoke/session/delegate authored workflows cannot run through the canonical runtime path
- migration stays one-sided, supporting only current-authored workflows via compatibility compilation
- equivalence between current-authored and target-authored workflows cannot be proven structurally in code

## Scope

In scope:

- implement compilation from the target authored workflow grammar to normalized workflow IR
- compile authored `:type :invoke`, `:type :session`, and `:type :delegate` step forms into their IR counterparts
- compile target authored refs/projections, contributions, outputs, yields, judges, and control flow into IR
- normalize authored hoisted execution fields into grouped IR execution payloads (`:invoke`, `:session`, `:delegate`)
- add golden tests for representative target-authored workflow -> IR compilation
- add equivalence-oriented tests where current-authored and target-authored forms normalize to the same semantic IR when appropriate

Out of scope:

- broad migration of built-in workflows to the target grammar
- retiring current authored grammar support
- implementing deterministic operation handlers beyond what IR execution already expects
- redesigning the target grammar again during implementation except where a concrete mismatch forces a documented correction

## Desired outcome

A workflow author can write a workflow in the target converged grammar and have it compile into the same canonical IR used by runtime execution.

## Acceptance

- a target-authored grammar -> IR compiler exists
- representative `:invoke`, `:session`, and `:delegate` authored workflows compile into expected normalized IR
- authored hoisted execution fields normalize into IR execution payloads cleanly
- refs/projections/contributions/yields/judges/control flow compile consistently with `doc/workflow-grammar.md` and `doc/workflow-ir.md`
- at least some semantically equivalent current-authored and target-authored workflows are proven to normalize compatibly where the surfaces overlap
- the target grammar becomes executable rather than documentation-only
