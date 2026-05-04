Approach:
- implement this as a compatibility compiler, not as a second runtime execution path
- use `doc/workflow-grammar-current.md`, `doc/workflow-grammar-migration.md`, and `doc/workflow-ir.md` together as the authoritative mapping contract
- preserve current workflow semantics first; do not opportunistically redesign authored behavior in this slice
- keep compatibility details at compile time and surface them through explicit IR structure or minimal `:compat` metadata where truly needed
- prefer golden tests with representative authored inputs and exact expected IR outputs

Likely steps:
1. identify the existing workflow loader/compiler seam where current authored definitions can normalize before execution
2. compile current step definitions into ordered IR steps
3. compile current `:executor` into IR `:session`
4. compile `:prompt-template` + `:input-bindings` into template contributions
5. compile `:session-preload` into ordered source-style contributions or explicit compatibility-carried contribution items where needed
6. compile `:session-overrides` into the IR session payload
7. compile current judge/routing shapes into typed IR judge and control-flow forms
8. add golden tests for representative authored workflows and edge cases
9. document or tighten any discovered semantic gaps between current grammar docs and live behavior

Proof target:
- representative current authored workflows normalize into one stable IR shape suitable for later IR runtime execution

Risks:
- hidden current behavior may not fit the documented grammar as cleanly as expected
- preload and binding semantics may require carefully limited compatibility metadata
- over-normalizing too early could accidentally break current execution intent

Additional settled approach for accepted-result-envelope compatibility:
- treat current `:step-output` refs as targeting the accepted-result envelope first, with canonical IR translation when the path clearly names a normalized output/yield surface
- preserve non-canonical envelope reads such as whole-envelope, `:diagnostics`, and `:blocked` through explicit, minimal `:compat` metadata on the compiled source/contribution rather than by widening canonical IR output semantics
- add golden tests that prove both canonical translation (`:outputs` paths) and compatibility-preserving translation (`:diagnostics`, whole envelope) so later runtime adoption has an explicit contract
- update `doc/workflow-ir.md` to state that compatibility metadata may preserve accepted-result-envelope reads from the current grammar during migration, while target-authored refs remain limited to canonical `:output` and `:yield` surfaces

Additional settled approach for current `:result-schema` compatibility:
- preserve current required `:result-schema` only as minimal step-level `:compat` metadata in compiled IR
- do not add a canonical IR `:result-schema` field in this slice, and do not derive arbitrary IR outputs mechanically from arbitrary current Malli schemas
- keep canonical IR outputs/yields driven by execution-form defaults plus explicit compatibility mapping rules rather than by current runtime validation schemas
- add golden tests proving representative compiled session steps retain current `:result-schema` breadcrumbs under `:compat`
- reconcile `doc/workflow-grammar-current.md`, `doc/workflow-ir.md`, and `doc/workflow-grammar-migration.md` so the drop-from-canonical / preserve-in-compat rule is explicit before runtime adoption
