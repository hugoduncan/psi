# 177 prompt-registry root-registry migration

## Intent

Migrate `prompt-registry` to use `root-registry` as its authoritative storage substrate now that task `176` simplified prompt contribution identity to canonical single-id ownership semantics.

This task should make `root-registry` the canonical owner of prompt contribution entries while preserving the prompt-registry public behavior that still matters above the substrate: prompt-specific canonicalization, patch/update semantics, provenance retention, deterministic prompt ordering, and caller-facing result contracts.

## Scope

In scope:

- `components/prompt-registry` storage ownership migration onto `components/root-registry`
- prompt-registry adapter design and implementation over `root-registry`
- all prompt contribution write seams:
  - register
  - update/patch
  - unregister
  - any owner-scoped cleanup path if present or required
- all prompt contribution read/projection seams:
  - lookup/list/count helpers
  - canonical sorting helpers
  - session-state prompt assembly seams
  - resolver / introspection / extension-detail projections
  - mutation result projections and test/helper seams
- focused and higher-surface migration guard tests proving legacy local prompt-contribution storage is no longer authoritative

Out of scope:

- changing the task `176` single-id contract again
- broader prompt-lifecycle redesign unrelated to storage ownership
- redesigning root-registry itself except for minimal semantic alignment proven necessary by this adopter

## Desired outcome

After this task:

- `root-registry` is the authoritative storage owner for prompt contribution entries
- `prompt-registry` becomes an adapter over shared root-registry storage rather than a standalone vector-owned store
- public prompt-registry behavior remains coherent with task `176`:
  - canonical single-id identity
  - same-owner replace
  - cross-owner ownership conflict
  - update/unregister by `id` with owner checks where relevant
  - provenance retained on stored entries
  - deterministic canonical prompt ordering
  - prompt-specific result maps preserved where callers depend on them
- no higher read/introspection/projection seam still reads legacy local prompt-contribution state as authoritative
- the migration leaves prompt-registry materially closer to the same shared-substrate model already used by command, tool, workflow, deterministic-operation, and skill registries

## Why now

Task `176` removed the largest semantic mismatch that blocked direct storage adoption:

- composite `ext-path + id` identity is gone
- cross-owner same-id coexistence is gone
- prompt contribution targeting is now canonical single-id with owner metadata

That leaves prompt-registry as a plausible root-registry adopter, though still with meaningful adapter-owned behavior around patching, timestamps, ordering, and result projection.

## Required preservation vs adaptation

### Preserve at the prompt-registry public/adapter level

The migration must preserve these prompt-specific behaviors unless caller audit proves otherwise:

- canonical contribution identity is string-coerced `id`
- same-owner duplicate registration replaces
- cross-owner duplicate registration fails with explicit ownership conflict
- update is patch-based rather than whole-entry replace
- `created-at` remains non-patchable; `updated-at` changes on mutation
- unregister miss remains the current prompt-registry miss contract unless the design explicitly proves a different caller-safe contract
- prompt-registry returns prompt-specific result maps (`:registered?`, `:replaced?`, `:updated?`, `:removed?`, `:changed?`, `:count`, etc.) where callers rely on them
- provenance fields such as `ext-path` remain queryable/projectable
- canonical ordering remains prompt-owned rather than delegated to unordered root-registry reads

### Move to shared substrate ownership

The migration should move these concerns down to `root-registry` ownership where possible:

- canonical authoritative entry storage
- single-id keyed lookup
- owner tracking / ownership conflict enforcement if root-registry semantics already fit
- unregister by canonical id
- any owner-scoped bulk cleanup needed by extension unload/reset paths

## Design questions to resolve

The refined design must make the following points unambiguous:

- Prompt contributions do not get a dedicated per-session root-registry host. The substrate is the shared top-level `:root-registries` area already used by other adopter registries, and prompt-registry should declare one stable registry id there, e.g. `:prompt-contributions` or equivalent prompt-registry-owned keyword.
- Session scoping must be represented inside that shared root-state substrate rather than by separate registry hosts. The prompt-registry adapter therefore needs one authoritative root-state shape that distinguishes the shared prompt definition store from per-session membership or copied prompt visibility state.
- The canonical stored root-registry entry should keep root-registry ownership metadata at the outer layer and preserve the current prompt contribution map as the prompt-registry-owned value payload, i.e. conceptually `{:id <string-id> :extension-id <ext-path> :value <canonical-prompt-contribution-map>}` where the value retains prompt fields such as `:section`, `:content`, `:priority`, `:enabled`, `:created-at`, and `:updated-at`.
- Prompt-registry should continue to own normalization/canonicalization of prompt values; root-registry should own only authoritative keyed storage, duplicate/ownership enforcement compatible with task `176`, and removal by canonical id.
- Prompt patch/update should remain adapter-owned over root-registry storage: lookup current stored prompt contribution by canonical id, assert owner when `ext-path` is supplied, merge the allowed patch into the prompt value, preserve non-patchable `:created-at`, advance `:updated-at`, then write back through root-registry replace-capable registration.
- Unregister miss should preserve the current prompt-registry no-op result surface unless implementation audit proves all callers are already root-registry-style failure-safe.
- Owner-scoped bulk cleanup remains in scope if reload/reset paths need it, but it must operate through prompt-registry/root-registry ownership rather than by clearing an independently authoritative session vector.
- The refined design must name every current higher projection that still reads session-local prompt-contribution vectors directly and redirect them to prompt-registry/root-registry-backed reads.
- The refined design must require seam-level guard tests that prove legacy session-local prompt-contribution vectors are no longer authoritative.

## Root-state storage topology

Task `177` adopts the same shared-substrate model used by the skill, tool, workflow, and deterministic-operation migrations:

- canonical prompt contribution definitions live under shared top-level root state `[:root-registries <prompt-registry-id> :entries-by-id <id>]`
- each canonical entry uses root-registry shape `{:id <string-id> :extension-id <ext-path> :value <canonical-prompt-contribution-map>}`
- session visibility is not encoded by a dedicated per-session registry host; it is encoded by each session's own prompt-contribution membership data, analogous to `:skill-ids`
- the likely session-owned projection is a vector of canonical prompt ids, e.g. `:prompt-contribution-ids`, while any surviving `:prompt-contributions` vector is strictly derived from those ids plus root-registry definitions

That topology makes the storage owner precise:

- `root-registry` owns prompt contribution definitions and cross-owner duplicate protection
- session data owns only which prompt ids are visible in that session
- prompt-registry owns the adapter that joins those two layers into the public prompt contribution behavior callers see

This task does not need to preserve raw copied prompt maps inside every session once canonical definitions are rooted in shared storage. If compatibility requires `:prompt-contributions` to survive temporarily, it must be rebuilt from root-registry-backed definitions selected by the session's canonical membership state rather than copied forward as authority.

## Session lifecycle copy and derive rules

The current lifecycle code still copies raw `:prompt-contributions` vectors in `session_state/init.clj` and `child_session_state.clj`. After migration, those flows must instead follow these rules:

- new root session creation copies or seeds prompt visibility from the current session using canonical session membership state (expected to be prompt ids), not by copying raw prompt maps as authority
- resumed session initialization reconstructs session prompt visibility from persisted canonical session membership state and root-registry-backed definitions; it must not treat a persisted `:prompt-contributions` vector as authoritative when a root-registry-backed representation exists
- forked session initialization copies the parent's prompt membership state and continues to resolve prompt maps from shared root-registry definitions
- child session creation copies the parent's prompt membership state by default, then derives renderable prompt contribution values from root-registry-backed definitions after any child prompt-component filtering
- reset/cleanup paths clear per-session prompt membership state and, where required, clear root-registry-owned definitions by extension ownership through prompt-registry/root-registry operations rather than by mutating only session-local vectors

Implementation can choose the exact session membership field name, but the design requires the following invariant: whenever lifecycle code needs prompt contributions for a session, the authoritative path is `session membership -> root-registry entry lookup -> canonical prompt ordering`, never `raw session-local prompt-contributions vector -> trust as source of truth`.

## Concrete seam inventory

### Write seams in scope

Task `177` must cover these concrete prompt contribution write paths because they still mutate or assume session-local `:prompt-contributions` authority today:

- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`
  - `:session/register-prompt-contribution`
  - `:session/update-prompt-contribution`
  - `:session/unregister-prompt-contribution`
  - `:session/reset-prompt-contributions`
  - `:session/refresh-system-prompt` / `:session/set-system-prompt` indirectly depend on authoritative contribution reads when rebuilding the rendered prompt.
- `components/agent-session/src/psi/agent_session/mutations/prompts.clj`
  - mutation surface remains stable, but its return contracts depend on the migrated handler/adapter result maps.
- `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj`
  - built-in workflow prompt contribution registration currently dispatches directly into the session prompt registration seam and therefore must continue to target the canonical store through that seam after migration.
- `components/agent-session/src/psi/agent_session/extension_runtime.clj`
  - reload/reset currently clears prompt contributions session-wide; this seam must either clear the per-session root-registry prompt store or route through prompt-registry bulk cleanup so no stale entries survive reload.
- child-session state creation/copy paths in:
  - `components/agent-session/src/psi/agent_session/child_session_state.clj`
  - `components/session-state/src/psi/session_state/init.clj`
  These currently copy `:prompt-contributions` vectors directly; task `177` must specify whether child/new/resume/fork session creation clones root-registry prompt entries, derives a fresh projected vector from copied registry state, or removes the vector entirely.
- nullable helper mutation/query seams in `components/extension-test-helpers/src/psi/extension_test_helpers/nullable_api.clj`
  - these currently maintain independent `:prompt-contributions` map storage and must migrate to the same single-id/root-authority semantics expected by higher tests.

### Read and projection seams in scope

Task `177` must cover these concrete read/projection seams because they still source prompt contributions from session-local vectors directly or via helpers backed by those vectors:

- `components/session-state/src/psi/session_state/state.clj`
  - `list-prompt-contributions-in` currently sorts `(:prompt-contributions session-data)` and is the main session-state prompt read helper. After migration it must read from prompt-registry/root-registry authority.
- `components/agent-session/src/psi/agent_session/prompt_request.clj`
  - request preparation uses `(:prompt-contributions session-data)` directly before filtering/sorting for `:turn/sorted-prompt-contributions`; it must switch to authoritative prompt-registry/session-state reads.
- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`
  - effective prompt rebuilds rely on `session/list-prompt-contributions-in`; once that helper is migrated the handler becomes compatible, but it is still an in-scope coherence seam.
- `components/agent-session/src/psi/agent_session/resolvers/session.clj`
  - prompt lifecycle/introspection currently projects `:psi.agent-session/prompt-contributions` and `:psi.agent-session/prompt-layers` from raw `(:prompt-contributions sd)` and must be redirected.
- `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`
  - `:psi.extension/prompt-contributions` and count currently read raw session vectors and must be redirected.
- child-session inheritance and workflow child prompt rendering paths:
  - `components/agent-session/src/psi/agent_session/child_session_state.clj`
  - `components/agent-session/src/psi/agent_session/workflow_execution_*` tests and supporting code
  These are in scope because parent prompt contribution inheritance currently depends on copied vectors.
- prompt assembly/render surfaces that are already compatible once they are fed authoritative contribution lists remain indirectly in scope but should not need semantic redesign:
  - `components/prompt-assets/src/psi/prompt_assets/system_prompt.clj`
  - `components/turn-runtime/src/psi/turn_runtime/request.clj`
  These operate on passed-in contribution collections rather than owning storage.
- extension API list/query surface in `components/agent-session/src/psi/agent_session/extensions/api.clj`
  - this is already close to compatible because it queries `:psi.extension/prompt-contributions`, but the resolver behind that query must move to authoritative reads.
- extension detail/introspection projections and prompt-related EQL/query surfaces are in scope wherever they currently depend on the session resolver outputs above.

### Higher surfaces already broadly compatible once authority moves

The following surfaces are not the authoritative storage problem themselves and mostly need only the upstream read seams fixed, not redesign:

- prompt formatting/filtering in `components/prompt-assets`
- turn-runtime request application of `:turn/sorted-prompt-contributions`
- mutation call sites and extension API callers that only depend on stable return/query contracts

## Authoritative storage boundary

Task `177` adopts this storage boundary:

- `root-registry` becomes the canonical per-session owner of prompt contribution entries.
- Session-local `:prompt-contributions` vectors must not remain independently authoritative after migration.
- If a temporary session-local `:prompt-contributions` vector survives for compatibility or projection convenience, it is a derived cache/snapshot owned by the prompt-registry/session-state adapter layer and must be rebuilt from root-registry data rather than mutated as a source of truth.
- Synchronization ownership, if a temporary cache exists, belongs to the prompt-registry/session-state adapter seam rather than to arbitrary callers or handlers.
- New-session, resumed-session, forked-session, and child-session flows must copy or derive prompt contribution state from canonical root-registry-owned entries, not by treating an old vector as authoritative.

## Required guard tests

The implementation must add tests that prove the storage boundary above:

- focused prompt-registry/root-registry contract tests for register, replace, ownership conflict, patch update, unregister miss, and deterministic ordering
- dispatch/handler tests proving prompt contribution mutations route through the canonical store and preserve caller-facing return maps
- at least one higher-surface coherence test showing a resolver or prompt-request surface reads prompt contributions from root-registry-backed authority even when any legacy session-local vector would be stale or absent
- child-session or session-initialization proof covering inherited/copied prompt contribution behavior after the authoritative-store change
- nullable helper/query proof if nullable test APIs continue to emulate prompt contribution storage

## Likely migration shape

The expected implementation shape is:

- `prompt-registry` remains the domain adapter
- `root-registry` owns canonical prompt entry storage
- prompt-registry owns:
  - contribution normalization
  - patch semantics
  - timestamp behavior
  - prompt-specific result projections
  - canonical sorting helper(s)
  - compatibility translation for any temporary lower-level seams still passing `ext-path`

This task should prefer the same migration discipline captured in task `164`:

1. name the new authoritative owner
2. enumerate all write seams
3. enumerate all read/introspection/projection seams
4. classify each seam’s post-migration source and compatibility expectations
5. add main-contract and higher-surface coherence tests
6. verify legacy local storage is no longer authoritative
7. run focused and full-suite verification before close

## Constraints

- Do not reintroduce composite identity or cross-owner same-id coexistence.
- Keep root-registry authoritative for storage after migration; do not leave split-brain ownership between session-local vectors and root-registry.
- Preserve prompt-specific behavior in the adapter rather than overfitting root-registry to prompt semantics.
- Any remaining compatibility with lower seams that still pass `ext-path` must stay ownership/provenance-only, never identity-based.
- Add at least one higher-surface coherence test so the migration cannot pass only lower-component tests while stale projection seams still read legacy prompt-contribution storage.

## Acceptance criteria

- The design names `root-registry` as the authoritative prompt-contribution storage owner and specifies the prompt registry id / stored entry mapping.
- The design specifies how prompt patch/update behavior is preserved over root-registry storage.
- The design specifies which prompt-registry public behaviors are preserved in the adapter and which concerns move to root-registry.
- The design inventories affected write and read/projection seams.
- The design requires guard tests for both the main prompt-registry contract and at least one higher read/introspection/projection seam.
- The design leaves a clear implementation path for migrating prompt-registry storage without reintroducing the identity complexity removed in task `176`.
