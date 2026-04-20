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

Notes:
- This is still execution-semantics groundwork only; it does not yet create workflow runs in canonical state or orchestrate execution sessions.
- Existing extension workflow runtime in `workflows.clj` remains separate; this new statechart module defines the deterministic workflow-run transition model for the new runtime.
