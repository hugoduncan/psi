Approach:
- treat this as a small lower-boundary component extraction, not a workflow redesign
- preserve deterministic operation invoke/result behavior first
- reduce workflow runtime dependence on `psi.agent-session.*` by moving invoke execution into an extracted lower component
- keep deterministic-operation runtime generic: invoke-step result wrapping should move into explicit workflow-owned code in `psi.agent-session.workflow-statechart-runtime` in the first cut

Planned outcomes:
1. create a new deterministic-operation runtime component
2. move canonical invoke execution out of `psi.agent-session.*`
3. preserve current operation result validation/error behavior
4. update `psi.agent-session.workflow-statechart-runtime` to depend downward on the extracted component, plus any incidental consumers discovered during implementation
5. move invoke-step result wrapping into `psi.agent-session.workflow-statechart-runtime` and record that first-cut ownership boundary

Scope boundaries:
- no redesign of deterministic-operation registration/query semantics
- no workflow authoring redesign
- no broader workflow runtime extraction in this task
- no intentional user-facing behavior changes

Follow-on guidance:
- reference task `105` as the umbrella workflow-adjacent extraction map
- do not leave compatibility shims behind the extraction; move authoritative ownership cleanly
- callers that need defs-level validation/result helpers should depend on `psi.deterministic-operation-registry.defs` directly rather than through the extracted runtime component
