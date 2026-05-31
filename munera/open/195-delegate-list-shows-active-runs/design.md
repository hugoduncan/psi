# delegate list shows active runs

## Intent

Fix the delegation management surface so a `delegate` tool call with `action: "list"` reliably shows delegation runs that are still active or otherwise retained, instead of returning an empty list while delegated work is in progress.

## Problem

The `delegate` tool supports starting workflow/single-step delegated runs and managing them with actions such as `list`, `continue`, and `remove`. In practice, `delegate` with `action: "list"` appears to return an empty result even when one or more delegation runs are in progress.

This breaks operator awareness and follow-up workflows:

- the originating agent cannot discover active delegated runs;
- users cannot rely on `delegate list` to decide whether work is still running;
- `continue` or cleanup actions become harder because run ids are not visible through the expected management surface;
- automation built on top of the delegate tool may incorrectly conclude that there are no active runs.

## Scope

### In scope

- Reproduce the behavior with at least one active delegated run and a `delegate` call using `action: "list"`.
- Identify the authoritative storage/projection path for delegated run metadata.
- Fix the `delegate list` implementation so it returns active/in-progress delegated runs visible to the invoking session.
- Preserve expected visibility for stopped, completed, failed, or otherwise retained delegated runs if the existing tool contract intends to list them.
- Ensure returned entries include enough information to manage the run, including the run id, workflow/profile name when available, current state/status, and useful timestamps or labels when available.
- Add focused regression coverage for listing while a delegated run is still in progress.
- Add focused coverage for the relevant visibility boundary: runs from the invoking session are listed; unrelated sessions are not listed unless the current contract explicitly says they should be global.
- Verify that `continue` and `remove` still work with run ids surfaced by `list`.

### Out of scope

- Redesigning the delegate tool API shape beyond what is needed to make `action: "list"` truthful and useful.
- Adding new user-facing slash commands.
- Changing workflow execution semantics.
- Changing completed-run retention or workflow-run cleanup policy, except where the current bug is caused by accidental premature deletion of delegation run metadata.
- Broad session tree or workflow introspection redesign.

## Desired behavior

After this task:

- Starting a delegated run and calling `delegate` with `action: "list"` from the originating session returns that run while it is active.
- The list result is not empty when active delegated runs exist and are visible under the delegate tool's session boundary.
- The surfaced run id can be passed to `delegate` `continue` or `remove` where those actions are valid for the run's current state.
- If no runs are visible to the invoking session, `delegate list` returns an intentionally empty list rather than failing or showing unrelated runs.
- Tests prevent regressions where list reads from an empty/default registry, the wrong session, or only terminal runs.

## Behavioral requirements

1. `delegate list` must read from the same authoritative run registry/storage that `delegate run` writes to.
2. `delegate list` must target the invoking/originating agent session explicitly; it must not silently query a default or unrelated session.
3. Active delegated runs must remain discoverable until they reach a state where the delegate management contract intentionally removes or hides them.
4. If the implementation has separate concepts of workflow runs and delegation tool runs, `delegate list` must list the delegation runs managed by the `delegate` tool, and each entry should expose enough linkage to the underlying workflow run/session for debugging.
5. Empty-list results must be reserved for the true case where no visible delegation runs exist.
6. Errors in reading the run registry should surface as actionable tool errors, not as a misleading empty list.

## Acceptance criteria

1. A focused test starts or installs an active delegated run for a session, calls the delegate list path for that same session, and asserts the run is present.
2. The listed run entry includes a stable run id usable by delegate management actions.
3. The listed run entry includes status/state information that distinguishes active/running work from stopped or terminal work.
4. A same-session visibility test proves the list path uses the invoking session's delegation state rather than an empty/default context.
5. A boundary test proves unrelated session runs are not accidentally listed unless the documented delegate contract requires global listing.
6. A regression test covers the observed failure mode: active run exists, `delegate list` is not empty.
7. Existing `delegate continue` and `delegate remove` behavior remains compatible with ids returned by `delegate list`.
8. If the fix touches user-visible delegate output shape or documentation, README/doc/changelog are updated in the same implementation slice.

## Investigation notes

Likely areas to inspect during planning:

- the delegate tool implementation for `action: "list"`;
- the workflow-loader/delegation run registry and any in-memory versus persisted storage split;
- session targeting for tool calls from originating sessions and child workflow sessions;
- whether async run handles are stored under workflow runtime state, agent-session state, extension state, or an external delegation registry;
- any retention/cleanup behavior that might remove active run metadata too early.

## Delegate list visibility and retention contract

`delegate list` is scoped to the invoking/originating agent session, not global. A run is visible to a session only when the delegate tool's background-job registry has a workflow-delegate background job whose `thread-id` equals the invoking `:psi.agent-session/session-id`. The background job is the authoritative session/owner marker for delegate-tool visibility.

A background job is eligible for workflow-delegate visibility only when all of these fields match the canonical delegate workflow provenance:

- `tool-name = "delegate"`;
- `job-kind = :workflow`;
- `workflow-ext-path = "built-in:workflow"` (the built-in workflow delegate provenance);
- `thread-id = :psi.agent-session/session-id` for the invoking session.

Same-session background jobs with a different `tool-name` or a non-workflow `job-kind` are outside the delegate-workflow list contract and should be ignored by `delegate list`; they must not make the list fail as corrupt delegate workflow runs merely because they carry unrelated or foreign provenance. Same-session `tool-name = "delegate"` jobs that claim `job-kind = :workflow` are inside the delegate-workflow list contract; if their `workflow-ext-path` is missing, nil, blank, or non-`"built-in:workflow"`, they are malformed delegate workflow jobs and should surface an actionable `delegate list` inconsistency/tool error rather than being silently listed, silently ignored, or treated as an empty-list case.

The canonical workflow run registry remains the authoritative source for workflow run identity, status, definition id, current step, creation time, and manageability. A listed delegate entry is formed by joining the same-session eligible background job's `workflow-id` to the canonical workflow run's `run-id`.

Visibility rules:

- Non-terminal delegate background jobs (`:running` and `:pending-cancel`) must always be listed for the invoking session, even if workflow execution is still in progress.
- Retained terminal delegate background jobs (`:completed`, `:failed`, `:cancelled`, `:timed-out`) should remain listed while the matching canonical workflow run still exists, because their run ids may still be valid for `continue` or `remove` according to workflow status.
- Removed canonical workflow runs must not remain visible solely because terminal background-job history is retained.
- Delegate jobs from unrelated sessions must not be listed, even if their canonical workflow runs still exist in the global workflow registry.
- If a same-session delegate background job points at a missing canonical workflow run while still non-terminal, `delegate list` should surface an actionable inconsistency/error rather than silently returning an empty list.
- If a same-session non-terminal delegate workflow background job has a missing, nil, non-string, or blank `workflow-id`, `delegate list` should surface an actionable inconsistency/tool error for the malformed delegate job. It must not silently ignore the job, coerce the malformed id into an empty result, or report that no visible runs exist. The error should identify the delegate background job enough to diagnose or clean up the corrupted state without inventing a canonical workflow run id.
- If a retained terminal same-session delegate workflow background job (`:completed`, `:failed`, `:cancelled`, or `:timed-out`) has a missing, nil, non-string, or blank `workflow-id`, `delegate list` should treat it as non-manageable retained history and hide it from the normal list. It must not synthesize a canonical workflow run id, must not report the job as a continuable/removable run, and must not turn an otherwise valid list into a tool error solely because old terminal history is malformed. If no other visible valid delegate workflow jobs exist, this condition may still produce the ordinary empty-list result, because terminal retained jobs without a usable workflow id cannot be managed by `continue` or `remove`.
- If the delegate background-job registry or query surface is unavailable, missing, unreadable, or returns a non-query result, `delegate list` must surface an actionable tool error. Because same-session delegate background jobs are the authoritative visibility marker, `delegate list` must not treat an unavailable background-job surface as an empty job set, must not return `No active runs.` from that condition, and must not fall back to global canonical workflow runs as a substitute visibility source. The error should identify the background-job visibility/read surface so the operator can distinguish registry/query failure from a true no-visible-runs result.

- If multiple eligible same-session delegate workflow background jobs reference the same canonical `workflow-id`, `delegate list` should surface at most one list entry for that canonical workflow run id. The canonical workflow run remains the single management identity; duplicate background jobs must not produce duplicate run rows or alternate management ids.
- Duplicate eligible jobs for the same `workflow-id` are valid only when they can be reduced to one manageable entry without hiding an active-job contradiction:
  - if exactly one duplicate job is non-terminal, list the canonical run once and use that non-terminal delegate/background status as the displayed background status; retained terminal duplicates for the same run are historical noise and should not create extra entries or override the active delegate status;
  - if all duplicate jobs are terminal retained history, list the canonical run once while the canonical run still exists, using the newest retained background job status when a background status is displayed;
  - if more than one duplicate job is non-terminal for the same `workflow-id`, `delegate list` should surface an actionable duplicate-job inconsistency/tool error instead of choosing an arbitrary active status, because there is no unambiguous single inflight delegate job to manage.
- The duplicate-job rule does not change the existing malformed/missing-canonical rules: a non-terminal duplicate group whose canonical workflow run is missing is still an actionable missing-canonical inconsistency, and terminal-only duplicate history whose canonical workflow run was removed remains hidden.

For terminal-only duplicate eligible jobs with the same canonical `workflow-id`, "newest retained background job" is selected deterministically by the background-job completion ordering, newest last:

1. larger non-nil `completed-at`;
2. if `completed-at` is equal or unavailable for both jobs, larger non-nil `completed-seq`;
3. if still tied or unavailable, larger non-nil `job-seq`;
4. if still tied, lexicographically larger string `job-id`.

Terminal retained jobs normally have `completed-at` and `completed-seq`; missing ordering fields are treated as older than present values for the same comparison level, so malformed retained history cannot win over a fully recorded terminal job with a real completion marker. This ordering is only for choosing the displayed delegate/background status in a terminal-only duplicate group. It does not create another management id, and it does not make missing-canonical terminal history visible after the canonical workflow run is removed.

Final `delegate list` rows are ordered by the selected representative delegate/background job for each visible canonical workflow run, not by canonical workflow registry traversal order or status groups. The stable ordering is newest first by the earliest durable delegate-job ordering marker available for the representative job:

1. larger non-nil `started-at`;
2. if `started-at` is equal or unavailable for both rows, larger non-nil `job-seq`;
3. if still tied or unavailable, lexicographically larger string `job-id`;
4. if still tied, lexicographically larger canonical workflow `run-id`.

Missing ordering fields are treated as older than present values at the same comparison level. Rows are not grouped by canonical workflow status or delegate/background status before applying this ordering; status is display/filtering information, not the primary list sort key. This keeps active and retained delegate runs in deterministic recency order using the same background-job ownership surface that determines visibility, while `run-id` remains only a final deterministic tie-breaker.

Timed-out delegate background jobs are a delegate-tool retention state, not a canonical workflow-run status. Canonical workflow runs continue to use the workflow runtime status set (`:pending`, `:running`, `:blocked`, `:completed`, `:failed`, `:cancelled`). When a retained same-session delegate background job has `:status :timed-out` and its canonical workflow run still exists, `delegate list` should include the entry with:

- the canonical `run-id` as the surfaced management id;
- canonical workflow status as the primary run status used to decide `continue` compatibility;
- delegate/background status shown separately as `:timed-out` (or equivalent text such as `delegate timed out`) so the operator can distinguish wrapper/job timeout from workflow state;
- `remove` compatibility whenever the canonical run exists, because `remove` deletes the canonical workflow run;
- `continue` compatibility only when the canonical workflow status is one of the statuses currently supported by `delegate continue` (`:blocked`, `:completed`, `:failed`, `:cancelled`). A timed-out background job whose canonical run is still `:pending` or `:running` is listable/removable but not continuable yet.

A retained `:timed-out` background job pointing at a missing canonical workflow run should be hidden like other terminal retained jobs whose canonical run has been removed; it should not create a synthetic `:timed-out` workflow run.

When canonical workflow execution stops because the workflow is `:blocked`, the delegate wrapper/background job should be marked terminal `:completed`, not `:failed`, `:cancelled`, or a synthetic `:blocked` background-job status. The wrapper job completed its attempt successfully by driving the canonical workflow to a valid pause point; the canonical workflow run remains the authoritative source of the `:blocked` state and of `continue` eligibility.

For a blocked run, `delegate list` should therefore display both layers explicitly:

- canonical workflow status remains `:blocked` and is the primary run status used for management decisions;
- delegate/background status is `:completed` (or equivalent text such as `delegate attempt completed`) for the attempt that reached the blocked pause;
- the listed run is continuable because canonical `:blocked` is supported by `delegate continue`;
- the listed run is removable while the canonical run exists;
- the background status must not be interpreted as canonical workflow completion, and must not hide the run while the canonical blocked run remains retained.

This mapping applies only to the delegate/background wrapper status. It does not change the canonical workflow status model, does not add `:blocked` to the background-job terminal status set, and does not make blocked workflow runs terminal in the canonical workflow registry.


## Delegate background-job identity for attempts and continuations

Delegate background-job `tool-call-id` is an execution-attempt identity, not the canonical workflow management identity. The surfaced management id for `delegate list`, `continue`, and `remove` remains the canonical workflow `run-id` carried in `workflow-id`.

Any delegate workflow execution attempt that creates a background job must use a background-job `tool-call-id` that is unique for that attempt, rather than deriving it solely as `delegate/<run-id>` whenever another retained background job for the same canonical run may already exist. A valid shape is `delegate/<run-id>/<attempt-id>` (for example an attempt sequence, timestamp, UUID, or other durable unique attempt token). The exact token format is not user-facing, but it must be stable enough for background-job registry diagnostics and unique under the registry's one-job-per-`tool-call-id` rule. `job-id` remains globally unique as required by the background-job registry.

The canonical `workflow-id` stored on each delegate background job must continue to be the workflow run id being managed:

- an initial async delegate run stores `workflow-id = <new run-id>` and may use either the first attempt-specific `tool-call-id` or the legacy `delegate/<run-id>` only if that id cannot collide with retained history;
- resuming a blocked canonical run stores `workflow-id = <same blocked run-id>` but must create a new attempt-specific `tool-call-id` when any prior retained job for that run may exist;
- continuing a terminal run by creating a new canonical workflow run stores `workflow-id = <new continuation run-id>` for the new job, while provenance such as `continued-from` may point back to the prior run separately if the implementation records it.

`delegate list` must not use `tool-call-id` as the management id and must not require a one-to-one relationship between `tool-call-id` and canonical `workflow-id`. Duplicate eligible jobs for the same `workflow-id` are interpreted using the duplicate-job reduction rules above: retained terminal attempt history plus one newer non-terminal attempt is valid and lists as one run; multiple non-terminal attempts for the same canonical run remain an actionable duplicate-job inconsistency.

A resumed/continued attempt must not replace or mutate retained terminal attempt history merely to satisfy the background-job registry uniqueness constraint. If an implementation chooses an alternative cleanup strategy instead of attempt-specific ids, it must first intentionally remove or retire the old retained job before starting the new attempt, and the resulting state must still satisfy the same duplicate/list visibility rules without leaving a registry-level `tool-call-id` collision.


## Delegate remove cleanup contract

For runs surfaced by `delegate list`, `delegate remove` uses the listed canonical `run-id` as its target and removes the canonical workflow run from the workflow run registry. When the target is backed by a same-session delegate background job that is still non-terminal (`:running` or `:pending-cancel`), a successful remove must also resolve the delegate background-job side of the relationship so that a later `delegate list` cannot see a non-terminal same-session job pointing at a missing canonical workflow run.

Acceptable cleanup outcomes for an active/non-terminal listed run are:

- cancel the inflight delegate execution and record the background job as terminal `:cancelled`;
- remove the delegate background job from the delegate/background-job registry; or
- perform an equivalent atomic state transition that makes the job intentionally hidden from `delegate list` after the canonical workflow run is removed.

A successful `remove` must therefore not leave a same-session non-terminal delegate workflow background job with the removed `workflow-id`. If the implementation cannot cancel or clean up the background job while removing the canonical workflow run, `remove` should fail with an actionable tool error and leave the canonical run visible/manageable rather than creating a state that `delegate list` must later report as corruption.

After successful removal, a retained terminal delegate background job whose canonical workflow run was deleted is hidden by the existing terminal-retention visibility rule; it is not treated as a delegate-list inconsistency. The missing-canonical-run inconsistency remains reserved for non-terminal jobs that were not intentionally resolved by a successful remove/cleanup path.
