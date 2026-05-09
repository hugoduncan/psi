Approach:
- treat this as a focused follow-on to task 133, not a restart of workflow reframing
- preserve all user-facing built-in workflow behavior while replacing the remaining pseudo-extension mechanics
- begin with an explicit inventory of every workflow use of extension-registry/API machinery and every projection/test that still assumes extension ownership
- choose the smallest built-in registration path only after that inventory is complete
- prefer shared registries with built-in-specific entrypoints or provenance-aware insertion before introducing separate built-in registries
- make lifecycle, prompt contribution, provenance, and introspection decisions explicit in the implementation notes

Planned outcomes:
1. map the exact places where built-in workflow still depends on extension-registry/API machinery
2. classify which of those uses require new built-in entrypoints, shared provenance-aware storage, or a small dedicated built-in store
3. decide and document the built-in invocation path for lifecycle and reload/session-switch behavior
4. migrate built-in workflow bootstrap off extension identity seeding and extension API creation
5. update affected projections/tests/docs or implementation notes so they reflect built-in rather than pseudo-extension registration
6. record the new residual status in task 133 and/or this task as needed

Decision rules:
- success requires removing `ext/register-extension-in!` and `ext/create-extension-api` from canonical built-in workflow bootstrap
- reuse of shared storage is allowed only through built-in-specific registration paths, not through extension-shaped registration calls
- if any prompt rendering or introspection wording still says "extension" for shared built-in surfaces, record it explicitly as wording debt rather than ownership truth
- if `built-in:workflow` remains anywhere, it must remain only as a built-in provenance identifier rather than as extension identity in extension-owned state

Scope boundaries:
- no lower workflow component redesign
- no user-facing workflow behavior changes
- no broad rewrite of third-party extension mechanics
- no registry split unless shared registries cannot represent built-in ownership cleanly
