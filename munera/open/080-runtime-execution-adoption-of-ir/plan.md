Approach:
- treat this as the architectural pivot where IR becomes real runtime substrate rather than documentation
- preserve current workflow behavior by placing current-grammar compilation before execution, not by keeping dual execution semantics
- change execution in the smallest slices that still leave one clear runtime model behind
- use existing workflow lifecycle, execution, and routing tests as the main regression proof surfaces, adding focused IR-native tests where they improve clarity

Likely steps:
1. identify the canonical runtime seam where authored definitions become executable workflow definitions
2. thread normalized IR through that seam without broadening external API churn more than necessary
3. make execution-entry validation explicit: current-authored definitions may compile through compatibility, but runtime execution accepts only canonical execution-valid IR and rejects compiled `:workflow-runtime` refs until a later IR surface exists for them
4. adapt step lookup/progression/routing to IR step names and execution payloads
5. adapt judge execution to typed IR judge forms
6. adapt session-style step execution to IR `:session` payloads while preserving behavior
7. keep run/step-run/attempt/history observer surfaces shape-stable for this slice while moving their internal recording logic onto IR-owned execution concepts
8. add focused IR-execution and observability regression tests and keep representative existing workflow tests green
9. remove or isolate any execution-time dependence on current authored field names discovered during the migration

Proof target:
- runtime executes normalized IR workflows directly
- existing current-authored workflows still pass once compiled to IR first, provided the compiled IR is execution-valid
- compiled current-authored workflows that still carry `:workflow-runtime` refs are rejected explicitly at execution entry
- execution and observer-facing run/attempt/history surfaces stay regression-locked together across the pivot

Risks:
- hidden authored-shape assumptions may exist deep in execution or observability code
- execution and introspection may drift if only one path is updated
- over-broad refactoring could destabilize the still-green deterministic workflow runtime work
