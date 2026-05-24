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
   - expose `all/find/names/count/register/set/skill-ids` session-aware APIs
   - resolve session `:skill-ids` to projected canonical skill vectors for reads and API return values
   - provide replace-whole-session-membership behavior for `:session/set-skills`
   - do not provide or retain a session `:skills` sync/projection writer or a legacy hydration compatibility path

3. Separate bootstrap definition loading from session membership:
   - bootstrap/root-runtime initialization should load or register skill definitions directly into root-registry before sessions exist
   - this bootstrap path must not go through a session-owned membership API
   - session creation/defaults then consume already-registered skills by writing `:skill-ids`

4. Migrate authoritative write and session-lifecycle seams:
   - `:session/register-skill` ensures the definition in root-registry, appends the skill id to session `:skill-ids` when absent, and gates prompt refresh on `:changed?`
   - `:session/set-skills` replaces the session's complete `:skill-ids` set through the adapter and does not store session `:skills`; if supplied concrete skill maps whose definitions are missing, it must register them synchronously in the same root-state update before persisting membership ids
   - session creation/defaults paths write canonical `:skill-ids`; bootstrap/default startup skill loading should pre-register definitions, but non-bootstrap creation paths supplied concrete skill maps must also normalize them synchronously in the same root-state update rather than reject or require a follow-up dispatch/effect
   - fork and child-session paths copy/filter authoritative parent `:skill-ids` rather than embedded skill maps when selecting from already-registered parent membership; child-session or scheduler inputs that carry concrete skill maps must first ensure missing definitions in the same update
   - scheduler-created and workflow child sessions rely on those lifecycle handlers; later explicit set events use membership replacement semantics
   - resume paths are assumed to use canonical `:skill-ids`, not legacy embedded `:skills`

5. Migrate authoritative read/projection seams:
   - session and discovery resolvers
   - prompt refresh/build paths
   - prompt request exact lookup path
   - commands `/skills` and `/help`
   - TUI projection/banner/autocomplete
   - workflow child-session skill selection
   - any direct raw `(:skills sd)` read must be removed or replaced with a `:skill-ids` + registry lookup path

6. Update session schema/model deliberately:
   - add canonical `:skill-ids` to session schema/model
   - remove runtime/persisted `:skills` as an embedded authoritative field
   - no legacy embedded-`:skills` hydration path is required for this task
   - add tests proving embedded `:skills` is absent from canonical runtime/persisted session data and that `:skill-ids` owns membership

7. Update tests:
   - focused lower definition-storage + membership adapter tests
   - focused bootstrap tests proving skill definitions load into root-registry before sessions exist
   - dispatch tests for add/duplicate/set and prompt refresh gating
   - tests proving runtime/persisted session data uses `:skill-ids` and no longer retains embedded `:skills`
   - representative higher-surface canonical ordering tests from task `173`
   - child-session inheritance tests proving parent skill ids drive inheritance

8. Update task `164`:
   - classify `skill-registry` as a root-registry-backed definition owner with session-owned skill-id membership
   - record which semantics remain adapter-owned: duplicate-ignore projection, `:added?` / `:changed?`, prompt-refresh gating, canonical skill-name ordering
   - record that embedded session `:skills` storage was removed in favor of `:skill-ids`, with no legacy hydration compatibility retained
   - record that bootstrap hydrates definition storage directly rather than via session membership

9. Verify:
   - focused lower and higher tests
   - full `bb test` before close
