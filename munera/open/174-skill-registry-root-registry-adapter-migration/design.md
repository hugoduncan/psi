# 174 skill registry root-registry storage migration

## Intent

Move canonical skill definitions onto the shared `root-registry` substrate while making sessions own skill membership by id/reference rather than by embedded skill maps, preserving the public skill behavior established by task `173`.

The goal is a real storage and ownership migration: `root-registry` should become the authoritative owner of skill definitions, while session data should become the authoritative owner of which skill ids a session includes. The migration must remove legacy session `:skills` projection storage. `skill-registry` and agent-session code should become adapters/readers over root-registry-backed skill definitions plus session-owned `:skill-ids`, preserving skill-specific validation, public result projection, API response shapes, and prompt-refresh change semantics.

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

The remaining mismatch is storage/API shape and ownership model:

- `skill-registry` currently works directly over a session-local vector of skill maps
- `root-registry` works over keyed entries in root state with `:id`, `:extension-id`, and `:value`
- session data currently stores embedded `:skills`, but should instead own a list of included skill references such as `:skill-ids`
- many seams still read session `:skills` directly
- `skill-registry` duplicate handling is public duplicate-ignore/no-change, while `root-registry/insert` reports duplicate as a lower failure

This task should move authoritative skill definitions to `root-registry`, move session membership to session-owned skill ids, and keep user-visible skill behavior stable through adapter translation and read-time projection rather than persisted embedded skill maps.

## Problem statement

`skill-registry` still owns bespoke keyed-collection mechanics even though those mechanics now match concepts already present in `root-registry`:

- keyed identity
- duplicate detection
- exact lookup
- list/count projections
- canonical adapter-owned ordering

Keeping these mechanics separate makes `skill-registry` a permanent special case in the registry-unification arc. The cleaner split is: registry owns skill definitions, session owns membership. Sessions should not own embedded skill maps, and skill definitions should not need to be duplicated per session.

The problem is to move authoritative skill definitions to `root-registry`, replace embedded session `:skills` with session-owned `:skill-ids`, and preserve the public skill contract and higher prompt/workflow behavior.

## Desired outcome

`skill-registry` should become an adapter over canonical root-registry-backed skill definitions, while sessions own included skill ids by reference.

The resulting design should make this split explicit:

- `root-registry` owns canonical keyed skill definitions in root state
- session data owns included skill membership as `:skill-ids` (or an equivalent canonical id-reference field)
- `skill-registry` owns validation of skill maps and exact `:name` identity
- `skill-registry` owns public duplicate-ignore/no-change result projection over lower duplicate-detection results
- session-facing read surfaces resolve `:skill-ids` through root-registry-backed skill definitions and project canonical skill vectors on demand
- session `:skills` vector storage is removed from runtime/persisted session data; no legacy hydration path is required for this task

## Scope

This task includes:

- designing and implementing a `skill-registry` adapter over persistent root-registry-backed skill definitions
- deciding the canonical session membership field shape, expected to be `:skill-ids`
- preserving the existing `psi.skill-registry.registry` public API where it remains useful for vector-level projections and compatibility tests, or replacing its authoritative use sites with root-state/session-aware adapter APIs
- migrating `:session/register-skill` to register or ensure skill definitions in root-registry and append/retain membership in session `:skill-ids`
- migrating `:session/set-skills` to replace the session's `:skill-ids` membership from supplied skill maps via registry-backed id resolution/registration
- migrating session/resolver/prompt/TUI/command/workflow read seams to resolve projected canonical skills from session `:skill-ids` plus root-registry-backed definitions rather than raw session `:skills`
- removing session-local `:skills` runtime/persisted projection storage and replacing it directly with `:skill-ids`
- using `root-registry/insert` as the lower duplicate-detection primitive for skill definitions
- translating lower duplicate results into the current public duplicate-ignore/no-change skill result
- preserving canonical exact skill-name ordering for all projected registry results and read helpers
- preserving `:added?` / `:changed?` semantics exactly
- preserving the task `173` behavior that duplicate/no-change registration may return canonical projected skill vectors without emitting prompt-refresh effects
- updating task `164` to record that `skill-registry` is now a root-registry-backed definition owner with session-owned skill-id membership

## Out of scope

This task does not include:

- making session skill membership implicit/derived rather than session-owned
- changing duplicate skill registration into a public error
- changing duplicate skill registration into replacement semantics
- adding skill unregister/remove behavior beyond what is strictly needed for replacing session membership in `:session/set-skills`
- redesigning skill discovery collision behavior in `prompt-assets`
- changing prompt refresh rules except as needed to preserve current behavior through the adapter
- migrating `prompt-registry`

## Required public behavior to preserve

The public `skill-registry` contract must remain behaviorally equivalent at the session-facing API level:

- `valid-skill-name?` returns true only for non-blank strings
- invalid skill names throw the existing invalid-name exception shape at the skill boundary
- projected session skills resolve to vectors canonicalized by exact skill `:name` JVM string ordering
- projected session `skill-names` follow the same canonical order
- projected session `skill-count` returns the count of included unique skill ids
- exact skill lookup by name/id returns the projected skill map or `nil` on miss
- `register-skill`-style session operations return:
  - projected `:skills` as canonical skill vector
  - `:skill` as the inserted skill on add, or the existing first-written skill on duplicate
  - `:added? true` and `:changed? true` for a newly included skill id
  - `:added? false` and `:changed? false` when the session already includes that skill id
  - `:count` as unique included skill count
- duplicate registration remains first-write-wins / public no-op
- duplicate/no-change registration can still return a canonicalized projected `:skills` vector even when `:changed? false`
- `:session/register-skill` persists root-registry-backed skill definitions plus session `:skill-ids`, returns canonical vector data as API result when required, and emits `:runtime/refresh-system-prompt` only when `:changed? true`

## Root-registry definition storage and session membership semantics

The adapter should use `root-registry` as persistent canonical storage for skill definitions, while session data persists membership by skill id.

Expected storage shape:

1. Declare a shared skill-definition registry area in root state, likely `:skills`.
2. Store each skill definition as a root-registry entry:
   - lower `:id` = skill `:name`
   - lower `:extension-id` / owner = a stable owner convention appropriate for skill definitions (to be decided explicitly)
   - lower `:value` = the skill map
3. Store session membership as `:skill-ids` in session data.
4. Register a new skill for a session by:
   - inserting/ensuring the definition in root-registry with `root-registry/insert`
   - adding the skill id to session `:skill-ids` if absent
5. Translate lower outcomes:
   - lower `:insert` success with new membership → public add/change result
   - lower duplicate-id failure with absent membership → resolve existing definition and add membership
   - already-present session membership → public duplicate/no-change result
6. Project session `:skill-ids` through root-registry definitions back to canonical skill vectors sorted by exact skill `:name` for all session-facing read surfaces.

The adapter must not expose root-registry entries or lower result maps through public skill APIs or EQL projections.
## Design questions to resolve before implementation

1. What is the root-registry definition storage shape?

   Preferred answer to evaluate: use one declared registry id such as `:skills` keyed globally by skill name. This fits the user's direction that the registry owns definitions while sessions own included ids.

2. What is the canonical session membership field shape?

   Preferred answer: `:skill-ids` as a vector of skill ids/names. The field should represent explicit session ownership of included skills, while projected `:skills` vectors disappear from stored session data.

3. Should this task support a legacy embedded-`:skills` hydration path?

   Preferred answer: no. The task should change canonical session shape directly to `:skill-ids` and update creation/copy/set paths accordingly, without carrying a compatibility hydration path for embedded `:skills`.

4. Should `:session/set-skills` replace the whole session membership set or insert only missing skills?

   Preferred answer: preserve current set-style semantics by replacing the session's `:skill-ids` from the supplied skill maps, while `:session/register-skill` remains insert/no-op membership addition.

5. Should lower duplicate-id results ever surface to callers?

   Preferred answer: no. Lower duplicates are an implementation detail translated into the current skill duplicate-ignore result.

6. Does this require extending `root-registry` for owner/listing semantics?

   Preferred answer: probably less than the previous design, because registry listing is global by skill definition id while session ownership lives in `:skill-ids`. Add lower helpers only if definition registration/lookup would otherwise duplicate brittle logic.


## Adapter boundary and APIs

The root-registry-backed skill adapter lives in `components/skill-registry`. That component should gain a dependency on `psi/root-registry` and expose root-state/root-registry-aware APIs alongside the existing pure vector helpers.

Boundary decision:

- `psi.skill-registry.registry` keeps the existing pure vector API for validation, canonicalization, API-level return projections, and tests that intentionally exercise the public collection contract. It must not be used as persisted/runtime session storage after migration.
- The same component, preferably in a new namespace such as `psi.skill-registry.root-storage`, owns the root-registry/session-membership adapter because it is still skill-domain behavior: skill validation, exact `:name` identity, duplicate-ignore projection, canonical skill-name ordering, and public result metadata.
- `agent-session` must call that adapter for session-aware root-state writes and reads; it should not reimplement root-registry lookup, definition registration, membership updates, or canonical skill projection locally.
- `session-state` remains a lower pure session-map initializer and does not take a root-registry dependency. Its canonical session shape for this task should use `:skill-ids` rather than embedded `:skills`.
- Prompt, resolver, TUI, command, and workflow seams should depend on agent-session/session-facing helper functions or the skill-registry adapter result, not on raw root-registry entries.

Adapter API shape to implement:

- `ensure-skill-registry` / `ensure-skill-registry-in` declares the shared `:skills` root-registry area idempotently.
- session-aware APIs should operate directly on root-registry definitions plus session `:skill-ids`; no legacy `hydrate-session-skills` compatibility path is required.
- `all-skills-in`, `find-skill-in`, `skill-names-in`, and `skill-count-in` resolve one session's canonical projected skills from session `:skill-ids` plus root-registry-backed definitions.
- `register-skill-in` inserts/ensures one skill definition using `root-registry/insert`, adds the skill id to session membership when absent, and translates lower duplicate failures into the public duplicate/no-change result.
- `set-skills-in` replaces the complete session `:skill-ids` membership from supplied skill maps and returns the canonical API projection without storing projected `:skills` in session data.
- `skill-ids-in` should expose the session-owned membership seam explicitly for child-session inheritance and other session-local capability logic.
- no `sync-session-skills-projection` or legacy hydration compatibility path should exist in the final design.

These APIs should return root-state update results or pure root-state transforms, not perform effects. Prompt refresh remains owned by `agent-session` dispatch handlers, gated only by the adapter's public `:changed?` result.
## Session lifecycle and membership ownership

Session lifecycle handlers should create and propagate canonical `:skill-ids` directly.

Rules:

- New top-level session creation (`:session/new-initialize`, `:session/create-top-level`) creates canonical session data with `:skill-ids`, not embedded `:skills`.
- Resume paths should load canonical session data with `:skill-ids`; supporting persisted embedded `:skills` compatibility is out of scope for this task.
- Fork (`:session/fork-initialize`) copies the parent's `:skill-ids` membership into the new child session data; parent and child share root-registry definitions by id rather than duplicating stored skill maps.
- Child session creation (`:session/create-child`) derives the child selected skill ids from the parent session membership and selected/filtering logic, writes those ids to the child session data in the same update, and stores no child `:skills` projection.
- Scheduler-created sessions and workflow child sessions use the same lifecycle handlers above; any `:session/set-skills` event they emit after creation is authoritative replacement of session `:skill-ids` through the adapter.
- Bootstrap or context construction paths that supply skill maps should hydrate the root skill-definition registry directly before sessions are created, so later session creation only writes canonical `:skill-ids` membership and never acts as the bootstrap path for definitions.

This keeps lifecycle behavior deterministic, synchronous, and replayable: every lifecycle handler's root-state transform leaves canonical root-registry definitions and session membership populated, with no embedded `:skills` projection.
## Affected seams to audit

Implementation must audit and preserve these seams:

- `components/skill-registry/src/psi/skill_registry/registry.clj`
- new root-storage/session-membership adapter namespace under `components/skill-registry/src/psi/skill_registry/`
- `components/skill-registry/test/psi/skill_registry/registry_test.clj`
- session schema/model paths for introducing `:skill-ids` and removing embedded `:skills`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj` for `:session/register-skill` and `:session/set-skills`
- bootstrap/root-runtime initialization paths that currently load skill maps before sessions exist
- session lifecycle/session creation/child-session/scheduler paths that seed or copy skill membership
- session persistence/resume paths that must use canonical `:skill-ids`
- `components/agent-session/test/psi/agent_session/config_compaction_test.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/resolvers/session.clj` for `:psi.agent-session/skills` and any new `:psi.agent-session/skill-ids` surface if exposed
- prompt building paths that currently read raw `(:skills sd)`
- prompt-assets skill helpers and tests, especially canonical prompt/display ordering
- TUI skill banner/autocomplete tests added by task `173`
- command surfaces `/skills` and `/help` tests added by task `173`
- workflow child-session selected-skill ordering tests added by task `173`
- task `164-registry-semantics-unification-audit`

## Acceptance

This task is complete when:

- root-registry is the authoritative storage owner for skill definitions
- session data is the authoritative owner of included skill membership via `:skill-ids` or the explicitly chosen equivalent reference field
- public `skill-registry` and agent-session skill behavior is unchanged from the task `173` contract
- duplicate registration is implemented through lower duplicate detection and translated into public duplicate-ignore/no-change behavior at the session-membership boundary
- session `:skills` is removed from runtime/persisted session data; no legacy embedded skill-map projection storage remains
- focused tests prove add, set/replace, duplicate, unsorted input canonicalization, exact lookup, count, membership replacement, and public result metadata through the root-registry-plus-`skill-ids` path
- session dispatch tests prove prompt refresh still fires only for semantic additions to session membership, not duplicate/no-change canonicalization
- bootstrap/root-runtime initialization hydrates root-registry skill definitions directly before sessions exist
- session creation/resume/child/scheduler paths use canonical `:skill-ids` directly or normalize supplied already-registered skill maps into `:skill-ids`
- child-session inheritance uses parent session skill ids, not embedded parent skill maps
- higher ordered skill-list surfaces remain canonical by exact skill name and no longer read raw session `:skills`
- task `164` records the new classification of `skill-registry` as a root-registry-backed definition owner with session-owned skill-id membership
- full `bb test` passes before close
