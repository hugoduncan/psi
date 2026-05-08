Approach:
- treat this as a focused follow-on to the landed `turn-runtime` extraction rather than as a new sibling-component design
- expand the existing `turn-runtime` boundary to absorb lower prepared-turn request and recording ownership that still lives under `agent-session`
- keep dispatch invocation and session-owned orchestration explicitly above the boundary
- use the task to replace the old narrow `turn-preparation` framing with one coherent lower component story

Planned outcomes:
1. establish `turn-runtime` as the single lower prepared-turn component identity
2. identify which parts of current request preparation are truly lower assembly versus session-owned projection/policy
3. move lower response-recording ownership into `turn-runtime`
4. update `psi.turn` to depend downward on the expanded lower component while retaining dispatch/session orchestration ownership
5. leave a cleaner keep/move/split boundary for any later `psi.turn` refactoring

Scope boundaries:
- no extraction of `psi.turn` as a component in this task
- no movement of dispatch invocation below `agent-session`
- no broad prompt-composition extraction in this task
- no prompt lifecycle redesign beyond ownership/import adjustment needed for the boundary expansion

Follow-on guidance:
- this task should be treated as the current authoritative continuation of the turn-runtime extraction thread
- historical task `102-turn-preparation-component-extraction` should not be revived as a sibling component target
- follow-on work after this task, if needed, should start from the clearer split of lower prepared-turn mechanics in `turn-runtime` versus higher dispatch-owning orchestration in `psi.turn` and `agent-session`
