# Delegate missing workflow result surfacing

## Intent

Make the `delegate` tool reliably surface semantic results for missing or unavailable workflows, especially when no workflows are defined, so callers cannot mistake transport-level tool success for a successful delegation.

## Problem

A user attempted to validate delegation with:

```edn
{:action "run"
 :workflow "agent"
 :prompt "Say hello and confirm you are working correctly. Keep it brief."
 :mode "sync"
 :timeout_ms 30000}
```

in an environment with no workflows defined. The tool call was reported as successful but returned no visible output. A subsequent `delegate list` call also reported success with no visible output. The caller reasonably concluded that delegation mechanics worked but produced empty output.

That behavior is misleading. With no workflows defined:

- `delegate run` for workflow `agent` should produce an explicit unknown-workflow error in the delegate tool result.
- `delegate list` should produce an explicit empty list response, not an empty/silent result.
- Tool transport success must not be treated as semantic delegate success when the delegate operation failed or has an empty-but-meaningful response.

Existing source suggests the Clojure delegate implementation intends to render errors and list output, but the exposed API/tool result can be empty. This task is to reproduce the actual end-to-end behavior and fix the result propagation path.

## Scope

This task covers the delegate tool behavior visible to callers through the same tool/API surface used by agent sessions, including:

- `delegate` action `run` with an unknown workflow name when the loaded workflow definition set is empty.
- `delegate` action `run` with an unknown workflow name when other workflows exist.
- `delegate` action `list` when no workflows and no active runs exist.
- The boundary where delegate implementation return values are converted into the exposed tool-call response.
- Tests that prove the caller-visible response contains the expected text or structured error, not just an internal return value.

## Out of scope

- Adding a generic `agent` workflow.
- Changing workflow registry semantics.
- Changing lower-level `psi.workflow/create-run` error wording except where needed to preserve delegate output consistency.
- Changing delegated workflow-step behavior for dynamic delegate steps, unless the same result propagation bug directly affects caller-visible delegate tool results.
- Treating transport-level success as failure solely because the delegate operation has a semantic error; it is acceptable for the tool call transport to succeed if the response clearly contains the semantic delegate error.

## Desired behavior

When no workflows are defined:

```text
Tool: delegate
Arguments: {"action":"run", "workflow":"agent", ...}
```

returns a caller-visible result equivalent to:

```text
Error: Unknown workflow 'agent'. Use action=list to see available workflows.
```

or a structured tool result whose rendered content unambiguously contains that error.

When no workflows and no active runs exist:

```text
Tool: delegate
Arguments: {"action":"list"}
```

returns a caller-visible result equivalent to:

```text
Available workflows:
No workflows loaded.

Active runs:
No active runs.
```

When some workflows exist but the requested workflow is not among them, `delegate run` returns the same unknown-workflow shape with the requested workflow name and a suggestion to use `action=list`.

The response must be visible at the API/tool boundary used by agents. A test that only calls a private implementation function and sees the right string is insufficient.

## Reproduction requirements

The task must include an executable regression that fails before the fix and passes after it. The regression must exercise the real delegate tool boundary as closely as practical, not merely the pure text formatting functions.

The reproduction should distinguish:

- transport/tool invocation status, such as “successfully called the tool”; from
- semantic delegate operation result, such as “unknown workflow” or “no workflows loaded”.

The test should assert the semantic result is present in the caller-visible payload even when the transport invocation itself is successful.

## Acceptance criteria

- A focused test reproduces the reported silent result for `delegate run` with an unknown workflow in an empty workflow-definition environment before the fix.
- A focused test proves `delegate list` with no workflows/no active runs returns a visible empty-list message.
- A focused test proves `delegate run` with an unknown workflow while other workflows exist returns a visible unknown-workflow message.
- The fix ensures delegate operation errors and meaningful empty-list output are propagated to the actual tool response consumed by callers.
- The final behavior prevents an agent from honestly summarizing the transcript as “delegation ran without errors” when the workflow is unknown.
- Existing successful delegate workflow behavior remains unchanged.
- Relevant user-facing documentation or changelog is updated if the exposed delegate tool behavior changes.

## Notes

Observed direct lower-level workflow API behavior for a missing definition returns a structured error with message `Workflow definition not found`. The delegate tool layer is allowed to keep its more user-oriented message `Unknown workflow '<name>'. Use action=list to see available workflows.` as long as it is reliably visible to callers.
