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

2026-05-08

Implemented the first-cut deterministic-operation registration extraction.

What landed:
- created new lower component `components/deterministic-operation-registry/`
- added authoritative namespaces:
  - `psi.deterministic-operation-registry.defs`
  - `psi.deterministic-operation-registry.registry`
- wired the new component into root deps plus `components/agent-session/deps.edn`

Settled first-cut extracted ownership:
- `psi.deterministic-operation-registry.defs` now owns:
  - canonical operation id pattern and `valid-operation-id?`
  - canonical operation definition schema and validation
  - canonical stored-definition normalization via `normalize-operation-def`
- `psi.deterministic-operation-registry.registry` now owns:
  - `DeterministicOperationRegistry` object creation
  - register/get/list/count helpers
  - duplicate rejection
  - bulk unregister by `:ext-path`
  - thin lookup-plus-invoke seam `invoke-operation-in`

Important design decision on invoke ownership:
- kept canonical invoke execution in `psi.agent-session.deterministic-operations/invoke-operation`
- made extracted `registry/invoke-operation-in` accept the invoke fn as an argument
- this preserves the lower registry as the authoritative lookup owner without forcing invoke-result semantics into the extracted component
- higher workflow runtime now calls the extracted lookup seam directly and passes the canonical invoke function explicitly

Production rewiring that landed:
- `agent-session.context` now creates the runtime deterministic-operation registry via the extracted component directly
- `agent-session.workflow-statechart-runtime` now uses the extracted registry lookup seam directly for workflow `:invoke`
- `agent-session.extensions.runtime-fns` now registers runtime-owned deterministic operations through the extracted registry directly
- `agent-session.extensions` now delegates deterministic-operation cleanup to the extracted registry directly and uses extracted definition normalization

Compatibility decision:
- retained `components/agent-session/src/psi/agent_session/deterministic_operation_registry.clj` as a thin compatibility wrapper delegating to the extracted component
- this minimized churn in existing tests and higher-level callers while making the lower owner explicit
- wrapper keeps the old 3-arity `invoke-operation-in` by supplying `psi.agent-session.deterministic-operations/invoke-operation`

Behavior preserved and now explicitly proven:
- canonical id field remains `:id`
- canonical id validation remains namespaced kebab-case string matching the pre-existing pattern
- stored deterministic-operation shape remains closed and unchanged:
  - required `:id`, `:handler`
  - optional `:description`, `:summary`, `:ext-path`, `:source`
  - `:source` remains limited to `:extension` or `:runtime`
- normalization still trims `:description` and `:summary`
- invalid registration still throws
- duplicate registration still throws rather than replaces
- `get-operation-in` still returns `nil` on miss
- missing `invoke-operation-in` lookup still throws structured ex-info
- bulk unregister by `:ext-path` remains nil-tolerant
- registration order remains authoritative for `operation-ids-in` and `all-operations-in`
- registry mutation helpers still return the registry object

Focused verification run after extraction:
- new lower-component tests green:
  - `psi.deterministic-operation-registry.registry-test`
  - `7 tests, 14 assertions, 0 failures`
- higher-level compatibility and behavior proofs green:
  - `psi.agent-session.deterministic-operation-registry-test` → `7 tests, 14 assertions, 0 failures`
  - `psi.agent-session.workflow-invoke-runtime-test` → `3 tests, 15 assertions, 0 failures`
  - `psi.agent-session.extensions-test` → `23 tests, 117 assertions, 0 failures`

Non-obvious tradeoff recorded:
- the extracted lower registry namespace intentionally does not depend on `agent-session` invoke semantics
- instead of moving invoke execution down or keeping lookup up, the chosen seam is dependency injection of the invoke fn into `registry/invoke-operation-in`
- this keeps the registry lower and reusable while preserving current invoke behavior exactly
