Approach:
- treat output surfaces as a first-class runtime contract rather than an incidental byproduct of execution implementation details
- normalize step outputs close to execution/result recording so refs and introspection can share one logical model
- validate early where possible, but keep runtime safeguards as well for mixed or compatibility-driven paths
- preserve the distinction between `:output` and `:yield` all the way through schema, compiler, and runtime checks

Likely steps:
1. inspect current output/result/yield surfaces across session-style workflows and new invoke/delegate work
2. define canonical output key sets per step type
3. choose the canonical runtime representation for normalized step outputs
4. validate output refs against referenced step type and exposed key set
5. keep yield-ref validation on a separate path tied to yielded-value semantics
6. add focused tests for representative valid and invalid references across mixed-form workflows
7. reconcile any discovered drift among docs, schema, compilers, and runtime execution

Clarified design decision:
- first-cut delegate steps do not re-export callee step-local outputs through downstream `{:step ... :output ...}` refs
- delegate steps remain yielded-value-first and only gain `:output` surfaces when the delegate step itself explicitly declares local outputs in a later slice

Proof target:
- mixed-form workflows can rely on one stable, validated `:output` reference model without confusing step-local outputs with yielded values

Risks:
- output normalization may duplicate or fight existing result-envelope storage unless shaped carefully
- delegate outputs may be tempting to over-generalize beyond first-cut needs
- compatibility paths may hide output-surface drift unless tests cover mixed-form scenarios explicitly
