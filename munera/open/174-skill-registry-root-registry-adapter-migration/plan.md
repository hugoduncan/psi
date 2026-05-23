# Plan

1. Decide and document the persistent storage shape:
   - choose the session scoping model for root-registry entries
   - prefer one declared registry id `:session-skills` with session-scoped lower ids if it keeps declaration/listing simple
   - define the stable lower owner/extension id convention required by root-registry

2. Build the root-registry-backed skill adapter in `components/skill-registry`:
   - keep existing pure vector helpers for validation/canonicalization and API-level return projections only
   - add a root-state/root-registry-aware adapter namespace rather than reimplementing root-registry filtering in agent-session
   - declare/ensure the shared `:session-skills` registry area in root state
   - map `(session-id, skill-name)` to a root-registry entry
   - use `root-registry/insert` for `:session/register-skill`
   - translate duplicate-id lower results to the public skill no-op result
   - expose `all/find/names/count/register/set/hydrate` session-aware APIs
   - provide projected canonical skill vectors for reads and API return values
   - provide replace-whole-session-skill-set behavior for `:session/set-skills`
   - do not provide or retain a session `:skills` sync/projection writer except for removing legacy seeds after hydration

3. Migrate authoritative write and hydration seams:
   - `:session/register-skill` writes through the skill-registry adapter and gates prompt refresh on `:changed?`
   - `:session/set-skills` replaces the session's complete root-registry-backed set through the adapter and does not store session `:skills`
   - session creation/defaults paths hydrate input `:skills` seeds in the same root-state update that creates session data, then remove `:skills`
   - fork and child-session paths hydrate the new session id from copied/derived skill seeds in the same update, then remove `:skills`
   - scheduler-created and workflow child sessions rely on those lifecycle handlers; later explicit set events use adapter replacement semantics
   - resume paths hydrate persisted legacy `:skills` synchronously and remove them; if root-registry entries already exist, they win and the legacy vector is discarded

4. Migrate authoritative read/projection seams:
   - session and discovery resolvers
   - prompt refresh/build paths
   - prompt request exact lookup path
   - commands `/skills` and `/help`
   - TUI projection/banner/autocomplete
   - workflow child-session skill selection
   - any direct raw `(:skills sd)` read must become either an explicit pre-hydration seed read or be removed

5. Remove legacy projection storage deliberately:
   - no runtime session map should retain `:skills` after hydration/create/set/register paths complete
   - no persistence path should persist session `:skills` as a derived projection
   - compatibility is read-time API projection from root-registry, not duplicated session storage
   - add tests proving stale raw `:skills` cannot masquerade as authoritative and are removed after hydration

6. Update tests:
   - focused lower skill-storage adapter tests
   - dispatch tests for add/duplicate/set and prompt refresh gating
   - migration/hydration tests from legacy session `:skills`
   - tests proving runtime/persisted session data no longer retains `:skills` after migration paths
   - representative higher-surface canonical ordering tests from task `173`

7. Update task `164`:
   - classify `skill-registry` as a root-registry-backed session-scoped storage adopter
   - record which semantics remain adapter-owned: duplicate-ignore projection, `:added?` / `:changed?`, prompt-refresh gating, canonical skill-name ordering
   - record that legacy session `:skills` projection storage was removed rather than synchronized

8. Verify:
   - focused lower and higher tests
   - full `bb test` before close
