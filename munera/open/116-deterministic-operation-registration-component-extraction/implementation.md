2026-05-07

Task created to extract deterministic-operation registration into a separate lower component.

Creation rationale:
- workflow-related work now clearly contains two distinct registry seams: workflow-definition registration and deterministic-operation registration for workflow `:invoke`
- task `115` is about workflow-definition registration and should remain separate
- current deterministic-operation ownership appears mixed across `deterministic_operations.clj`, `deterministic_operation_registry.clj`, extension registration helpers in `extensions.clj`, and workflow invoke runtime consumers
- the right first move is to make the lower deterministic-operation registry owner explicit without widening into broader workflow runtime redesign

Initial design intent captured in `design.md`:
- create `components/deterministic-operation-registry/` as the lower owner of deterministic-operation registration/removal/query semantics
- preserve current operation-id validation and deterministic-operation definition validation behavior first
- keep workflow invoke sequencing/runtime orchestration above the boundary while delegating registered-operation lookup downward
- keep extension lifecycle orchestration above the boundary while delegating operation registration/removal downward

Key separation recorded at task creation:
- `115-workflow-registration-component-extraction` covers workflow-definition registration only
- `116-deterministic-operation-registration-component-extraction` covers the distinct workflow-adjacent invoke-operation registry seam
- `077-deterministic-workflow-steps` remains the broader workflow authoring/runtime umbrella and is not superseded by this extraction

Open design questions to resolve during implementation:
- what exact canonical field names and stored shape define a registered deterministic operation today
- whether lower helpers already have a tuple-style API that should be preserved
- whether invoke-result wrapping helpers should remain above the boundary or whether a tiny shared lower helper must move with the registry extraction
- what listing/order semantics are already part of the live public behavior, if any
