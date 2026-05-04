2026-05-04 review: actionable ambiguities remain — task lacks the referenced `implementation.md` and `design-steps.md` artifacts; design/plan do not yet choose the authoritative target-authored compile seam alongside the current-only `workflow_runtime.clj` path; and cross-grammar equivalence acceptance does not define whether IR comparison ignores step ids / compat metadata or requires byte-identical IR.

2026-05-04 design-pass follow-up: completed the newly added ambiguity steps.
- compile seam decided: target-authored compilation should happen at `workflow-runtime/create-run` effective-definition normalization, parallel to the existing current-authored compiler, so runtime execution still consumes only canonical IR
- authored input surface decided: this slice compiles an in-memory target-authored workflow map of the form `{:steps [...]}`; direct workflow-file parsing/loader convergence is explicitly deferred
- equivalence contract decided: cross-grammar tests compare canonical IR after recursive `:compat` stripping; canonical fields must otherwise match exactly, and tests should not depend on current-only generated step ids
- updated `design.md` and `plan.md` to record these decisions so later implementation work has an unambiguous compile boundary and proof contract

2026-05-04 review: actionable inconsistency remains — `steps.md` still lists the compile seam decision as open (`Identify the loader/compiler seam for target-authored workflow normalization`) even though `design.md`, `plan.md`, `design-steps.md`, and this implementation log already record that seam as decided at `workflow-runtime/create-run` effective-definition normalization. The task files should align so unresolved design work in `steps.md` does not contradict recorded design decisions.

2026-05-04 execution: reconciled `steps.md` with the recorded design decisions by marking the compile-seam item done and annotating it with the authoritative `workflow-runtime/create-run` effective-definition normalization seam. No further newly added design follow-up items remained open after this alignment pass.

2026-05-04 implementation: landed the target-authored compiler and runtime seam integration.
- added `workflow_target_ir_compiler.clj` as the forward compiler from `{:steps [...]}` target-authored workflow data into canonical workflow IR
- compiler now normalizes `:invoke`, `:session`, and `:delegate` step forms, shared source-specs, `:source`/`:template` contributions, default yields, judge forms, and routing/loop-bound fields
- runtime `create-run` effective-definition normalization now detects target-authored definitions and compiles them through the new compiler while current-authored definitions continue through `workflow_current_ir_compiler`
- target-authored effective definitions are padded with minimal compatibility step-order/step-map structure so existing workflow-run schema/statechart helpers remain valid while runtime execution continues to consume `:canonical-ir`
- added `workflow_target_ir_compiler_test.clj` with golden target-authored compile tests, semantic IR equivalence tests against current-authored workflows after recursive `:compat` stripping, and a create-run seam proof
- focused verification green via `bb clojure:test:unit --focus psi.agent-session.workflow-target-ir-compiler-test --focus psi.agent-session.workflow-runtime-test`

2026-05-04 review: actionable runtime contract drift remains — target-authored inline `create-run` definitions are normalized with a generated `:definition-id` in `:effective-definition`, unlike current inline definitions whose snapshot remains source-id-free while provenance is carried only by `:source-definition-id nil`. Align the target-authored inline snapshot contract with the existing inline-definition behavior and add a regression test.
