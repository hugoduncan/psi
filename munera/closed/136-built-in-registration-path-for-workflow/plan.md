Approach:
- treat this as a focused follow-on to task 133, not a restart of workflow reframing
- preserve all user-facing built-in workflow behavior while replacing the remaining pseudo-extension mechanics
- begin with an explicit inventory of every workflow use of extension-registry/API machinery and every projection/test that still assumes extension ownership
- choose the smallest built-in registration path only after that inventory is complete
- use shared provenance-aware command/tool registries rather than introducing separate built-in command/tool stores
- keep prompt contributions in the shared session prompt-contribution store through a built-in-specific registration path
- add only the smallest dedicated built-in lifecycle surface needed for workflow's `session_switch` callback
- make lifecycle, prompt contribution, provenance, and introspection decisions explicit in the implementation notes

Planned outcomes:
1. map the exact places where built-in workflow still depends on extension-registry/API machinery, including non-agent-session runtime-boundary proofs
2. generalize shared command/tool registries with built-in-aware provenance and registration entrypoints as the first internal phase of `136`
3. add and document the explicit built-in invocation path for `session_switch` lifecycle and reload/session-switch preservation
4. migrate built-in workflow bootstrap off extension identity seeding and extension API creation
5. update affected projections/tests/docs or implementation notes so they reflect built-in rather than pseudo-extension registration
6. record the new residual status in task 133 and/or this task as needed

Decision rules:
- success requires removing `ext/register-extension-in!` and `ext/create-extension-api` from canonical built-in workflow bootstrap
- command/tool registry generalization remains inside `136`; do not split it into a precursor task
- reuse of shared command/tool registries is preferred, provided built-ins no longer require extension identity seeding and registry reads preserve provenance cleanly
- prompt contributions remain in shared session state, reached through a built-in-specific registration path rather than extension API wrapping
- built-in lifecycle scope for this slice is `session_switch` only; `session_before_switch`, `session_before_fork`, and `session_fork` remain extension-only lifecycle surfaces unless implementation uncovers a concrete built-in workflow dependency on them
- keep the prompt rendering heading `# Extension Prompt Contributions` unchanged in this task unless implementation shows a near-zero-cost rename, and treat the retained heading as explicit wording debt rather than ownership truth
- if any prompt rendering or introspection wording still says "extension" for shared built-in surfaces, record it explicitly as wording debt rather than ownership truth
- retain `built-in:workflow` only as the stable built-in provenance identifier for workflow-owned surfaces; it must be removed from extension-owned identity state and from proofs that equate it with an extension install

Scope boundaries:
- no lower workflow component redesign
- no user-facing workflow behavior changes
- no broad rewrite of third-party extension mechanics
- no separate built-in command/tool registry unless shared registries fail to represent built-in provenance cleanly during implementation
- no extraction of a new common/shared registry substrate under command/tool registries in this task; generalize the existing registries locally only as far as built-in workflow requires
