Approach:
- treat this as a small lower-boundary component extraction, not a workflow redesign
- preserve deterministic operation invoke/result behavior first
- reduce workflow runtime dependence on `psi.agent-session.*` by moving invoke execution into an extracted lower component
- make the one real boundary decision explicit: whether workflow-facing invoke-step result wrapping stays with deterministic-operation runtime in the first cut or moves to workflow-owned code

Planned outcomes:
1. create a new deterministic-operation runtime component
2. move canonical invoke execution out of `psi.agent-session.*`
3. preserve current operation result validation/error behavior
4. update workflow runtime consumers to depend downward on the extracted component
5. record the final ownership decision for invoke-step result wrapping

Scope boundaries:
- no redesign of deterministic-operation registration/query semantics
- no workflow authoring redesign
- no broader workflow runtime extraction in this task
- no intentional user-facing behavior changes

Follow-on guidance:
- reference task `105` as the umbrella workflow-adjacent extraction map
- if invoke-step result wrapping remains in the extracted runtime for the first cut, record any cleaner workflow-owned split as a later shaping follow-on rather than expanding this task
