Approach:
- treat this as a focused follow-on to task 133, not a restart of workflow reframing
- preserve all user-facing built-in workflow behavior while replacing the remaining pseudo-extension mechanics
- begin with an explicit inventory of every workflow use of extension-registry/API machinery and every projection/test that still assumes extension ownership
- choose the smallest built-in registration path only after that inventory is complete
- prefer shared registries with built-in-specific entrypoints or provenance-aware insertion before introducing separate built-in registries
- make lifecycle, prompt contribution, provenance, and introspection decisions explicit in the implementation notes

Planned outcomes:
1. map the exact places where built-in workflow still depends on extension-registry/API machinery, including non-agent-session runtime-boundary proofs
2. classify which of those uses require new built-in entrypoints, shared provenance-aware storage, or a small dedicated built-in store
3. decide and document the built-in invocation path for lifecycle and reload/session-switch behavior
4. migrate built-in workflow bootstrap off extension identity seeding and extension API creation
5. update affected projections/tests/docs or implementation notes so they reflect built-in rather than pseudo-extension registration
6. record the new residual status in task 133 and/or this task as needed

Decision rules:
- success requires removing `ext/register-extension-in!` and `ext/create-extension-api` from canonical built-in workflow bootstrap
- reuse of shared storage is allowed only through built-in-specific registration paths, not through extension-shaped registration calls
- built-in lifecycle scope for this slice is `session_switch` only; `session_before_switch`, `session_before_fork`, and `session_fork` remain extension-only lifecycle surfaces unless implementation uncovers a concrete built-in workflow dependency on them
- keep the prompt rendering heading `# Extension Prompt Contributions` unchanged in this task unless implementation shows a near-zero-cost rename, and treat the retained heading as explicit wording debt rather than ownership truth
- if any prompt rendering or introspection wording still says "extension" for shared built-in surfaces, record it explicitly as wording debt rather than ownership truth
- retain `built-in:workflow` only as the stable built-in provenance identifier for workflow-owned surfaces; it must be removed from extension-owned identity state and from proofs that equate it with an extension install

Scope boundaries:
- no lower workflow component redesign
- no user-facing workflow behavior changes
- no broad rewrite of third-party extension mechanics
- no registry split unless shared registries cannot represent built-in ownership cleanly
