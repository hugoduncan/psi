Approach:
- treat this as a narrow below-dispatch workflow-domain extraction, not a workflow-runtime redesign
- preserve existing pure judge/routing semantics first
- move only canonical pure projection/normalization/routing logic into a lower component
- keep persistence reads, judge-session execution, retry orchestration, and public entrypoints above the new boundary

Planned outcomes:
1. create a lower workflow judge/routing component
2. move canonical pure judge projection, verdict normalization, and routing evaluation out of `psi.agent-session.*`
3. preserve current projection semantics and current step-id contract unless implementation explicitly records a necessary decision
4. update workflow runtime consumers to depend downward on the extracted component
5. keep persistence reads, session creation, prompt submission, and retry orchestration outside the extracted component
6. remove mixed authoritative ownership of the pure logic from `psi.agent-session.*`; a thinner higher impure execution namespace may remain if that is the cleanest final split

Scope boundaries:
- no redesign of workflow judge semantics
- no redesign of workflow routing semantics
- no move of mutations/resolvers/`psi-tool`
- no broad workflow runtime extraction in this task
- no intentional user-facing behavior changes

Follow-on guidance:
- reference task `105` as the umbrella extraction map
- coordinate with the planned turn-execution-contract extraction so judge execution ownership does not drift into the new component
- keep the new component focused on domain decisions, not session orchestration
