# Steps

## Slice 1 — Boundary reproduction harness

- [x] Locate the registered `delegate` tool execution path used by agent sessions, including runtime tool execution, post-tool processing, content normalization, and transcript/result-message recording.
- [x] Identify the narrowest test entry point that exercises the caller-visible delegate tool response without calling only private formatter or dispatcher functions.
- [x] Create or reuse a test fixture that initializes the built-in delegate tool for a session while allowing `runtime-state/loaded-definitions` to be controlled per test.
- [x] Create or reuse a valid empty background-job query fixture so `delegate list` can distinguish "no active runs" from background-job read-surface failure.
- [x] Record the chosen boundary and fixture assumptions in `implementation.md`.

## Slice 2 — Unknown workflow surfacing regressions

- [x] Add a focused boundary test for `delegate run` with workflow `agent` and no loaded workflow definitions.
- [x] Assert the unknown-workflow test observes transport/tool invocation success separately from semantic delegate failure.
- [x] Assert the caller-visible payload/result text contains `Error: Unknown workflow 'agent'. Use action=list to see available workflows.` or an equivalent structured value rendered with that message.
- [x] Add a focused boundary test for `delegate run` with workflow `agent` when at least one different workflow definition is loaded.
- [x] Assert the non-empty-registry unknown-workflow payload includes the requested workflow name and the `action=list` suggestion.
- [x] Confirm the new unknown-workflow tests fail on the current silent-result behavior before applying the fix, or document why existing current behavior already passes at this boundary and where the actual failing seam is reproduced.

## Slice 3 — Empty list surfacing regression

- [x] Add a focused boundary test for `delegate list` with no loaded workflow definitions, no canonical workflow runs, and an empty but valid background-job query result.
- [x] Assert the `delegate list` invocation is transport/tool successful.
- [x] Assert the caller-visible payload/result text contains `Available workflows:`, `No workflows loaded.`, `Active runs:`, and `No active runs.`.
- [x] Confirm the empty-list test fails on the current silent-result behavior before applying the fix, or document why existing current behavior already passes at this boundary and where the actual failing seam is reproduced.

## Slice 4 — Result propagation fix

- [x] Trace the failing boundary from delegate implementation return value through runtime tool execution, post-tool processing, content normalization, and result-message recording to find where content is dropped or hidden.
- [x] Apply the minimal fix so string delegate results become caller-visible tool response content/result text.
- [x] Ensure semantic delegate errors remain visible even when tool transport status remains successful.
- [x] Ensure meaningful empty-list output is not converted to nil, empty content, or a hidden-only detail field.
- [x] Add or update assertions that prove the fixed boundary preserves delegate output in the exact payload consumed by callers.
- [x] Record the root cause and fix decision in `implementation.md`.

## Slice 5 — Successful behavior preservation

- [x] Run existing focused delegate workflow tests that cover successful registered workflow execution.
- [x] Run existing focused delegate-list tests from task 195 to ensure active-run visibility, same-session scoping, malformed background-job errors, continue, and remove behavior still pass.
- [x] Add preservation coverage only if the fix touches shared result shaping in a way not covered by existing tests.
- [x] Run targeted `clj-kondo` over changed source and tests.
- [x] Record verification commands and results in `implementation.md`.

## Slice 6 — Docs and coherence pass

- [x] Decide whether the exposed delegate behavior change is user-visible enough for `CHANGELOG.md`.
- [x] Update `CHANGELOG.md` if the fix changes caller-visible delegate tool behavior from silent success to visible semantic output/error.
- [x] Review `README.md` and `doc/` delegate/workflow sections and update them only if their current delegate behavior description becomes inaccurate or incomplete.
- [x] Re-read changed plan, steps, implementation notes, tests, code, and docs for coherence with `design.md` acceptance criteria.
- [x] Commit the completed implementation changes separately from this planning commit when implementation work begins.
