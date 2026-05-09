Approach:
- treat this as the main below-public-entrypoint workflow runtime-core extraction, not a public API redesign
- preserve runtime behavior first while moving cohesive runtime-core ownership into a lower component
- review the workflow-adjacent namespace cluster explicitly rather than assuming every nearby workflow namespace belongs inside runtime core
- depend downward on lower seams for judge/routing, bounded step execution, and deterministic operation runtime where available
- keep mutations/resolvers/`psi-tool` and other public entrypoints above the extracted runtime core
- record final runtime-core membership and any temporary residual dependencies explicitly rather than leaving them implicit in moved code

Planned outcomes:
1. identify the authoritative workflow runtime-core cluster
2. decide which adjacent workflow namespaces are true runtime-core owners versus sibling lower helpers or above-boundary shaping owners
3. create a lower workflow runtime-core component with authoritative `psi.workflow-runtime.*` namespace(s)
4. move execution/progression/statechart runtime ownership out of `psi.agent-session.*`
5. update higher workflow entrypoints to depend downward on the extracted runtime-core component
6. consume lower seams for judge/routing and bounded step execution where available, or record temporary residual dependencies explicitly if not yet landed
7. record final runtime-core membership, statechart-runtime decomposition choices, and any residual above-boundary workflow logic for later cleanup

Scope boundaries:
- no move of mutations/resolvers/`psi-tool`
- no redesign of public workflow APIs
- no dispatcher redesign
- no unrelated session-core extraction
- no intentional user-facing behavior changes
- no automatic assumption that `workflow_runtime`, `workflow_step_prep`, or `workflow_terminal_contract` belong inside runtime core without explicit review

Follow-on guidance:
- coordinate with tasks `123` and `124` so the runtime extraction consumes lower seams rather than reabsorbing them
- keep runtime-core ownership focused on execution/progression/statechart concerns
- allow decomposition of `workflow_statechart_runtime.clj` if whole-file movement would preserve poor ownership
- avoid compatibility shims unless a very narrow temporary seam is proven necessary
