Approach:
- treat this as a concept-first extraction, not another generic workflow-runtime split
- preserve the current role split from `127`: session-config shaping remains distinct from step materialization
- move only workflow child-session configuration policy into a dedicated lower component
- preserve the existing workflow-runtime → session adapter seam from `128` for session-bound reads used by step session-config shaping
- keep `agent-session` as the higher assembly/orchestration layer and avoid reclassifying this logic upward

Planned outcomes:
1. confirm the exact current responsibility inventory in `psi.workflow-runtime.step-session-config`, including model inheritance, prompt-mode derivation, thinking-level derivation, and prompt-component-selection derivation
2. choose the narrowest accurate component/namespace name for the extracted ownership surface
3. create the dedicated lower component and move the authoritative session-config logic with minimal semantic change
4. preserve `resolve-step-session-config` as the canonical public behavior surface and preserve its externally consumed output contract unless a justified replacement is recorded
5. rewire workflow-runtime, `agent-session.context`, `psi_tool_workflow`, and affected tests to the new owner
6. decide whether `psi.workflow-runtime.step-session-config` disappears entirely or remains only as a tiny temporary forwarding seam, with justification if retained
7. verify behavior remains unchanged and no accidental materialization/runtime reshuffle occurred
8. record the final boundary, public surface, naming choice, dependency/input shape, transitional namespace status, and any residual dependency debt in `implementation.md`

Scope boundaries:
- no workflow behavior redesign
- no inheritance semantics redesign
- no execution-adapter redesign
- no public workflow API changes
- no step-materialization extraction in this task
- no generic session-policy framework work
