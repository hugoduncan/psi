# Plan

## Approach

Fix delegate result surfacing by treating the caller-visible tool response as the authoritative behavior, then tracing backward only as far as needed to preserve semantic delegate output through the runtime boundary.

Key decisions:

- Start with boundary-level characterization tests that invoke the registered `delegate` tool through the same runtime/tool execution path used by agent sessions as closely as practical.
- Distinguish transport success (`:is-error false` or equivalent successful tool invocation) from semantic delegate results in assertions: unknown workflow and empty-list messages must be present in the returned content/result text.
- Keep the delegate implementation's current user-oriented unknown-workflow wording unless the result propagation path requires a minimal consistency adjustment.
- Cover both empty workflow registries and non-empty registries missing the requested workflow so unknown-workflow surfacing cannot depend on the no-definitions special case.
- Cover `delegate list` with no workflows and no active runs as meaningful output, not a silent success.
- Prefer a narrow fix at the conversion/post-processing/tool-response seam if pure delegate functions already return correct strings.
- Preserve existing successful delegate run/list/continue/remove behavior and existing delegate-list visibility semantics from task 195.
- Update changelog or user-facing docs only if the exposed behavior or documented delegate tool contract changes.

## Risks

- The reported silent result may live in post-tool processing, content normalization, or transcript recording rather than in `psi.agent-session.workflow.core/execute-delegate-tool`; tests must exercise enough of the real boundary to catch that seam.
- Existing unit tests that call private delegate functions can pass while the agent-consumed payload remains empty; avoid relying on formatter-only or private-function-only coverage.
- Initializing the built-in workflow extension in tests loads project workflows by default, so an empty-definition test may need controlled runtime state or targeted stubbing without bypassing the tool boundary under test.
- Delegate `list` already has strict background-job visibility validation from task 195; an empty-list test must provide a valid empty background-job query surface, not accidentally test a background-job read error.
- Marking semantic delegate errors as transport errors could break callers that expect tool transport success with visible semantic error text; keep transport semantics unchanged unless investigation proves otherwise necessary.

## Slice order

1. **Boundary reproduction harness** — identify the registered delegate tool invocation path and build test fixtures that can run it with controlled loaded workflow definitions, canonical runs, and background-job query state.
2. **Unknown workflow surfacing regressions** — add focused boundary tests for `delegate run` with an unknown workflow when no workflows are loaded and when other workflows exist.
3. **Empty list surfacing regression** — add focused boundary coverage for `delegate list` when no workflows and no active delegate runs exist.
4. **Result propagation fix** — repair the minimal boundary/conversion path so delegate strings, semantic errors, and meaningful empty-list output survive into the caller-visible tool response.
5. **Successful behavior preservation** — verify existing successful delegate workflow behavior and task-195 delegate-list behavior remain unchanged with focused tests.
6. **Docs and coherence pass** — update changelog/docs if needed, run focused tests and targeted lint, and record verification plus decisions in `implementation.md`.
