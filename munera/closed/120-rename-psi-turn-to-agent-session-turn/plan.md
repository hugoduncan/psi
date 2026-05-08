Approach:
- treat this as a narrow post-`119` ownership-signaling rename, not a behavioral refactor
- start only once `119` has landed the specific ownership split that leaves the surviving `psi.turn.*` family as higher `agent-session` orchestration rather than lower prepared-turn ownership
- inventory the remaining higher `psi.turn.*` namespaces and rename them into `psi.agent-session.turn.*`
- update production and test consumers in one pass so the old top-level higher-family names do not remain authoritative
- update any production string/data references if they exist
- keep `psi.turn-runtime.*` unchanged as the lower prepared-turn component family

Planned outcomes:
1. make the higher turn orchestration family's `agent-session` ownership explicit in its namespaces
2. remove the misleading top-level authoritative `psi.turn` / `psi.turn.*` higher namespaces from production code
3. preserve the `119` boundary story: lower mechanics in `turn-runtime`, higher orchestration in `agent-session`
4. leave repo search and focused tests demonstrating the rename is complete

Scope boundaries:
- no runtime behavior changes
- no expansion or reversal of the `119` ownership split
- no new long-lived compatibility shim
- no rename of `psi.turn-runtime.*`
- no broader namespace cleanup beyond what is directly required by renaming the higher `psi.turn.*` family

Follow-on guidance:
- execute this only after `119` lands or alongside its final polishing pass if that reduces churn without blurring the task boundary
- if implementation uncovers one or two adjacent top-level helper namespaces whose names become newly misleading solely because of this rename, record them separately rather than widening this task implicitly
