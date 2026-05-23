# Plan

1. Decide and document the persistent storage split:
   - use root-registry for canonical skill definitions
   - use session data for authoritative membership references, expected as `:skill-ids`
   - define the lower owner/extension id convention for skill definitions in root-registry

2. Build the root-registry-backed definition + membership adapter in `components/skill-registry`:
   - keep existing pure vector helpers for validation/canonicalization and API-level return projections only
   - add a root-state/root-registry-aware adapter namespace rather than reimplementing registry lookup or session membership logic in agent-session
   - declare/ensure the shared `:skills` registry area in root state
   - use skill `:name` as the canonical root-registry definition id
   - use `root-registry/insert` for definition registration
   - expose `all/find/names/count/register/set/hydrate/skill-ids` session-aware APIs
   - resolve session `:skill-ids` to projected canonical skill vectors for reads and API return values
   - provide replace-whole-session-membership behavior for `:session/set-skills`
   - do not provide or retain a session `:skills` sync/projection writer; compatibility is one-way hydration from legacy embedded vectors only

3. Migrate authoritative write and hydration seams:
   - `:session/register-skill` ensures the definition in root-registry, appends the skill id to session `:skill-ids` when absent, and gates prompt refresh on `:changed?`
   - `:session/set-skills` replaces the session's complete `:skill-ids` set through the adapter and does not store session `:skills`
   - session creation/defaults paths hydrate embedded input `:skills` seeds into root-registry definitions plus `:skill-ids`, then remove `:skills`
   - fork and child-session paths copy/filter authoritative parent `:skill-ids` rather than embedded skill maps
   - scheduler-created and workflow child sessions rely on those lifecycle handlers; later explicit set events use membership replacement semantics
   - resume paths hydrate persisted legacy embedded `:skills` into definitions plus `:skill-ids`, then remove `:skills`

4. Migrate authoritative read/projection seams:
   - session and discovery resolvers
   - prompt refresh/build paths
   - prompt request exact lookup path
   - commands `/skills` and `/help`
   - TUI projection/banner/autocomplete
   - workflow child-session skill selection
   - any direct raw `(:skills sd)` read must become either explicit pre-hydration seed handling or a `:skill-ids` + registry lookup path

5. Update session schema/model deliberately:
   - add canonical `:skill-ids` to session schema/model
   - remove runtime/persisted `:skills` as an embedded authoritative field after migration
   - if needed, distinguish legacy input acceptance from canonical runtime session data
   - add tests proving stale raw `:skills` cannot masquerade as authoritative and that `:skill-ids` owns membership after hydration

6. Update tests:
   - focused lower definition-storage + membership adapter tests
   - dispatch tests for add/duplicate/set and prompt refresh gating
   - migration/hydration tests from legacy embedded `:skills`
   - tests proving runtime/persisted session data uses `:skill-ids` and no longer retains embedded `:skills`
   - representative higher-surface canonical ordering tests from task `173`
   - child-session inheritance tests proving parent skill ids drive inheritance

7. Update task `164`:
   - classify `skill-registry` as a root-registry-backed definition owner with session-owned skill-id membership
   - record which semantics remain adapter-owned: duplicate-ignore projection, `:added?` / `:changed?`, prompt-refresh gating, canonical skill-name ordering
   - record that embedded session `:skills` storage was removed in favor of `:skill-ids`

8. Verify:
   - focused lower and higher tests
   - full `bb test` before close
