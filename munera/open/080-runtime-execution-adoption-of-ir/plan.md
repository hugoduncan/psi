Approach:
- treat this as the architectural pivot where IR becomes real runtime substrate rather than documentation
- preserve current workflow behavior by placing current-grammar compilation before execution, not by keeping dual execution semantics
- change execution in the smallest slices that still leave one clear runtime model behind
- use existing workflow lifecycle, execution, and routing tests as the main regression proof surfaces, adding focused IR-native tests where they improve clarity

Likely steps:
1. identify the canonical runtime seam where authored definitions become executable workflow definitions
2. thread normalized IR through that seam without broadening external API churn more than necessary
3. adapt step lookup/progression/routing to IR step names and execution payloads
4. adapt judge execution to typed IR judge forms
5. adapt session-style step execution to IR `:session` payloads while preserving behavior
6. ensure attempt/result/history recording still exposes coherent data after the pivot
7. add focused IR-execution tests and keep representative existing workflow tests green
8. remove or isolate any execution-time dependence on current authored field names discovered during the migration

Proof target:
- runtime executes normalized IR workflows and existing current-authored workflows still pass once compiled to IR first

Risks:
- hidden authored-shape assumptions may exist deep in execution or observability code
- execution and introspection may drift if only one path is updated
- over-broad refactoring could destabilize the still-green deterministic workflow runtime work
