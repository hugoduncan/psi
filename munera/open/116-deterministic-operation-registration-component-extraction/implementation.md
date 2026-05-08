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

Final cleanup that also landed:
- removed the temporary compatibility namespace `components/agent-session/src/psi/agent_session/deterministic_operation_registry.clj`
- rewired remaining tests to target `psi.deterministic-operation-registry.registry` directly
- updated direct test calls and stubs to the extracted lower 4-arity seam by supplying or accepting the explicit invoke fn argument

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
- higher-level compatibility and behavior proofs green after extraction:
  - `psi.agent-session.deterministic-operation-registry-test` → `7 tests, 14 assertions, 0 failures`
  - `psi.agent-session.workflow-invoke-runtime-test` → `3 tests, 15 assertions, 0 failures`
  - `psi.agent-session.extensions-test` → `23 tests, 117 assertions, 0 failures`
- final focused verification after compat-wrapper removal green:
  - `psi.agent-session.deterministic-operation-registry-test`
  - `psi.agent-session.workflow-invoke-runtime-test`
  - `psi.agent-session.extensions-test`
  - `psi.agent-session.workflow-execution-test`
  - combined result: `47 tests, 213 assertions, 0 failures`

Non-obvious tradeoff recorded:
- the extracted lower registry namespace intentionally does not depend on `agent-session` invoke semantics
- instead of moving invoke execution down or keeping lookup up, the chosen seam is dependency injection of the invoke fn into `registry/invoke-operation-in`
- this keeps the registry lower and reusable while preserving current invoke behavior exactly

2026-05-08 — code-shaper review

Review outcome:
- overall extraction shape is good: lower ownership is clearer, production seams are simpler, and the compat wrapper removal improved honesty of the boundary
- no correctness concerns were found in the landed implementation

Actionable follow-up findings:
- remaining contract duplication exists between `psi.deterministic-operation-registry.defs` and `psi.agent-session.deterministic-operations`
  - `defs.clj` already owns `operation-result-schema`, `valid-operation-result?`, and `explain-operation-result`
  - `deterministic_operations.clj` still redefines the operation success/error/result schemas plus the matching validation helpers
  - this weakens single ownership and risks later drift if result-shape edits land in only one namespace
- a minor local shaping improvement remains in `unregister-operations-by-extension-in!`
  - `set` is rebuilt inline from `remove-ids` during registration-order filtering
  - harmless at current scale, but a pre-bound `remove-id-set` would make the code slightly clearer and avoid recomputation

Recommended direction from review:
- make `psi.deterministic-operation-registry.defs` the sole formal owner of deterministic-operation result schemas and result validation helpers
- reduce `psi.agent-session.deterministic-operations` to invoke execution and workflow-facing result wrapping only
- apply the small local cleanup in `unregister-operations-by-extension-in!` if doing the follow-up shaping pass

2026-05-08 — code-shaper follow-up execution

Implemented the follow-up shaping pass from the code-shaper review.

What changed:
- removed remaining deterministic-operation result contract duplication from `psi.agent-session.deterministic-operations`
  - `operation-success-result-schema`
  - `operation-error-result-schema`
  - `operation-result-schema`
  - `valid-operation-result?`
  - `explain-operation-result`
  now delegate directly to `psi.deterministic-operation-registry.defs`
- this makes `psi.deterministic-operation-registry.defs` the sole formal owner of deterministic-operation result schemas and result validation helpers
- `psi.agent-session.deterministic-operations` is now thinner and more focused on:
  - invoke execution
  - malformed-result exception shaping
  - workflow-facing `operation-result->invoke-step-result`
- applied the small local cleanup in `psi.deterministic-operation-registry.registry/unregister-operations-by-extension-in!`
  - pre-bound `remove-id-set`
  - reused it during registration-order filtering instead of rebuilding `set` inline

Verification for the follow-up shaping pass:
- focused verification green across:
  - `psi.deterministic-operation-registry.defs-test`
  - `psi.deterministic-operation-registry.registry-test`
  - `psi.agent-session.deterministic-operation-registry-test`
  - `psi.agent-session.workflow-invoke-runtime-test`
  - `psi.agent-session.extensions-test`
  - `psi.agent-session.workflow-execution-test`
- combined result: `47 tests, 213 assertions, 0 failures`
- focused lint across touched source and test files: `0 errors, 0 warnings`

2026-05-08 — test review

Review outcome:
- test coverage is broadly strong and aligned with the extracted boundary
- lower-component tests correctly prove the extracted registry contracts directly
- higher-level tests correctly prove workflow invoke and extension cleanup integration paths

Actionable follow-up findings:
- `components/agent-session/test/psi/agent_session/deterministic_operation_registry_test.clj` now substantially overlaps the lower-component proofs in:
  - `components/deterministic-operation-registry/test/psi/deterministic_operation_registry/registry_test.clj`
- the overlap includes lower-owner semantics such as:
  - registration/lookup
  - duplicate rejection
  - invoke success/error behavior
  - unregister-by-extension
  - thrown-operation canonicalization
- after the extraction, those semantics should be owned primarily by the extracted component tests rather than re-proved in agent-session
- the remaining agent-session-owned part in that file is `invoke-step-wrapping-test`, which still belongs above the lower registry because it proves workflow-facing result wrapping
- `defs_test.clj` currently proves definition validation well, but it does not directly prove the extracted result-schema validation helpers with one explicit valid `:ok`, one explicit valid `:error`, and one malformed result case

Recommended direction from review:
- trim or replace `psi.agent-session.deterministic-operation-registry-test` so it covers only agent-session-owned behavior, likely the invoke-step result wrapping surface
- keep lower registry semantics proven in the extracted component tests
- consider adding direct result-schema validation checks to `psi.deterministic-operation-registry.defs-test` to strengthen local proof of the extracted result contract

2026-05-08 — test-shaping follow-up execution

Implemented the test-shaping follow-up from the test review.

What changed:
- reduced `components/agent-session/test/psi/agent_session/deterministic_operation_registry_test.clj` to the agent-session-owned `invoke-step-wrapping-test` only
- removed re-proof of lower registry semantics from that agent-session test namespace so the extracted component remains the primary owner of registry-semantic proofs
- added direct extracted-component result-validation checks to `components/deterministic-operation-registry/test/psi/deterministic_operation_registry/defs_test.clj`
  - one explicit valid `:ok` result
  - one explicit valid `:error` result
  - one malformed result case checked through both `valid-operation-result?` and `explain-operation-result`

Verification for the test-shaping follow-up:
- focused verification green across:
  - `psi.deterministic-operation-registry.defs-test`
  - `psi.deterministic-operation-registry.registry-test`
  - `psi.agent-session.deterministic-operation-registry-test`
  - `psi.agent-session.workflow-invoke-runtime-test`
  - `psi.agent-session.extensions-test`
  - `psi.agent-session.workflow-execution-test`
- combined result: `41 tests, 201 assertions, 0 failures`
- focused lint across touched test files: `0 errors, 0 warnings`
