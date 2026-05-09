Approach:
- treat this as a focused follow-on to task 133, not a restart of workflow reframing
- preserve all user-facing built-in workflow behavior while replacing the remaining pseudo-extension mechanics
- identify the smallest built-in registration abstraction that can own workflow tool/command/prompt/lifecycle installation without pretending workflow is an extension
- prefer minimal shared-registry changes over a broad extension-runtime redesign

Planned outcomes:
1. map the exact places where built-in workflow still depends on extension-registry/API machinery
2. decide the narrowest built-in registration path that can replace those uses
3. migrate built-in workflow bootstrap to the new path
4. update any affected projections/tests/docs so they reflect built-in rather than pseudo-extension registration
5. record the new residual status in task 133 and/or this task as needed

Scope boundaries:
- no lower workflow component redesign
- no user-facing workflow behavior changes
- no broad rewrite of third-party extension mechanics
