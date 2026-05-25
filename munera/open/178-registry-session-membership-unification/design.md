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

## Key design question

The main unresolved architectural question is whether tools should converge toward the same session-membership model as skills and prompts.

In particular, this task should decide whether the long-term model should move from:

- session-owned `:tool-defs` as effective payload

toward something more like:

- registry-owned canonical tool definitions
- session-owned `:tool-ids` or equivalent membership/selection
- derived `:tool-defs` compatibility/execution projection

If the answer is yes, the design should describe the migration shape and affected seams. If the answer is no, the design should explain why tools remain a principled exception rather than an accidental asymmetry.

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
