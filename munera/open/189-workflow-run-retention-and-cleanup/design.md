# 189 workflow run retention and cleanup

## Intent

Add automatic cleanup for completed workflow runs and the sessions they create, so each originating agent session retains only a configurable number of completed workflow runs. The default retention is 1 completed workflow run per originating session.

## Problem

Workflow runs currently accumulate after they finish. Their associated workflow sessions also remain available indefinitely. Over time this creates avoidable state growth, makes introspection noisier, and leaves old workflow-owned sessions hanging around even when only the most recent completed run is typically useful.

The system needs an automatic retention rule that applies when workflow runs reach a terminal retained state.

## Scope

### In scope

- Add a configurable completed-workflow-run retention count.
- Apply retention per originating agent session.
- Default retention to `1` completed workflow run per originating session when no explicit value is configured.
- Trigger cleanup automatically when a workflow run enters a retained terminal state.
- Keep the newest retained completed workflow runs for that originating session and remove older completed workflow runs beyond the retention limit.
- Remove the workflow-owned session trees associated with removed workflow runs.
- Ensure the cleanup only affects retained terminal workflow runs (`:completed`, `:failed`, `:cancelled`); active or otherwise non-terminal runs must not be removed.
- Ensure cleanup is deterministic: when more than the retained count of completed runs exist for an originating session, the kept set is the most recently completed runs, with older completed runs removed first.
- Align workflow-run read/introspection projections with the authoritative linked-session cleanup target set by exposing a canonical linked workflow-owned session id projection, not only execution-session ids.
- Cover the behavior with focused tests.

### Out of scope

- Changing retention for non-workflow agent sessions.
- Adding manual user-facing cleanup commands in this slice.
- Broad persistence compaction beyond workflow-run and workflow-session removal.
- Changing workflow execution semantics other than post-completion retention cleanup.

## Desired outcome

After this task:

- Each originating agent session has an effective retained-terminal-workflow-run retention count.
- If no explicit retention count is configured, the effective count is `1`.
- When a workflow run enters a retained terminal status, the system keeps only the newest retained terminal runs for that originating session.
- Older retained terminal workflow runs beyond the retention limit are removed automatically.
- Workflow-owned session trees linked only to removed workflow runs are also removed automatically as part of the same cleanup behavior.
- Workflow-run read/introspection surfaces expose a canonical linked-session projection matching the cleanup contract's deduplicated execution-plus-judge linked root set, while the existing execution-only projection may remain as a narrower compatibility/detail surface if still useful.
- Non-terminal workflow runs are never removed by this cleanup, and terminal statuses outside the runtime workflow-run status set are out of scope.

## Definitions

- **Originating agent session**: the parent session from which a workflow run was started.
- **Retained terminal workflow run**: a workflow run whose status is one of the canonical workflow runtime terminal statuses covered by this task: `:completed`, `:failed`, or `:cancelled`. These are terminal within the workflow runtime and are the only statuses eligible for retention cleanup in this slice.
- **Workflow-owned session**: a runtime session created by workflow execution and marked `:workflow-owned? true`, including execution child sessions referenced from step attempts via `:execution-session-id` and judge child sessions referenced via `:judge-session-id`.
- **Linked workflow-owned session ids**: the deduplicated union of non-nil attempt `:execution-session-id` values and non-nil attempt `:judge-session-id` values recorded on one workflow run. This is the canonical read and cleanup target set for this task.
- **Retention count**: the number of retained terminal workflow runs to keep for one originating agent session.

## Behavior

### Retention policy

- Retention is grouped by originating agent session.
- This task treats the canonical workflow runtime terminal statuses `:completed`, `:failed`, and `:cancelled` as the retained status set.
- The system evaluates retention when a workflow run transitions into one of those retained terminal statuses.
- Retention ordering uses terminal transition time: the retained terminal runs for that originating session are ordered from newest terminal transition to oldest terminal transition.
- If multiple retained terminal runs for the same originating session share the same terminal transition time (`:finished-at`), the authoritative tie-breaker is canonical workflow run creation order from `[:workflows :run-order]`, with the later-created run treated as newer and retained ahead of earlier-created runs.
- The system keeps the first `N` retained terminal runs in that ordering, where `N` is the effective retention count for the originating agent session.
- Any older retained terminal runs beyond `N` are removed.
- Non-terminal runs (`:pending`, `:running`, `:blocked`) are never candidates for this cleanup.
- The default effective retention count is `1`.

### Retention configuration

- The authoritative configuration surface is the agent-session runtime context config map at `[:config :completed-workflow-run-retention-count]`.
- This setting is global to one runtime context, not a per-session persisted attribute in this slice. All originating sessions inside the same runtime context therefore read the same configured count unless a future task introduces a narrower ownership surface.
- When the config key is absent, the effective retention count defaults to `1`.
- A configured value of `0` is allowed and means no retained terminal workflow runs remain after cleanup is applied.
- Negative retention counts are invalid and must be rejected at the configuration boundary before cleanup runs.
- Cleanup reads the effective retention count at the moment a workflow run transitions into a retained terminal status; the count is not snapshotted earlier at run creation time. Existing older retained terminal runs are compared against the current effective count when each new retained terminal transition occurs.

### Session cleanup

- When a retained terminal workflow run is removed by retention cleanup, cleanup first computes that run's authoritative workflow-owned root session set from the workflow run data itself.
- The authoritative per-run linked session set is the union of all non-nil `:execution-session-id` values and all non-nil `:judge-session-id` values recorded across that run's step attempts.
- Cleanup must ignore duplicate ids in that set and must not infer ownership from unrelated session ancestry alone.
- For each linked workflow-owned session in that set, cleanup removes the whole workflow-owned subtree rooted at that linked session by using session-tree close semantics, not single-session-only close semantics.
- Whole-tree cleanup is required because a workflow-owned execution or judge session may itself have workflow-owned descendants; retention cleanup must not leave such descendants orphaned in session state.
- Cleanup must not remove sessions belonging to the originating parent session.
- Cleanup must not remove sessions associated with retained terminal runs.
- Cleanup must not remove sessions for non-terminal runs.
- Cleanup must only tree-close linked roots that are workflow-owned for the removed run. If a linked id is absent, already closed, or not workflow-owned, cleanup skips it rather than broadening deletion.

### Read/introspection projection alignment

- This task does not leave cleanup semantics broader than introspection semantics.
- Workflow-run read/introspection surfaces covered by this task must expose a canonical linked-session projection derived from the same authoritative per-run linked session set used for cleanup: the deduplicated union of non-nil attempt `:execution-session-id` and `:judge-session-id` values.
- Existing `:psi.workflow.run/execution-session-ids` may remain as a narrower execution-only projection for compatibility or convenience, but it is not the sole authoritative linked-session read surface after this task.
- Focused resolver/read tests touched by this task should prove the canonical linked-session projection includes both execution and judge ids, deduplicated, so cleanup-target introspection and retention behavior cannot silently diverge.

## Acceptance criteria

1. Starting from an originating session with the default configuration, after two retained terminal workflow runs for that session exist, only the newest retained terminal workflow run remains and the older retained terminal workflow run is removed.
2. Under the same default case, every linked workflow-owned session tree for the removed older retained terminal run is also removed.
3. If a removed retained terminal run recorded multiple linked workflow-owned sessions across execution attempts and judge passes, cleanup removes all of their linked workflow-owned subtrees, not just one execution session.
4. With retention explicitly configured to `2`, the two newest retained terminal workflow runs for an originating session are retained and older retained terminal runs are removed.
5. With retention explicitly configured to `0`, a retained terminal workflow run is removed as soon as cleanup is applied, and all of its linked workflow-owned session trees are also removed.
6. Non-terminal workflow runs are not removed, even if the number of older retained terminal runs already exceeds the retention count.
7. Cleanup is isolated per originating agent session: terminalizing workflow runs in one parent session does not remove retained terminal workflow runs belonging to a different parent session.
8. Invalid negative retention configuration at `[:config :completed-workflow-run-retention-count]` is rejected.
9. Focused tests prove retention ordering, defaulting, per-session isolation, multi-session workflow cleanup, and subtree cleanup semantics.
10. Workflow-run resolver/read surfaces updated in this slice expose a canonical linked-session id projection that includes both execution and judge linked workflow-owned session ids, deduplicated, and focused proof covers that alignment.

## Notes for refinement

Areas to pin down during planning/implementation review:

- whether any existing introspection or listing surfaces need explicit proof updates after cleanup removes historical runs and sessions
