# Plan

1. Decide and document the persistent storage shape:
   - choose the session scoping model for root-registry entries
   - prefer one declared registry id `:session-skills` with session-scoped lower ids if it keeps declaration/listing simple
   - define the stable lower owner/extension id convention required by root-registry

2. Build the root-registry-backed skill adapter in `components/skill-registry`:
   - keep existing pure vector helpers for public collection semantics and compatibility projections
   - add a root-state/root-registry-aware adapter namespace rather than reimplementing root-registry filtering in agent-session
   - declare/ensure the shared `:session-skills` registry area in root state
   - map `(session-id, skill-name)` to a root-registry entry
   - use `root-registry/insert` for `:session/register-skill`
   - translate duplicate-id lower results to the public skill no-op result
   - expose `all/find/names/count/register/set/hydrate/sync-projection` session-aware APIs
   - provide projected canonical skill vectors for reads and compatibility outputs
   - provide replace-whole-session-skill-set behavior for `:session/set-skills`

3. Migrate authoritative write and hydration seams:
   - `:session/register-skill` writes through the skill-registry adapter and gates prompt refresh on `:changed?`
   - `:session/set-skills` replaces the session's complete root-registry-backed set through the adapter
   - session creation/defaults paths run lifecycle hydration in the same root-state update that creates session data
   - fork and child-session paths hydrate the new session id from copied/derived compatibility seeds in the same update
   - scheduler-created and workflow child sessions rely on those lifecycle handlers; later explicit set events use adapter replacement semantics
   - resume paths hydrate persisted legacy `:skills` synchronously; if root-registry entries already exist, they win and rewrite the projection

4. Migrate authoritative read/projection seams:
   - session and discovery resolvers
   - prompt refresh/build paths
   - prompt request exact lookup path
   - commands `/skills` and `/help`
   - TUI projection/banner/autocomplete
   - workflow child-session skill selection
   - any direct raw `(:skills sd)` read that is not explicitly a legacy compatibility seed

5. Preserve compatibility deliberately:
   - if session data still carries `:skills`, mark it as derived compatibility projection
   - write it from adapter projections after hydration/register/set when persistence or legacy readers need it
   - read it only as a seed when root-registry has no entries for that session
   - add tests that root-registry data wins after hydration so stale raw vectors cannot masquerade as authoritative

6. Update tests:
   - focused lower skill-storage adapter tests
   - dispatch tests for add/duplicate/set and prompt refresh gating
   - migration/hydration tests from legacy session `:skills`
   - representative higher-surface canonical ordering tests from task `173`

7. Update task `164`:
   - classify `skill-registry` as a root-registry-backed session-scoped storage adopter
   - record which semantics remain adapter-owned: duplicate-ignore projection, `:added?` / `:changed?`, prompt-refresh gating, canonical skill-name ordering

8. Verify:
   - focused lower and higher tests
   - full `bb test` before close
