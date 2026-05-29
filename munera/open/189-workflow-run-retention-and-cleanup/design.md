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
- Remove the workflow-owned sessions associated with removed workflow runs.
- Ensure the cleanup only affects retained terminal workflow runs (`:completed`, `:failed`, `:cancelled`); active or otherwise non-terminal runs must not be removed.
- Ensure cleanup is deterministic: when more than the retained count of completed runs exist for an originating session, the kept set is the most recently completed runs, with older completed runs removed first.
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
- Sessions owned only by removed workflow runs are also removed automatically as part of the same cleanup behavior.
- Non-terminal workflow runs are never removed by this cleanup, and terminal statuses outside the runtime workflow-run status set are out of scope.

## Definitions

- **Originating agent session**: the parent session from which a workflow run was started.
- **Retained terminal workflow run**: a workflow run whose status is one of the canonical workflow runtime terminal statuses covered by this task: `:completed`, `:failed`, or `:cancelled`. These are terminal within the workflow runtime and are the only statuses eligible for retention cleanup in this slice.
- **Workflow-owned session**: a runtime session created for workflow execution and associated to a workflow run.
- **Retention count**: the number of retained terminal workflow runs to keep for one originating agent session.

## Behavior

### Retention policy

- Retention is grouped by originating agent session.
- This task treats the canonical workflow runtime terminal statuses `:completed`, `:failed`, and `:cancelled` as the retained status set.
- The system evaluates retention when a workflow run transitions into one of those retained terminal statuses.
- Retention ordering uses terminal transition time: the retained terminal runs for that originating session are ordered from newest terminal transition to oldest terminal transition.
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

- When a completed workflow run is removed by retention cleanup, its associated workflow-owned session is also removed.
- Cleanup must not remove sessions belonging to the originating parent session.
- Cleanup must not remove sessions associated with retained completed runs.
- Cleanup must not remove sessions for non-terminal runs.

## Acceptance criteria

1. Starting from an originating session with the default configuration, after two retained terminal workflow runs for that session exist, only the newest retained terminal workflow run remains and the older retained terminal workflow run is removed.
2. Under the same default case, the workflow-owned session for the removed older retained terminal run is also removed.
3. With retention explicitly configured to `2`, the two newest retained terminal workflow runs for an originating session are retained and older retained terminal runs are removed.
4. With retention explicitly configured to `0`, a retained terminal workflow run is removed as soon as cleanup is applied, and its workflow-owned session is also removed.
5. Non-terminal workflow runs are not removed, even if the number of older retained terminal runs already exceeds the retention count.
6. Cleanup is isolated per originating agent session: terminalizing workflow runs in one parent session does not remove retained terminal workflow runs belonging to a different parent session.
7. Invalid negative retention configuration at `[:config :completed-workflow-run-retention-count]` is rejected.
8. Focused tests prove retention ordering, defaulting, per-session isolation, and workflow-session cleanup.

## Notes for refinement

Areas to pin down during planning/implementation review:

- whether any existing introspection or listing surfaces need explicit proof updates after cleanup removes historical runs and sessions
