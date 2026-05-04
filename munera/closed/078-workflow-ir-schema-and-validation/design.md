Goal: define and implement the first canonical validation/schema surface for normalized workflow IR so runtime code has one execution-facing model independent of authored workflow grammar.

## Intent

Task `077-deterministic-workflow-steps` settled the migration direction:

- current authored grammar -> normalized workflow IR <- target authored grammar
- runtime should execute IR rather than either authored grammar directly

This task creates the first concrete implementation slice for that plan by defining the runtime-facing IR schema and validation boundary.

## Problem statement

The project now has:

- `doc/workflow-grammar-current.md`
- `doc/workflow-grammar.md`
- `doc/workflow-grammar-concepts.md`
- `doc/workflow-grammar-migration.md`
- `doc/workflow-ir.md`

But the runtime does not yet have one implemented canonical validation surface for normalized workflow IR.

Without that surface:

- later compilers have no authoritative target
- execution code cannot converge on one runtime model
- compatibility behavior risks leaking across implementation layers
- deterministic/session/delegate step handling remains partly conceptual instead of executable

## Scope

In scope:

- define the first concrete IR schema/validation shape in code
- encode the core tagged unions for IR step types and yields
- encode shared source-ref/source-spec validation
- encode judge-form validation for `:type :llm` and `:type :invoke`
- encode control-flow validation at the IR layer
- encode temporary compatibility metadata as explicitly optional and non-canonical
- add focused tests proving representative valid/invalid IR forms
- keep the implemented schema aligned with `doc/workflow-ir.md`
- decide and document the validation boundary for this slice
- include a minimal runtime-owned semantic validation layer for invariants that are intrinsic to normalized IR structure rather than later execution or compiler resolution
- treat missing default `:yields` as a compiler-normalization failure rather than a validator fill-in responsibility
- require local `:yields` output-key references to correspond to declared step-local `:outputs`
- require normalized IR to contain at least one step
- require `:judge` and `:on` to appear together at the normalized IR boundary

Out of scope:

- compiling authored grammars into IR
- executing IR in runtime
- implementing deterministic operation registry behavior
- migrating built-in workflows
- broader graph/link resolution beyond the minimal semantic invariants owned by this slice
- operation-specific argument semantics
- delegated target existence checks

## Desired outcome

A developer can point to one runtime-facing schema surface and say:

- this is what a normalized workflow IR looks like
- this is what current-grammar and target-grammar compilers must emit
- this is what runtime execution will consume

## Acceptance

- a code-level workflow IR schema/validation surface exists
- it covers `:type :invoke`, `:type :session`, and `:type :delegate`
- it covers shared refs/projections, outputs, yields, judges, and control flow
- it allows explicitly optional compatibility metadata without making it part of normal authored semantics
- focused tests prove valid/invalid representative IR examples
- the implemented schema is consistent with `doc/workflow-ir.md`
- this slice's validation boundary is explicit in task artifacts and tests
- if this slice owns any non-structural invariants, those invariants are named explicitly and proved with focused tests
