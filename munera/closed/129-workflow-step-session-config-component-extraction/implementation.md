2026-05-07

Implemented extraction of authoritative workflow child-session config policy into a dedicated lower component.

Final component / namespace name
- chosen component: `workflow-step-session-config`
- chosen namespace family: `psi.workflow-step-session-config.*`
- canonical public owner: `psi.workflow-step-session-config.core`
- naming decision: kept the narrower `workflow-step-session-config` name because the final ownership surface remains specifically about workflow step child-session configuration rather than a broader workflow session policy domain
- rejected alternative: `workflow-session-config` would imply a wider ownership surface than this task actually moved

Responsibility inventory now owned by the extracted component
- parent session selection / fallback for workflow child-session config derivation
- tool inheritance / normalization for workflow child sessions
- skill inheritance / lookup fallback for workflow child sessions
- model inheritance for workflow child sessions
- prompt-mode inheritance from the parent session
- thinking-level derivation / inheritance
- prompt-component-selection derivation
- workflow meta merge rules used during child-session config shaping
- child-session developer-prompt / config derivation
- canonical public behavior surface `resolve-step-session-config`

Responsibility inventory intentionally left outside the extracted component
- workflow runtime stepping / progression / statechart execution remains in `psi.workflow-runtime.*`
- workflow step conversation materialization remains in `psi.workflow-runtime.step-materialization`
- public workflow execution entrypoints remain under higher `agent-session` surfaces
- session creation / prompt execution remains outside this lower component

Public surface
- public var: `resolve-step-session-config`
  - remains public because it is the canonical lower behavior surface consumed by workflow runtime callers through ctx wiring
- all helper functions remain private
- externally consumed output contract was preserved unchanged

Dependency / input shape
- preserved the current direct dependency pattern
- the extracted component still reads workflow registry state directly via `psi.workflow-registry.registry/workflow-definition` for referenced definition metadata lookup
- the extracted component still depends on the named runtime → session seam `psi.workflow-runtime.execution-adapter` for session-bound reads
- this task did not redesign the input shape because preserving behavior and boundary clarity was the smaller change

Transitional namespace status
- `psi.workflow-runtime.step-session-config` was removed entirely after rewiring
- no forwarding seam remains
- all production/test consumers were rewired directly to `psi.workflow-step-session-config.core`

Residual dependency status
- no workflow-runtime namespace directly depends on the removed `psi.workflow-runtime.step-session-config` namespace
- workflow runtime continues to consume step session-config derivation through ctx callback wiring rather than a direct namespace dependency

Consumer rewires completed
- `psi.agent-session.context` now wires `:resolve-workflow-step-session-config-fn` from `psi.workflow-step-session-config.core/resolve-step-session-config`
- `psi.agent-session.psi-tool-workflow` compatibility backfill now resolves the new namespace
- `psi.agent-session.test-support` now wires the new owner
- lower proofs moved from `components/workflow-runtime/test/.../step_session_config_test.clj` to `components/workflow-step-session-config/test/.../core_test.clj`

Boundary verification notes
- `step-materialization` remained separate and unchanged in role
- execution adapter seam remained the canonical higher/session-bound crossing
- no workflow behavior redesign was introduced during extraction

Verification
- focused tests: `5 tests, 20 assertions, 0 failures`
- focused lint: `0 errors, 0 warnings`

Review notes
- code-shaper review (terse): extraction is clean, the new owner is small and coherent, and the rewiring avoided unnecessary compatibility seams; follow-up completed: parent-session data is now read once and reused across tool/skill/model/prompt-mode derivation, and the fallback placeholder skill map now lives behind a tiny private helper for better scanability
- verification after code-shaper follow-up: focused tests remain `5 tests, 20 assertions, 0 failures` and focused lint remains clean
- test review (terse): the extracted owner tests cover the main inheritance/override contract well at the public boundary; follow-up completed: added focused missing-skill and missing-tool fallback proofs at `resolve-step-session-config`, and re-evaluated test shape — the matrix is still small enough that direct setup remains clearer than introducing shared or table-driven indirection
- verification after test follow-up: focused tests are now `7 tests, 22 assertions, 0 failures` and focused lint remains clean
- test-shaper review (terse): tests are strong, boundary-focused, deterministic, and now cover the main behavior partitions including fallback cases; follow-up completed: added one tiny local helper to compress repeated definition/run setup without broadening into shared fixtures, and normalized assertion grouping slightly for easier scanability
- verification after test-shaper follow-up: focused tests remain `7 tests, 22 assertions, 0 failures` and focused lint remains clean
