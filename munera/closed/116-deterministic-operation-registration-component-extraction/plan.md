Approach:
- treat this as a registry-boundary extraction, not a workflow-runtime redesign
- preserve current deterministic-operation registration and workflow `:invoke` behavior first
- separate canonical operation registration/query ownership from extension lifecycle orchestration and workflow invoke-time execution
- keep the first cut focused on deterministic-operation definitions, registration/removal, and registered-operation lookup/listing

Planned outcomes:
1. create a lower `deterministic-operation-registry` component
2. make canonical deterministic-operation registration/removal/query ownership explicit below `agent-session`
3. delegate extension registration/removal entrypoints downward into the extracted component
4. delegate workflow invoke-time registered-operation lookup downward into the extracted component
5. preserve current invoke behavior and extension unregister cleanup behavior

Scope boundaries:
- no workflow-definition registration extraction in this task
- no workflow authoring/runtime redesign in this task
- no invoke-step grammar or source-resolution redesign in this task
- no broader extension-runtime redesign in this task
- no generic registry abstraction unless implementation proves it is already present and obviously reusable

Key design checks before implementation:
- confirm the canonical operation id field and current validation rules
- confirm the current lower registry-object API shape for register/query/invoke helpers
- confirm current duplicate-registration rejection behavior
- confirm `get-operation-in` lookup-miss behavior and `invoke-operation-in` missing-operation behavior
- confirm registration-order behavior for public id/list queries
- confirm extension unregister-all cleanup semantics
- confirm whether invoke-time execution helpers should stay above the boundary except for registry lookup

Follow-on guidance:
- keep `115-workflow-registration-component-extraction` separate; it covers workflow definitions, not deterministic operations
- if extraction reveals a second lower seam around shared invoke-result wrapping or operation-definition normalization, document it but do not widen scope unless necessary
- `105-agent-session-component-extraction-map` already records this distinct workflow-adjacent invoke-operation registry seam explicitly; keep it synchronized if the extraction boundary shifts
