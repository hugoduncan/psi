Approach:
- treat this as an enabling bounded-execution extraction rather than a full turn redesign
- scope it to the workflow forms that actually require session/turn execution: session-backed actor steps and judge steps
- define the smallest useful workflow-facing bounded execution boundary
- preserve current workflow behavior while shifting bounded callers to direct canonical execution results
- keep persistence/audit behavior intact without using journals/transcripts as the semantic result contract
- keep workflow-specific session shaping and non-turn workflow forms outside the new boundary
- record the key implementation-shaping boundary decisions explicitly rather than leaving them implicit in code movement

Planned outcomes:
1. identify the current workflow session-backed actor/judge execution path and its mixed ownership
2. define a lower bounded execution contract for those workflow step forms
3. route workflow runtime through the new contract
4. isolate execution-session creation/binding and prompt submission mechanics behind that boundary
5. keep workflow-specific session-config derivation and conversation shaping outside the extracted contract
6. keep deterministic `:invoke` and delegated `:delegate` execution outside this task
7. explicitly record the chosen result shape, boundary start, session-creation mode, retry/session-reuse approach, and lower-boundary home
8. record the resulting dependency direction for the follow-on workflow runtime-core extraction

Scope boundaries:
- no redesign of workflow routing or progression
- no redesign of transcript/UI publication
- no broad persistence redesign
- no move of mutations/resolvers/`psi-tool`
- no full turn component redesign in this task
- no unification of non-turn workflow forms under this contract

Follow-on guidance:
- coordinate with task `123` so judge decision logic and judge execution ownership remain separate
- use canonical execution-result return as the bounded-caller contract
- treat journal/transcript state as audit/history rather than semantic recovery
- prefer expanding an existing lower turn-runtime boundary unless that would distort ownership
