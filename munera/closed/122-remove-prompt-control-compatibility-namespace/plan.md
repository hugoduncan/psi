Approach:
- treat this as a narrow compatibility-removal task, not a turn or workflow redesign
- rewire all remaining production and test consumers from `psi.agent-session.prompt-control` to `psi.agent-session.turn` in one pass
- delete the facade only after callers are updated
- keep proof focused on unchanged prompt-turn behavior rather than preserving delegation tests for a retired namespace

Planned outcomes:
1. remove the obsolete `psi.agent-session.prompt-control` namespace entirely
2. make `psi.agent-session.turn` the sole higher turn-entry surface used by current code
3. reduce prompt/turn architectural noise without changing behavior
4. leave repo search and focused verification proving the cleanup is complete

Scope boundaries:
- no behavioral redesign
- no change to the `agent-session.turn` ↔ `turn-runtime` boundary
- no replacement compatibility shim
- no broad prompt-path cleanup beyond references directly required by removing `prompt-control`

Follow-on guidance:
- update active task text where `prompt-control` is still described as a live compatibility seam
- treat closed historical task records as history unless a direct implementation note must be clarified
- if implementation reveals one or two remaining adjacent obsolete prompt-turn names, record them separately rather than widening this task implicitly
