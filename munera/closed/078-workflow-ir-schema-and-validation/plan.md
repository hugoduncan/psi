Approach:
- implement this slice as the first runtime-owned landing zone for the IR-first migration architecture defined in task `077`
- place the canonical IR validation surface alongside existing workflow runtime model code in `components/agent-session/src/psi/agent_session/`, but keep it separate from the legacy `workflow_model.clj` definition/run schemas so the normalized IR boundary is explicit
- start from `doc/workflow-ir.md` as the authoritative design surface and encode only the execution-relevant first-cut semantics
- prefer a small, explicit schema surface over premature generalization
- keep authored-grammar compatibility concerns out of the main schema except for one clearly optional `:compat` area
- add tests that exercise both positive examples and structural rejection for invalid forms
- add a narrow semantic-validation layer for invariants that are intrinsic to normalized IR consistency: prior-step-only step refs, `:on` requiring `:judge`, and yield/output cross-reference correctness
- defer broader semantic checks (for example delegated target existence and operation-specific argument contracts) to later compiler/runtime slices

Likely steps:
1. inspect current workflow model/schema namespaces and choose the right home for IR validation
2. define top-level IR schema plus tagged step-type schemas
3. define shared source-ref, source-spec, contribution, output, yield, judge, and routing schemas
4. define minimal optional `:compat` allowance without letting it swallow canonical validation
5. add focused schema tests for representative invoke/session/delegate IR examples
6. add focused invalid-shape tests for mixed execution forms, invalid refs, and malformed yields/judges
7. re-read `doc/workflow-ir.md` and tighten wording or schema if drift is discovered

Proof target:
- one implemented schema boundary can validate representative normalized IR values and reject invalid ones in a way that matches the documented IR design

Risks:
- making the first schema too loose and losing the benefit of normalization
- making it too ambitious and blocking later compiler work on unnecessary detail
- accidentally encoding current authored grammar assumptions into the IR
