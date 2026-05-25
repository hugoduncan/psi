# 178 registry/session membership unification

## Intent

Create a focused design task for unifying how registry-owned definitions and session-owned capability state relate across the system.

The task should establish a single architectural model for registries and session use of registries so future registry migrations and session-shaping work follow one contract instead of a mix of embedded session payloads, membership ids, and compatibility projections.

## Problem

The registry unification arc through tasks `164`–`177` established a shared `root-registry` substrate and migrated the main registry owners onto it, but session interaction with those registries is still not fully unified.

Today the system mixes several patterns:

- canonical definitions owned in root-backed registries
- session-owned membership ids (`:skill-ids`, `:prompt-contribution-ids`)
- session-owned effective payloads (`:tool-defs`)
- derived compatibility projection state (`:prompt-contributions`)
- runtime-owned adapter surfaces (`deterministic-operation-registry`)

That asymmetry is currently workable, but it leaves the architecture harder to reason about and makes later migrations more error-prone.

## Scope

This task covers the architectural unification of registry/session interaction for the registry-like domains already in the root-registry migration arc, especially:

- `skill-registry`
- `prompt-registry`
- `tool-registry`
- `workflow-registry`
- `deterministic-operation-registry`

It should also inspect adjacent session/runtime surfaces where those registries are consumed, including:

- session state shape
- bootstrap/default session construction
- new/resume/fork/child-session lifecycle shaping
- prompt/system-prompt assembly
- workflow child-session shaping
- capability/introspection projections

This task is primarily about architectural unification and task decomposition, not immediate implementation of all follow-on changes.

## Desired outcome

The task should leave behind a clear, implementation-guiding design that answers, for each registry-like domain:

1. where canonical definitions live
2. whether sessions own membership/selection for that domain
3. what session field is authoritative for that membership/selection
4. what effective execution payloads are derived from that authoritative state
5. what compatibility projections remain and whether they should be removed
6. how workflow/session shaping should narrow or inherit the domain

For prompt contributions specifically, the design must distinguish authoritative membership from required projection state:

- `:prompt-contribution-ids` is the authoritative session-owned membership surface
- `:prompt-contributions` remains a derived compatibility/execution projection while prompt lifecycle seams still persist a materialized vector for refresh, introspection, and mutation-result contracts
- child/new/resume/fork/session shaping should preserve that split explicitly rather than describing `:prompt-contribution-ids` as the only prompt-related session field that exists in practice

The design should define a preferred invariant for future work:

- registries own canonical definitions
- sessions own membership/selection
- runtime and workflow execution derive effective payloads from canonical definitions plus session-owned membership/selection
- compatibility projections are derived only and never authoritative

The design should explicitly classify current domains into one of these categories:

- direct fit for `definitions in registry + membership ids in session`
- registry-owned definitions plus derived effective session payloads
- runtime-owned adapter domain that should only partially align with the pattern
- intentionally out of scope / not worth forcing into the pattern

## Architectural invariant

The preferred project-wide rule is:

- canonical definitions live in a registry or another explicitly named runtime owner
- sessions own only membership, selection, or narrowing for those definitions
- execution-facing payloads are derived from canonical definitions plus session membership/selection
- compatibility projections may be persisted when current seams still require them, but they are never authoritative

This means future work should describe every field that refers to a registry-backed capability as one of four things:

- canonical definition storage
- authoritative session membership or selection
- derived execution payload
- derived compatibility projection

A field should not silently play more than one of those roles.

## Current classification by domain

### 1. `skill-registry`

Category: direct fit for `definitions in registry + membership ids in session`.

Current shape:

- canonical definitions live in the root registry under `:skills` via `psi.skill-registry.root-storage`
- the authoritative session field is `:skill-ids`
- effective prompt/runtime skill payloads are derived by looking up those ids in root state and then applying the existing skill-registry canonical ordering rules
- prompt rendering, discovery, workflow child-session shaping, and session query surfaces should read derived skills from `:skill-ids`, not from any session-owned embedded skill payload

Implication:

- skills are already the clearest example of the target model
- future work should continue to treat `:skill-ids` as the only authoritative session surface for skill membership
- any model-visible skill vectors are projections, not alternate storage authorities

### 2. `prompt-registry`

Category: direct fit for `definitions in registry + membership ids in session`, with an intentionally retained compatibility projection.

Current shape:

- canonical prompt contribution definitions live in the root registry under `:prompt-contributions` via `psi.prompt-registry.root-storage`
- the authoritative session field is `:prompt-contribution-ids`
- the derived execution/compatibility projection is `:prompt-contributions`
- prompt lifecycle handlers still persist that vector because refresh, introspection, and mutation-result seams currently materialize and observe it directly

Authoritative rule:

- `:prompt-contribution-ids` is the canonical session-owned membership surface
- `:prompt-contributions` is required derived compatibility/execution projection state only
- `:prompt-contributions` must always be derivable from root-backed definitions plus `:prompt-contribution-ids` and current selection/filtering rules
- no lifecycle or workflow seam should treat `:prompt-contributions` as independent authority

Child/new/resume/fork/session shaping rule:

- prompt inheritance and filtering should be described from parent membership ids, not from the parent materialized contribution vector
- child-session shaping may persist a derived `:prompt-contributions` vector for compatibility, but that vector must be rebuilt from inherited/selected ids plus canonical registry lookup
- the current child-session implementation already points this way semantically: `child_session_state.clj` persists both `:prompt-contribution-ids` and a materialized `:prompt-contributions` vector, and future cleanup should keep the split explicit rather than collapsing it back into one ambiguous field

Implication:

- prompts fit the target model, but they are the clearest current example of a necessary persisted derived projection
- future cleanup may remove the materialized vector, but only after refresh/introspection/mutation contracts no longer require it

### 3. `tool-registry`

Category: registry-owned definitions plus derived effective session payloads.

Current shape:

- canonical tool definitions live in the root-backed tool registry
- sessions currently persist `:tool-defs` as the effective execution payload
- prompt assembly, agent runtime tool installation, scheduler overrides, workflow child-session creation, and mutation contracts consume `:tool-defs` directly
- there is no authoritative session `:tool-ids` field today

Decision:

- tools should converge toward the same architectural model as skills and prompts
- the long-term authoritative session surface should become membership/selection data such as `:tool-ids` or another explicitly named selection field
- persisted `:tool-defs` should be demoted to derived execution/compatibility payload, not remain the long-term authority

Why this is the preferred direction:

- task `165` established root-backed canonical tool definitions as the lower owner, so keeping session-owned full tool maps as authority would preserve an accidental asymmetry rather than a principled exception
- workflow child-session shaping, scheduler overrides, and prompt rebuild flows already need to reason about selected tool names separately from full tool maps
- aligning tools with the same authority split as skills/prompts will make lifecycle and narrowing rules easier to state and test

What remains exceptional for tools even after convergence:

- tools still need a materialized execution payload because runtime tool installation and provider-facing request shaping require full normalized tool maps
- therefore `:tool-defs` may remain persisted longer than `:tool-ids`-only designs would suggest, but it should be described as derived execution state, not canonical membership authority

Migration direction:

- add an authoritative session membership/selection field for tools
- derive `:tool-defs` from canonical registry definitions plus that membership/selection
- migrate child-session and workflow selection semantics to narrow by membership/selection first, then re-materialize `:tool-defs`
- keep `:tool-defs` as a compatibility/execution projection until runtime/update/mutation seams can operate from derived realization alone

### 4. `workflow-registry`

Category: intentionally out of scope for session membership unification as a capability-membership domain.

Current shape:

- canonical workflow definitions live in the root-backed workflow registry
- sessions do not own membership in workflow definitions the same way they own available skills/prompts/tools
- workflow runs and workflow selection are invocation/runtime concerns, not persistent per-session capability membership
- workflow child-session shaping consumes workflow definitions and step-owned narrowing instructions, but it does not maintain a session field analogous to `:skill-ids`

Rule:

- workflow definitions should remain canonical registry-owned definitions looked up by id
- do not invent a session workflow-membership field just for uniformity
- session/workflow shaping should instead describe which workflow definition is being invoked and how that definition narrows child-session capabilities

Implication:

- workflows are registry-backed definitions, but not session-membership capabilities
- they are therefore adjacent to this model, not a direct member of the membership-unification pattern

### 5. `deterministic-operation-registry`

Category: runtime-owned adapter domain that only partially aligns with the pattern.

Current shape:

- canonical deterministic operation definitions live in shared root-registry storage under the runtime-owned adapter
- no session-owned membership field exists or is desired
- workflow invoke steps target operation ids directly at runtime
- duplicate rejection, bulk unregister by extension, and invoke-miss throwing remain adapter-owned semantics

Rule:

- deterministic operations align with registry-owned canonical definitions
- they do not align with session-owned membership/selection
- future work should preserve the adapter/runtime boundary and should not force operations into session capability membership just to match skills/prompts/tools

Implication:

- deterministic operations are the named exception class for this design: registry-backed, runtime-owned, and invoke-oriented rather than session-membership-driven

## Session-field classification

The current authoritative/derived split should be described as follows:

| Domain | Canonical definitions | Authoritative session field | Derived execution payload | Derived compatibility projection |
|---|---|---|---|---|
| skills | root registry `:skills` | `:skill-ids` | derived skill maps used by prompt/discovery/workflow shaping | none required today |
| prompts | root registry `:prompt-contributions` | `:prompt-contribution-ids` | sorted contributions used for prompt assembly | persisted `:prompt-contributions` |
| tools | root-backed tool registry | none yet; should become `:tool-ids` or equivalent | persisted `:tool-defs` | `:tool-defs` currently also carries compatibility burden |
| workflows | root registry `:workflow-definitions` | none; invocation is by workflow id, not session membership | runtime lookup of workflow definitions | canonical compatibility map at `[:workflows :definitions]`, but not session-local |
| deterministic operations | root registry `:deterministic-operations` via adapter | none | runtime invoke lookup by operation id | adapter-owned registry object surface |

## Lifecycle and bootstrap rule

Future lifecycle code should follow one rule whenever a registry-backed capability is installed into a session:

1. ensure canonical definitions exist in the owning registry
2. persist only authoritative session membership/selection in the session where the domain fits that model
3. derive execution payloads from canonical definitions plus that membership/selection
4. persist derived projections only when an existing runtime or compatibility seam still requires materialized state
5. when persisting a derived projection, name it and document it as non-authoritative

Applied to concrete lifecycle surfaces:

- bootstrap/default session construction should load canonical definitions into their registry owners first, then seed session membership/selection
- new/resume/fork child-session shaping should inherit or narrow membership/selection first, then rebuild any required derived execution payloads
- prompt/system-prompt refresh should read canonical skills/prompts/tools through their authoritative session membership/selection surfaces instead of trusting embedded session payloads as source of truth
- capability/introspection projections should read from canonical definitions plus membership/selection when possible, and only fall back to persisted compatibility projections where current seams still require them

## Tool-session model resolution

The tool question should be considered resolved by this design:

- `:tool-defs` should not remain the long-term authoritative session surface
- tools should move toward `registry definitions + session membership/selection + derived tool payload`
- the exact field name may be `:tool-ids` or another explicitly named selection structure if tool narrowing needs more than plain ids

This is a convergence decision, not an implementation claim that the migration is already complete.

The design intentionally does not require immediate removal of `:tool-defs`. Instead it reclassifies `:tool-defs` as transitional derived execution/compatibility state so later implementation tasks can migrate incrementally without losing clarity about authority.

## Remaining asymmetries that future tasks should treat explicitly

- tools still lack an authoritative session membership/selection field
- prompts still persist a derived compatibility vector in session data
- some prompt/system-prompt rebuild seams still source live tool payloads directly from session `:tool-defs`
- child-session shaping still persists derived projections alongside authority fields and should make the authority/projection split more explicit for every capability domain it touches
- workflow/session shaping rules for tools/skills/prompts are not yet expressed through one shared vocabulary of membership authority versus derived payload

## Recommended follow-on slices

This task should guide later implementation through separate focused slices rather than one umbrella refactor.

### Follow-on A: tool membership authority introduction

- add the authoritative session field for tool membership/selection
- define canonical derivation of `:tool-defs` from registry definitions plus that field
- align direct mutations so they update membership authority first and execution payload second

### Follow-on B: tool lifecycle and child-session convergence

- migrate bootstrap, new/resume/fork, and workflow child-session shaping so tool inheritance/filtering runs from tool membership/selection authority
- re-materialize `:tool-defs` only as derived payload after narrowing
- add focused proof for parent/child tool selection semantics

### Follow-on C: prompt projection cleanup boundary

- inventory every seam that still requires persisted `:prompt-contributions`
- decide whether each seam should read derived prompt contributions on demand instead of consuming the persisted vector
- only remove the vector after refresh/introspection/mutation contracts no longer depend on it

### Follow-on D: shared lifecycle vocabulary and helpers

- consolidate bootstrap/new/resume/fork/child-session shaping around shared helpers that accept authoritative membership/selection and return derived payload/projection state
- ensure every capability domain documents whether the helper output is authoritative or derived

### Follow-on E: out-of-scope registry audit

- separately evaluate adjacent registries such as `model-registry`, memory-provider registry, or other runtime catalogs to decide whether they match the membership model, the runtime-adapter model, or neither

## Constraints

- Do not force false uniformity where a domain has genuinely different lifecycle or runtime ownership semantics.
- Preserve the distinction between canonical authority, session authority, derived execution payload, and compatibility projection.
- Treat deterministic operations carefully: they may align conceptually with registry ownership while still remaining adapter- and runtime-lifecycle-shaped.
- Prefer one architectural rule plus clearly named exceptions over many local one-off rules.
- Do not silently reintroduce embedded session-owned definitions as authority once a domain has been migrated to root-backed ownership.

## Acceptance criteria

- `design.md` explains the target invariant for registry/session interaction in concrete project terms.
- `design.md` inventories current registry/session patterns and identifies remaining asymmetries.
- `design.md` explicitly resolves or frames the tool-session model question.
- `design.md` identifies which current session fields are canonical membership, derived execution payload, or compatibility-only projection.
- `design.md` states the prompt-domain lifecycle rule precisely: `:prompt-contribution-ids` is authoritative membership, while persisted `:prompt-contributions` remains required derived compatibility projection state until a later cleanup task removes that projection.
- `design.md` states whether child-session prompt inheritance/filtering is driven from parent membership ids or from the parent derived contribution vector.
- `design.md` decomposes any required implementation into recommended follow-on slices rather than mixing all changes into one vague umbrella.
- `design.md` gives future migrations a clear rule for how bootstrap and session lifecycle surfaces should interact with registry-owned definitions.
- this task leaves architectural guidance and follow-on recommendations, not same-pass creation/refinement of separate follow-on task directories.

## Likely follow-on tasks

Possible follow-ons this task may spawn or refine:

- tool session-membership unification (`:tool-defs` vs `:tool-ids` decision and migration if warranted)
- compatibility projection cleanup for registry-derived session fields
- bootstrap/lifecycle convergence for remaining registry-backed capability domains
- separate audit of out-of-scope registries such as `model-registry` or memory-provider registry if they should adopt the same pattern only partially or not at all

## Notes

This task is a follow-on to the registry semantics and migration arc:

- `164` audit and migration guidance
- `165` root-registry target architecture
- `166` root-registry component build
- `167`–`177` adopter migrations and semantic refinements

It should consolidate the next layer of architectural learning from those tasks into one coherent session/registry model before additional registry migrations or cleanup slices proceed.