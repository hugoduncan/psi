# 077 task family dependency map

This document gives a compact dependency map for the `077-deterministic-workflow-steps` task family.

It is intended as a navigation aid across the child tasks created to move from the current authored workflow grammar to the target converged grammar through a normalized workflow IR.

## Phase 0 — umbrella

- **`077` deterministic-workflow-steps**
  - umbrella design
  - defines target grammar, IR-first migration, execution forms, refs, outputs, yields

## Phase 1 — runtime migration backbone

These tasks establish the canonical runtime execution substrate.

- **`078` workflow-ir-schema-and-validation**
  - defines code-level IR boundary
  - foundation for all later tasks

- **`079` current-authored-grammar-to-ir-compiler**
  - depends on: `078`
  - preserves existing workflows by compiling current grammar to IR

- **`080` runtime-execution-adoption-of-ir**
  - depends on: `078`, `079`
  - makes runtime execute IR rather than current authored workflow maps

## Phase 2 — shared semantics

These tasks stabilize cross-cutting semantics before target grammar and mixed-form execution deepen.

- **`087` step-output-surface-normalization-and-validation**
  - depends on: `078`, practically `080`
  - defines which `:output` keys exist per step type
  - validates `{:step ... :output ...}` refs
  - keeps `:yield` distinct from `:output`

- **`088` shared-source-reference-and-projection-support**
  - depends on: `078`, practically `080`
  - defines shared ref resolution for:
    - `:workflow-input`
    - `:workflow-original`
    - `{:step ... :output ...}`
    - `{:step ... :yield ...}`
  - owns `:path` / `:projection` behavior

## Phase 3 — target authoring

This makes the new grammar executable.

- **`081` target-authored-grammar-to-ir-compiler**
  - depends on: `078`, `080`, `087`, `088`
  - compiles target `:invoke | :session | :delegate` grammar into IR

## Phase 4 — invoke execution form

These tasks make deterministic steps real.

- **`082` deterministic-operation-registry-and-extension-contract**
  - depends on: `078`, `080`
  - defines registry and operation execution contract

- **`083` runtime-execution-support-for-deterministic-invoke-steps**
  - depends on: `082`, `087`, `088`, practically `080`
  - executes IR `:type :invoke` steps

- **`084` deterministic-result-recording-and-introspection-surfaces**
  - depends on: `083`, `087`
  - records invoke execution artifacts
  - exposes them through introspection/query surfaces

## Phase 5 — session and delegate execution forms

These tasks make the other two target forms real on the IR substrate.

- **`085` inline-session-contribution-compilation-into-child-session-conversations**
  - depends on: `088`, practically `080`
  - compiles IR session contributions into canonical child-session conversation state

- **`086` delegated-boundary-model-and-workflow-invocation-plumbing**
  - depends on: `088`, practically `080`
  - executes IR `:type :delegate` steps
  - defines delegated `:workflow-input` / `:workflow-original` semantics

## Phase 6 — examples and migration proof

This proves the authoring model in practice.

- **`089` example-workflow-migration-and-documentation**
  - depends on: `081`, `083`, `085`, `086`, plus shared semantics from `087` and `088`
  - migrates representative workflows
  - updates docs to teach target grammar through examples

## Phase 7 — retirement

This removes the old authored surface once migration is complete.

- **`090` eventual-compatibility-retirement-for-current-authored-workflow-grammar**
  - depends on: `089`, and effectively the whole chain
  - removes current-grammar compatibility loading/compiler support

## Short dependency graph

```text
077
 ├─ 078
 │   ├─ 079
 │   │   └─ 080
 │   │       ├─ 087
 │   │       ├─ 088
 │   │       │   ├─ 085
 │   │       │   └─ 086
 │   │       ├─ 081  (also depends on 087, 088)
 │   │       ├─ 082
 │   │       │   └─ 083  (also depends on 087, 088)
 │   │       │       └─ 084  (also depends on 087)
 │   │       └─ 089  (depends on 081, 083, 085, 086, 087, 088)
 │   └─ 090  (after 089 and remaining blockers are gone)
```

## Ownership summary

### Runtime model
- `078` — what IR is
- `080` — runtime executes IR

### Compatibility
- `079` — current grammar -> IR
- `090` — remove current grammar

### Target authoring
- `081` — target grammar -> IR
- `089` — examples/docs using target grammar

### Shared semantics
- `087` — what outputs are valid
- `088` — how refs/projections resolve values

### Execution forms
- `082` / `083` / `084` — invoke
- `085` — session
- `086` — delegate

## Recommended practical order

1. `077`
2. `078`
3. `079`
4. `080`
5. `087`
6. `088`
7. `081`
8. `082`
9. `083`
10. `085`
11. `086`
12. `084`
13. `089`
14. `090`
