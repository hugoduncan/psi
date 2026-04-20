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

Notes:
- This is intentionally the pure data/state slice only; it does not yet compile workflows to execution statecharts or orchestrate step sessions.
- Existing extension workflow runtime in `workflows.clj` remains separate; this new model is the canonical slice-one runtime state foundation for deterministic workflow runs.
