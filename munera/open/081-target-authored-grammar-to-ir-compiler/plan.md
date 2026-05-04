Approach:
- dependency note: this slice should build on the shared output/reference semantics stabilized in tasks `087` and `088`, so target-grammar compilation does not implicitly redefine them
- implement this as the canonical forward compiler for the converged authoring surface, not as a parallel execution path
- use `doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`, and `doc/workflow-ir.md` together as the authoritative mapping contract
- keep the compiler thin where possible: the target grammar is already designed to be close to IR semantics, so normalization should mostly group hoisted authored fields into explicit IR payloads and apply defaults
- preserve one-way clarity: target-authored workflows should normalize predictably without compatibility guesswork
- anchor the compiler seam at runtime effective-definition normalization in `workflow-runtime/create-run`, with later loader/file convergence lowering into the same in-memory target-authored form
- add both golden compiler tests and selected cross-grammar equivalence tests
- define cross-grammar equivalence as canonical IR equality after recursive removal of migration-only `:compat` metadata

Likely steps:
1. identify and implement the target-authored compiler seam at effective-definition normalization before execution
2. compile the target in-memory workflow definition shape `{:steps [...]}` into ordered IR steps
3. normalize authored `:type :invoke` hoisted fields into IR `:invoke` payloads
4. normalize authored `:type :session` hoisted fields into IR `:session` payloads
5. normalize authored `:type :delegate` hoisted fields into IR `:delegate` payloads
6. compile shared refs/projections and contribution forms into IR source-spec and contribution shapes
7. compile authored `:yields`, `:judge`, `:on`, and `:max-iterations` into IR control/result forms
8. add golden tests for representative invoke/session/delegate workflows
9. add selected equivalence tests against current-authored workflows where the semantics overlap, using semantic comparison that strips `:compat`
10. tighten docs or compiler behavior if any drift is found between target grammar docs and executable normalization

Proof target:
- representative target-authored workflows normalize into stable IR and can therefore run through the canonical IR execution path

Risks:
- subtle authored-field validity or defaulting rules may be underspecified until the compiler makes them concrete
- equivalence with current-authored workflows may reveal semantic gaps that need careful documentation rather than ad hoc coercion
- over-broad flexibility in the compiler could reintroduce ambiguity that the target grammar was meant to remove
- if tasks `087` and `088` land additional source/output semantics first, this slice should adopt them rather than freezing an older interpretation
