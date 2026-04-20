Created to refine a deterministic multi-agent coordination design before implementation planning.

Initial intent:
- replace prompt-only coordination with runtime-owned workflow/statechart coordination
- collaborate with the user to remove ambiguity before writing `plan.md`

Current status:
- task created with design questions and first-pass scope candidates
- design and implementation plan were subsequently completed
- first implementation slice has now started

2026-04-19 — workflow state model groundwork
- Added `components/agent-session/src/psi/agent_session/workflow_model.clj`
- Established canonical root-state placement for deterministic workflows under `:workflows`
  - `[:workflows :definitions]`
  - `[:workflows :runs]`
  - `[:workflows :run-order]`
- Added pure Malli schemas for:
  - workflow definitions
  - step definitions
  - retry and capability policies
  - result envelopes
  - step attempts
  - step runs
  - workflow runs
  - workflow root-state slice
- Wired canonical root-state initialization in `context.clj`
- Exposed workflow root-state paths in `session.clj` and `session_state.clj`
- Added focused schema/state tests in `workflow_model_test.clj`

2026-04-19 — workflow statechart compilation groundwork
- Added `components/agent-session/src/psi/agent_session/workflow_statechart.clj`
- Defined the generic slice-one workflow-run statechart with explicit phases:
  - `:pending`
  - `:running`
  - `:validating`
  - `:blocked`
  - `:completed`
  - `:failed`
  - `:cancelled`
- Defined the explicit workflow event surface for runtime orchestration:
  - `:workflow/start`
  - `:workflow/attempt-started`
  - `:workflow/result-received`
  - `:workflow/step-succeeded`
  - `:workflow/block`
  - `:workflow/resume`
  - `:workflow/retry`
  - `:workflow/fail`
  - `:workflow/complete`
  - `:workflow/cancel`
- Added `compile-definition` to normalize sequential workflow definitions into execution metadata:
  - chart
  - ordered steps
  - initial step id
  - next-step derivation
- Added focused statechart/compilation tests in `workflow_statechart_test.clj`

2026-04-19 — workflow run creation groundwork
- Added `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
- Implemented pure canonical-root operations for:
  - workflow definition registration
  - workflow run creation from a registered definition id
  - workflow run creation from an inline definition
- Workflow runs now capture immutable effective-definition snapshots at creation time
- Workflow runs initialize:
  - `:status :pending`
  - `:current-step-id` from compiled step order
  - per-step `:step-runs`
  - canonical creation history entry (`:workflow/run-created`)
- Added focused runtime tests in `workflow_runtime_test.clj`

2026-04-19 — workflow step-attempt session linkage
- Added `components/agent-session/src/psi/agent_session/workflow_attempts.clj`
- Implemented one canonical execution child session per workflow step attempt
- Workflow-owned child sessions are created through the existing child-session runtime path and attempts record execution-session ids
- Added focused tests in `workflow_attempts_test.clj`

2026-04-19 — result validation and progression
- Added `components/agent-session/src/psi/agent_session/workflow_progression.clj`
- Implemented pure progression operations for:
  - starting the latest attempt
  - structured result-envelope submission
  - generic envelope validation
  - step-schema validation
  - success advancement to next step
  - terminal completion on final step
  - blocked-state transition
  - validation-failure retry/fail behavior
  - execution-failure retry/fail behavior
  - blocked-run resume
- Added focused tests in `workflow_progression_test.clj`

2026-04-19 — Pathom/EQL workflow read surface
- Added `components/agent-session/src/psi/agent_session/resolvers/workflows.clj`
- Exposed workflow root attrs from session root:
  - `:psi.workflow/definition-count`
  - `:psi.workflow/definition-ids`
  - `:psi.workflow/definitions`
  - `:psi.workflow/run-count`
  - `:psi.workflow/run-ids`
  - `:psi.workflow/run-statuses`
  - `:psi.workflow/runs`
- Exposed entity-targeted workflow detail attrs:
  - `:psi.workflow.definition/detail` from `{:psi.workflow.definition/id ...}`
  - `:psi.workflow.run/detail` from `{:psi.workflow.run/id ...}`
- Added session-side workflow linkage attrs in `resolvers/session.clj`:
  - `:psi.agent-session/workflow-run-id`
  - `:psi.agent-session/workflow-step-id`
  - `:psi.agent-session/workflow-attempt-id`
  - `:psi.agent-session/workflow-owned?`
  - `:psi.workflow.run/id` as session→workflow reference
- Wired workflow resolvers into the assembled Pathom surface in `resolvers.clj`
- Added focused resolver tests in `workflow_resolvers_test.clj`
- Verified focused workflow + resolver tests are green:
  - `clojure -M:test --focus psi.agent-session.workflow-resolvers-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.workflow-progression-test --focus psi.agent-session.resolvers-test`

Notes:
- The deterministic workflow substrate now covers state model, statechart compilation, run creation, attempt/session linkage, result progression, and Pathom/EQL read exposure.
- Remaining work is primarily:
  - `psi-tool` workflow ops
  - orchestration that combines run creation, attempt creation, and progression into a full executable lifecycle
  - representative chain-like proof and `agent-chain` follow-on
- Existing extension workflow runtime in `workflows.clj` remains separate; `workflow_runtime.clj` and related files are for the new canonical deterministic workflow-run state.
