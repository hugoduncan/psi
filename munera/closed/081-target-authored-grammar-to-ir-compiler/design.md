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
- compile target-authored refs/projections, contributions, outputs, yields, judges, and control flow into IR
- normalize authored hoisted execution fields into grouped IR execution payloads (`:invoke`, `:session`, `:delegate`)
- add golden tests for representative target-authored workflow -> IR compilation
- add equivalence-oriented tests where current-authored and target-authored forms normalize to the same semantic IR when appropriate

Out of scope:

- broad migration of built-in workflows to the target grammar
- retiring current authored grammar support
- implementing deterministic operation handlers beyond what IR execution already expects
- redesigning the target grammar again during implementation except where a concrete mismatch forces a documented correction
- changing workflow-file markdown parsing/loader semantics beyond the minimal seam documentation needed for this compiler slice

## Authoritative decisions

### Compile seam

The authoritative seam for this slice is `workflow-runtime/create-run` effective-definition normalization.

Specifically:

- runtime execution continues to consume only canonical workflow IR
- `create-run` remains the boundary that accepts authored workflow definitions and snapshots their execution-ready form
- current-authored inline or registered definitions continue to compile through `workflow-current-ir-compiler`
- target-authored inline or registered definitions should compile through a sibling target-authored compiler before the run snapshot is created
- loader/file-level lowering into target-authored in-memory data may be added later, but it is not the required seam for this slice

This keeps authored-surface compilation above execution, avoids a parallel execution path, and matches `doc/workflow-grammar-migration.md`'s two-compiler-to-one-IR architecture.

### Authored input surface for this slice

This slice compiles an exact in-memory target-authored workflow shape, not markdown files directly.

The input surface is:

```clojure
{:steps [target-step+]}
```

where each `target-step` follows the target forms documented in `doc/workflow-grammar.md`:

- `{:name string :type :invoke :operation string :args {...} ...}`
- `{:name string :type :session :contributions [...] ...}`
- `{:name string :type :delegate :target string :prompt-string ... ...}`

and shared authored fields may include `:outputs`, `:yields`, `:judge`, `:on`, and `:max-iterations`.

For this task:

- the compiler input comes from tests and direct runtime/registration callers that provide target-authored data in memory
- workflow-file parsing and workflow-file loader convergence onto this grammar are explicitly deferred
- any future file loader path should lower into this same in-memory target-authored shape before invoking the target-authored compiler

### Cross-grammar equivalence contract

Cross-grammar equivalence tests are semantic IR comparisons, not byte-identical raw-map comparisons.

The comparator contract for this slice is:

- canonical IR fields must match exactly after compilation
- step identity is compared after normalizing to step order plus authored `:name`; no test should depend on current-grammar generated ids that do not exist in the target surface
- target-authored IR is expected to contain no `:compat` metadata
- current-authored IR may contain narrow `:compat` metadata during migration
- equivalence assertions therefore compare current IR and target IR after recursively removing `:compat` keys

This keeps the tests aligned with `doc/workflow-ir.md`: compatibility metadata may exist temporarily in IR, but it is not part of the target authored grammar's semantics.

## Desired outcome

A workflow author can write a workflow in the target converged grammar and have it compile into the same canonical IR used by runtime execution.

## Acceptance

- a target-authored grammar -> IR compiler exists
- representative `:invoke`, `:session`, and `:delegate` authored workflows compile into expected normalized IR
- authored hoisted execution fields normalize into IR execution payloads cleanly
- refs/projections/contributions/yields/judges/control flow compile consistently with `doc/workflow-grammar.md` and `doc/workflow-ir.md`
- at least some semantically equivalent current-authored and target-authored workflows are proven to normalize compatibly where the surfaces overlap using semantic comparison that ignores migration-only `:compat` metadata
- the target grammar becomes executable rather than documentation-only
