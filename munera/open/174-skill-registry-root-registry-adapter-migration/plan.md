# Plan

1. Decide and document the persistent storage shape:
   - choose the session scoping model for root-registry entries
   - prefer one declared registry id `:session-skills` with session-scoped lower ids if it keeps declaration/listing simple
   - define the stable lower owner/extension id convention required by root-registry

2. Build the root-registry-backed skill adapter:
   - declare/ensure the skill registry area in root state
   - map `(session-id, skill-name)` to a root-registry entry
   - use `root-registry/insert` for `:session/register-skill`
   - translate duplicate-id lower results to the public skill no-op result
   - provide projected canonical skill vectors for reads and compatibility outputs
   - provide replace-whole-session-skill-set behavior for `:session/set-skills`

3. Migrate authoritative write seams:
   - `:session/register-skill`
   - `:session/set-skills`
   - session creation/defaults paths that seed `:skills`
   - child-session and scheduler paths that copy or set `:skills`
   - resume/hydration paths for persisted sessions with legacy `:skills`

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
   - define when it is written, read, or ignored
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
