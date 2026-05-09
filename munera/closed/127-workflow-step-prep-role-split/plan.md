Approach:
- treat this as a role-clarifying split inside the extracted workflow runtime component, not a semantics redesign
- preserve behavior exactly while separating workflow materialization from session-config shaping
- update runtime/context/tests to depend on the clearer split owners
- prefer direct use of the split owners over retaining `psi.workflow-runtime.step-prep` as a façade unless the façade is explicitly justified
- avoid moving code back upward unless implementation proves the current lower placement is wrong
- preserve behavior and externally consumed contracts exactly, while allowing internal helper extraction/renaming/require reshaping/local cleanup needed to make the split clearer

Planned outcomes:
1. classify current `step-prep` behavior into materialization versus session-config shaping
2. create the expected split owners `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.step-session-config`, unless a justified naming variation is recorded
3. rewire runtime/context/tool/test consumers to the new owners
4. remove `psi.workflow-runtime.step-prep` as the mixed owner if direct use of the split owners is the cleaner shape, or leave only a tiny explicit façade if justified
5. record the final ownership rationale in `implementation.md`

Scope boundaries:
- no workflow behavior redesign
- no inheritance-policy redesign
- no new adapter seam in this slice
- no consolidation or formalization of the existing workflow-runtime ↔ session-owned callback/read seam beyond rewiring consumers to the split owners
- no public workflow API changes
- no broad restructuring beyond the `step-prep` role split
