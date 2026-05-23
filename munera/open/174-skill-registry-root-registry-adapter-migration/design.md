# 174 skill registry root-registry adapter migration

## Intent

Migrate `skill-registry` onto the shared `root-registry` substrate as an adapter-backed session-local projection, while preserving the public skill-registry and session behavior established by task `173`.

The goal is further registry-unification alignment: `root-registry` should own the keyed storage mechanics for skill registration, duplicate detection, lookup, and listing, while `skill-registry` continues to own skill-specific validation, result projection, session-local vector compatibility, and prompt-refresh change semantics.

## Context

Completed registry work established the current target pattern:

- task `166` introduced `root-registry` as the shared lower keyed storage substrate
- task `170` split lower registration semantics into replace-capable `register` and duplicate-rejecting `insert`
- task `171` proved that a registry can adopt shared storage while preserving adapter-owned public behavior
- task `172` removed deterministic-operation insertion-order semantics
- task `173` removed skill insertion-order semantics and replaced them with canonical exact skill-name ordering

After task `173`, `skill-registry` is closer to `root-registry` semantics:

- skill identity is exact `:name`
- visible listing is canonical by exact skill-name string order
- lookup is exact-name based
- duplicate registration is explicit first-write-wins / no-op
- `:added?` / `:changed?` remain behaviorally meaningful because session dispatch uses `:changed?` to gate prompt refresh

The remaining mismatch is mostly storage/API shape:

- `skill-registry` currently works directly over a session-local vector of skill maps
- `root-registry` works over keyed entries in a root-state map with `:id`, `:extension-id`, and `:value`
- `skill-registry` duplicate handling is public duplicate-ignore/no-change, while `root-registry/insert` reports duplicate as a lower failure

This task should bridge those shapes intentionally rather than changing user-visible skill behavior accidentally.

## Problem statement

`skill-registry` still owns bespoke keyed-collection mechanics even though those mechanics now match concepts already present in `root-registry`:

- keyed identity
- duplicate detection
- exact lookup
- list/count projections
- canonical adapter-owned ordering

Keeping these mechanics separate makes `skill-registry` look like a permanent special case in the registry-unification arc. However, moving skills directly into global root-state storage would be the wrong semantic change: skills remain session-local resources and must continue to appear as session `:skills` vectors to existing callers.

The problem is to adopt the shared lower storage mechanics without changing session ownership or the skill public contract.

## Desired outcome

`skill-registry` should become an adapter over `root-registry` mechanics for a single session-local skill collection.

The resulting design should make this split explicit:

- `root-registry` owns lower keyed storage mechanics for the in-flight skill collection transformation
- `skill-registry` owns conversion between session skill vectors and root-registry state
- `skill-registry` owns validation of skill maps and exact `:name` identity
- `skill-registry` owns public duplicate-ignore/no-change result projection
- session state remains a canonical `:skills` vector, not a long-lived global root-registry skill store

## Scope

This task includes:

- designing and implementing a `skill-registry` adapter that uses `root-registry` lower operations internally
- preserving the existing `psi.skill-registry.registry` public API
- preserving session-local vector compatibility for `:skills`
- using `root-registry/insert` as the lower duplicate-detection primitive
- translating lower duplicate results into the current public duplicate-ignore/no-change skill result
- preserving canonical exact skill-name ordering for all registry results and read helpers
- preserving `:added?` / `:changed?` semantics exactly
- preserving the task `173` behavior that duplicate/no-change registration may still canonicalize the returned/stored `:skills` vector without emitting prompt-refresh effects
- updating task `164` to record that `skill-registry` is now a root-registry-aligned adapter-backed collection, not merely a bespoke helper-only candidate

## Out of scope

This task does not include:

- moving skills into long-lived application root-state as a global registry
- changing the session `:skills` data model exposed to callers
- changing duplicate skill registration into a public error
- changing duplicate skill registration into replacement semantics
- adding skill unregister/remove behavior
- adding extension ownership or built-in ownership semantics to skills
- redesigning skill discovery collision behavior in `prompt-assets`
- changing prompt refresh rules except as needed to preserve current behavior through the adapter
- migrating `prompt-registry`

## Required public behavior to preserve

The public `skill-registry` contract must remain behaviorally equivalent:

- `valid-skill-name?` returns true only for non-blank strings
- invalid skill names throw the existing invalid-name exception shape at the skill boundary
- `all-skills` returns a vector canonicalized by exact skill `:name` JVM string ordering
- `skill-names` follows the same canonical order
- `skill-count` returns the count of unique skill names
- `find-skill` returns the skill map for exact name match or `nil` on miss
- `register-skill` returns:
  - `:skills` as canonical skill vector
  - `:skill` as the inserted skill on add, or the existing first-written skill on duplicate
  - `:added? true` and `:changed? true` for a new skill name
  - `:added? false` and `:changed? false` for a duplicate skill name
  - `:count` as unique skill count
- duplicate registration remains first-write-wins / public no-op
- duplicate/no-change registration can still return a canonicalized `:skills` vector even when `:changed? false`
- `:session/register-skill` continues to persist canonicalized vectors when needed but emits `:runtime/refresh-system-prompt` only when `:changed? true`

## Lower adapter semantics

The adapter should use `root-registry` as an internal transformation substrate rather than as a new persistent owner.

Expected adapter shape:

1. Convert the incoming skill vector to a temporary declared root-registry state for a dedicated skill registry id.
2. Store each existing skill as a root-registry entry:
   - `:id` = skill `:name`
   - `:extension-id` = a stable artificial owner such as `:session`
   - `:value` = the skill map
3. Register a new skill with `root-registry/insert`.
4. Translate lower outcomes:
   - lower `:insert` success → public add/change result
   - lower duplicate-id failure → public duplicate/no-change result with existing skill
5. Project the final root-registry entries back to a canonical skill vector sorted by exact skill `:name`.

The adapter must not expose root-registry entries or lower result maps through the public skill API.

## Design questions to resolve before implementation

1. Should the conversion from incoming skill vector to temporary root-registry state use `root-registry/insert` for each existing skill and thereby keep the first occurrence on duplicate names, or should it pre-deduplicate before insertion?

   Preferred answer: use the same lower insert semantics for existing collection loading so first occurrence wins consistently.

2. What should happen if an incoming existing vector already contains duplicate names with different skill maps?

   Preferred answer: preserve current first-write-wins behavior, canonicalize to one entry per name, and do not treat this as a public change unless registering a new name.

3. Should lower duplicate-id results ever surface to callers?

   Preferred answer: no. Lower duplicates are an implementation detail translated into the current skill duplicate-ignore result.

4. Does this require a reusable root-registry helper for vector-backed adapters?

   Preferred answer: not in this task unless duplication appears immediately. Keep the first migration local and extract a helper only after a second vector-backed adopter such as `prompt-registry` proves the common shape.

## Affected seams to audit

Implementation must audit and preserve these seams:

- `components/skill-registry/src/psi/skill_registry/registry.clj`
- `components/skill-registry/test/psi/skill_registry/registry_test.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj` for `:session/register-skill`
- `components/agent-session/test/psi/agent_session/config_compaction_test.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- prompt-assets skill helpers and tests, especially canonical prompt/display ordering
- TUI skill banner/autocomplete tests added by task `173`
- command surfaces `/skills` and `/help` tests added by task `173`
- workflow child-session selected-skill ordering tests added by task `173`
- task `164-registry-semantics-unification-audit`

## Acceptance

This task is complete when:

- `skill-registry` uses `root-registry` lower mechanics internally for keyed storage, duplicate detection, lookup/list/count projection, or an explicitly justified subset of those mechanics
- public `skill-registry` behavior is unchanged from the task `173` contract
- duplicate registration is implemented through lower duplicate detection and translated into public duplicate-ignore/no-change behavior
- session state remains a canonical vector of skill maps, not a long-lived global root-registry store
- focused tests prove add, duplicate, unsorted input canonicalization, exact lookup, count, and public result metadata
- session dispatch tests prove prompt refresh still fires only for semantic additions, not duplicate/no-change canonicalization
- higher ordered skill-list surfaces remain canonical by exact skill name
- task `164` records the new classification of `skill-registry` as a root-registry-aligned adapter-backed session collection
- full `bb test` passes before close
