2026-05-04 review: actionable ambiguities remain — task lacks the referenced `implementation.md` and `design-steps.md` artifacts; design/plan do not yet choose the authoritative target-authored compile seam alongside the current-only `workflow_runtime.clj` path; and cross-grammar equivalence acceptance does not define whether IR comparison ignores step ids / compat metadata or requires byte-identical IR.

2026-05-04 design-pass follow-up: completed the newly added ambiguity steps.
- compile seam decided: target-authored compilation should happen at `workflow-runtime/create-run` effective-definition normalization, parallel to the existing current-authored compiler, so runtime execution still consumes only canonical IR
- authored input surface decided: this slice compiles an in-memory target-authored workflow map of the form `{:steps [...]}`; direct workflow-file parsing/loader convergence is explicitly deferred
- equivalence contract decided: cross-grammar tests compare canonical IR after recursive `:compat` stripping; canonical fields must otherwise match exactly, and tests should not depend on current-only generated step ids
- updated `design.md` and `plan.md` to record these decisions so later implementation work has an unambiguous compile boundary and proof contract
