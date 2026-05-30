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

`delegate list` is scoped to the invoking/originating agent session, not global. A run is visible to a session only when the delegate tool's background-job registry has a `tool-name = "delegate"` workflow job whose `thread-id` equals the invoking `:psi.agent-session/session-id`. The background job is the authoritative session/owner marker for delegate-tool visibility.

The canonical workflow run registry remains the authoritative source for workflow run identity, status, definition id, current step, creation time, and manageability. A listed delegate entry is formed by joining the same-session background job's `workflow-id` to the canonical workflow run's `run-id`.

Visibility rules:

- Non-terminal delegate background jobs (`:running` and `:pending-cancel`) must always be listed for the invoking session, even if workflow execution is still in progress.
- Retained terminal delegate background jobs (`:completed`, `:failed`, `:cancelled`, `:timed-out`) should remain listed while the matching canonical workflow run still exists, because their run ids may still be valid for `continue` or `remove` according to workflow status.
- Removed canonical workflow runs must not remain visible solely because terminal background-job history is retained.
- Delegate jobs from unrelated sessions must not be listed, even if their canonical workflow runs still exist in the global workflow registry.
- If a same-session delegate background job points at a missing canonical workflow run while still non-terminal, `delegate list` should surface an actionable inconsistency/error rather than silently returning an empty list.

Timed-out delegate background jobs are a delegate-tool retention state, not a canonical workflow-run status. Canonical workflow runs continue to use the workflow runtime status set (`:pending`, `:running`, `:blocked`, `:completed`, `:failed`, `:cancelled`). When a retained same-session delegate background job has `:status :timed-out` and its canonical workflow run still exists, `delegate list` should include the entry with:

- the canonical `run-id` as the surfaced management id;
- canonical workflow status as the primary run status used to decide `continue` compatibility;
- delegate/background status shown separately as `:timed-out` (or equivalent text such as `delegate timed out`) so the operator can distinguish wrapper/job timeout from workflow state;
- `remove` compatibility whenever the canonical run exists, because `remove` deletes the canonical workflow run;
- `continue` compatibility only when the canonical workflow status is one of the statuses currently supported by `delegate continue` (`:blocked`, `:completed`, `:failed`, `:cancelled`). A timed-out background job whose canonical run is still `:pending` or `:running` is listable/removable but not continuable yet.

A retained `:timed-out` background job pointing at a missing canonical workflow run should be hidden like other terminal retained jobs whose canonical run has been removed; it should not create a synthetic `:timed-out` workflow run.
