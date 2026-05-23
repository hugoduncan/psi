# 174 skill registry root-registry storage migration

## Intent

Move canonical skill storage onto the shared `root-registry` substrate while preserving the public skill behavior established by task `173`.

The goal is a real storage migration, not only an internal helper alignment: `root-registry` should become the authoritative owner of registered session skills. `skill-registry` and agent-session code should become adapters/projections over that storage, preserving skill-specific validation, public result projection, session compatibility where required, and prompt-refresh change semantics.

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

The remaining mismatch is storage/API shape:

- `skill-registry` currently works directly over a session-local vector of skill maps
- `root-registry` works over keyed entries in root state with `:id`, `:extension-id`, and `:value`
- session data currently stores `:skills` directly, and many seams still read that vector
- `skill-registry` duplicate handling is public duplicate-ignore/no-change, while `root-registry/insert` reports duplicate as a lower failure

This task should move the authoritative storage to `root-registry` intentionally while keeping user-visible skill behavior stable through compatibility projections and adapter translation.

## Problem statement

`skill-registry` still owns bespoke keyed-collection mechanics even though those mechanics now match concepts already present in `root-registry`:

- keyed identity
- duplicate detection
- exact lookup
- list/count projections
- canonical adapter-owned ordering

Keeping these mechanics separate makes `skill-registry` a permanent special case in the registry-unification arc. Skills are session-scoped resources, but session-scoped does not have to mean vector-owned: canonical storage can live under `root-registry` with a session-derived owner/registry identity, while session-facing APIs keep projecting canonical skill vectors.

The problem is to move authoritative skill storage to `root-registry` without changing the skill public contract or breaking existing session/prompt/workflow callers that consume projected skill vectors.

## Desired outcome

`skill-registry` should become an adapter over canonical root-registry-backed skill storage.

The resulting design should make this split explicit:

- `root-registry` owns canonical keyed skill entries in root state
- skill entries are scoped to a session, either by a dedicated per-session registry id or by a stable owner/id namespace that prevents cross-session collisions
- `skill-registry` owns validation of skill maps and exact `:name` identity
- `skill-registry` owns public duplicate-ignore/no-change result projection over lower duplicate-detection results
- session-facing read surfaces project canonical skill vectors from root-registry storage
- session `:skills` vector storage becomes a compatibility/projection surface rather than the authoritative source, and should be removed or kept synchronized only where required by persistence/backward compatibility

## Scope

This task includes:

- designing and implementing a `skill-registry` adapter over persistent root-registry-backed storage
- deciding the session scoping model for root-registry skill entries
- preserving the existing `psi.skill-registry.registry` public API where it remains used as a vector helper, or replacing its authoritative use sites with root-state/session-aware adapter APIs
- migrating `:session/register-skill` and `:session/set-skills` to write canonical root-registry skill storage
- migrating session/resolver/prompt/TUI/command/workflow read seams to read projected canonical skills from root-registry-backed storage rather than raw session `:skills`
- preserving session-local vector compatibility for callers that still require `:skills` in session data, while classifying it as derived/compatibility rather than authoritative
- using `root-registry/insert` as the lower duplicate-detection primitive
- translating lower duplicate results into the current public duplicate-ignore/no-change skill result
- preserving canonical exact skill-name ordering for all registry results and read helpers
- preserving `:added?` / `:changed?` semantics exactly
- preserving the task `173` behavior that duplicate/no-change registration may still canonicalize projected `:skills` vectors without emitting prompt-refresh effects
- updating task `164` to record that `skill-registry` is now a root-registry-backed session-scoped storage adopter

## Out of scope

This task does not include:

- making skills global across sessions
- changing duplicate skill registration into a public error
- changing duplicate skill registration into replacement semantics
- adding skill unregister/remove behavior unless strictly required for replacing `:session/set-skills` semantics
- adding extension ownership or built-in ownership semantics to skills beyond the session-scoping owner/registry identity needed by root-registry
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

## Root-registry storage semantics

The adapter should use `root-registry` as persistent canonical storage for session skills.

Expected storage shape:

1. Declare a skill registry area in root state.
2. Scope entries so skill names collide only within a session. Candidate shapes:
   - per-session registry id such as `[:session-skills session-id]`
   - one registry id such as `:session-skills` with entry ids namespaced by session id, e.g. `[session-id skill-name]`
3. Store each skill as a root-registry entry:
   - skill identity within the session = skill `:name`
   - lower `:id` = the chosen session-scoped skill id
   - lower `:extension-id` / owner = a stable session owner, if required by the selected shape
   - lower `:value` = the skill map
4. Register new skills with `root-registry/insert`.
5. Translate lower outcomes:
   - lower `:insert` success → public add/change result
   - lower duplicate-id failure → public duplicate/no-change result with existing skill
6. Project root-registry entries back to canonical skill vectors sorted by exact skill `:name` for all session-facing read surfaces.

The adapter must not expose root-registry entries or lower result maps through public skill APIs or EQL projections.

## Design questions to resolve before implementation

1. What is the root-registry session scoping shape?

   Preferred answer to evaluate: use one declared registry id `:session-skills` and lower ids that include both session id and skill name. This avoids dynamically declaring one registry per session while still preserving per-session uniqueness.

2. How should existing session `:skills` vectors be migrated into root-registry storage?

   Preferred answer: provide an explicit hydration/migration step for session creation, session resume, child-session creation, scheduler-created sessions, and compatibility paths that still seed `:skills`. Hydration should use lower insert semantics so first occurrence wins and vectors become canonical projections.

3. What should happen if a session data map and root-registry storage both contain skills for a session?

   Preferred answer: root-registry is authoritative after hydration. Session `:skills` should be treated as a compatibility seed only when no root-registry skill entries exist for that session, or according to an explicit one-way migration rule recorded by this task.

4. Should `:session/set-skills` replace the whole root-registry skill set for the session or insert only missing skills?

   Preferred answer: preserve current set-style semantics by replacing the session's root-registry-backed skill set with the supplied canonical vector, while `:session/register-skill` remains insert/no-op.

5. Should lower duplicate-id results ever surface to callers?

   Preferred answer: no. Lower duplicates are an implementation detail translated into the current skill duplicate-ignore result.

6. Does this require extending `root-registry` for owner/session-scoped listing?

   Preferred answer: use existing list/filter or owner-scoped cleanup if adequate. Add lower helpers only if the seam would otherwise duplicate brittle filtering logic.

## Affected seams to audit

Implementation must audit and preserve these seams:

- `components/skill-registry/src/psi/skill_registry/registry.clj`
- `components/skill-registry/test/psi/skill_registry/registry_test.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj` for `:session/register-skill` and `:session/set-skills`
- session lifecycle/session creation/child-session/scheduler paths that seed or copy `:skills`
- session persistence/resume paths that load older session `:skills` vectors
- `components/agent-session/test/psi/agent_session/config_compaction_test.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/resolvers/session.clj` for `:psi.agent-session/skills`
- prompt building paths that currently read raw `(:skills sd)`
- prompt-assets skill helpers and tests, especially canonical prompt/display ordering
- TUI skill banner/autocomplete tests added by task `173`
- command surfaces `/skills` and `/help` tests added by task `173`
- workflow child-session selected-skill ordering tests added by task `173`
- task `164-registry-semantics-unification-audit`

## Acceptance

This task is complete when:

- root-registry is the authoritative storage owner for registered session skills
- public `skill-registry` and agent-session skill behavior is unchanged from the task `173` contract
- duplicate registration is implemented through lower duplicate detection and translated into public duplicate-ignore/no-change behavior
- session `:skills` is either removed from authoritative reads or explicitly maintained as a derived compatibility projection with a tested one-way synchronization rule
- focused tests prove add, set/replace, duplicate, unsorted input canonicalization, exact lookup, count, and public result metadata through the root-registry-backed path
- session dispatch tests prove prompt refresh still fires only for semantic additions, not duplicate/no-change canonicalization
- session creation/resume/child/scheduler paths that seed skills hydrate root-registry-backed storage or are explicitly updated to use the new authoritative seam
- higher ordered skill-list surfaces remain canonical by exact skill name and no longer read stale raw `:skills` where root-registry data is authoritative
- task `164` records the new classification of `skill-registry` as a root-registry-backed session-scoped storage adopter
- full `bb test` passes before close
