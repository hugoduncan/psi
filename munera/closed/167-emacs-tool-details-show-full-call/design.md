# 167 — Emacs tool details show full call

## Intent
Make Emacs `C-c C-t` tool-detail expansion show the complete tool invocation as well as the tool response, so users can inspect arguments that were truncated or summarized in the collapsed transcript row.

## Problem
The Emacs transcript has a compact tool summary line and an expanded details view. The expanded view shows the tool response, but did not show the full tool call. When the summary line truncates or omits call arguments, users cannot inspect exactly what was executed from Emacs. This is especially painful for large structured calls such as `psi-tool`, `delegate`, `edit`, or `bash` invocations where the full arguments determine whether the result is trustworthy.

## Scope
- Update the Emacs frontend behavior for tool rows toggled by `C-c C-t`.
- Preserve the existing collapsed Emacs summary behavior.
- Preserve the existing expanded Emacs response rendering.
- Add the full tool call details to the Emacs expanded view in a readable, deterministic form.
- Cover the behavior with focused Emacs tests.

## Explicitly out of scope
- TUI parity or TUI-equivalent behavior. TUI may keep, gain, or later refine similar behavior, but task 167 does not require it and TUI gaps must not block this task.
- TUI tests or TUI extension-renderer coverage.
- Changing backend tool execution semantics.
- Changing what tools are allowed to execute.
- Redesigning transcript row models or command keybindings.
- Removing or weakening summary-line truncation.
- Adding a separate command for copying tool calls, unless it is a small local helper needed by the Emacs display implementation.

## Acceptance
1. Collapsed Emacs tool rows remain compact and do not display the full call arguments inline.
2. Toggling Emacs tool details with `C-c C-t` expands tool rows to show both:
   - the full tool call details, including tool name and complete arguments available to Emacs;
   - the existing tool response/output details.
3. Long or nested tool-call arguments are not silently truncated in the Emacs expanded details view.
4. The Emacs call-details rendering is deterministic enough for tests and stable user inspection.
5. Existing Emacs response/output display behavior remains unchanged apart from being accompanied by call details.
6. Toggling Emacs tool details closed removes the expanded call and response details, returning affected tool rows to collapsed rendering. This task preserves the existing global `C-c C-t` tools-expanded mode: one toggle expands all tool rows and the next toggle collapses all tool rows. The task does not require row-local expansion state.
7. Focused Emacs tests cover collapsed, expanded, and toggled-closed states for a tool row with arguments that would be truncated or incomplete in the summary line.

## Design constraints
- Use data already available to Emacs if possible; only change the RPC/event payload contract if the full call is not currently available.
- If Emacs receives both a display summary and structured/raw tool-call data, render the structured/raw data in the expanded details view rather than trying to reconstruct it from the summary.
- Prefer a simple labeled layout, for example separate `Call` and `Response` sections, over dense inline formatting.
- Avoid hiding call details behind another nested toggle; `C-c C-t` should reveal the information needed to audit the tool execution.
- Keep formatting robust for multiline strings, EDN/JSON-like maps, shell commands, and empty/nil arguments.

## Initial implementation notes
Likely areas to inspect:

- `components/emacs-ui/psi-tool-rows.el` for Emacs tool-row rendering and detail toggling.
- Tool-row tests under `components/emacs-ui/test/`, especially existing tests around tool output/detail mode.
- The RPC event shape that creates or updates Emacs tool rows, to verify whether full call details already reach Emacs.

Suggested focused verification should include the relevant Emacs tool-row test file(s), plus any adjacent Emacs transcript tests touched by the change.

## Expanded full-call detail contract

### Authoritative call data source
Expanded Emacs tool details should prefer the most structured complete call data already available to Emacs, but must never drop raw call content when parsing is invalid, partial, or lossy. The precedence is:

1. Render the tool name from the canonical tool identity field used by the Emacs tool row.
2. Render parsed/structured arguments when they are present and represent the complete call arguments.
3. If parsed arguments are absent, incomplete, invalid, or known to have been derived from a truncated summary, render the raw argument string/payload instead.
4. If both structured arguments and raw arguments are available and parsing is known or suspected to be partial, render the structured form first and include the raw form as a fallback so the expanded view remains auditable.
5. If no arguments are available, render an explicit empty argument marker rather than omitting the call section.

Invalid JSON, invalid EDN, or otherwise partially parsed call data should be displayed as raw text in expanded details with enough labeling to make clear that the raw payload is what Emacs received. The expanded view must not reconstruct full call details from the collapsed summary line.

### Parsed/raw completeness rule
Emacs may render parsed/structured arguments without also rendering raw arguments only when the parsed value comes from a trusted complete argument field, not from display text. Trusted complete fields are fields whose contract is the actual tool-call argument value or payload, including live execution events that carry the tool call's `args`/`arguments` as data and rehydrated transcript rows that persisted that same call argument value. Summary/header text, shortened preview fields, ellipsized strings, display labels, and strings already marked or known as truncated are never trusted complete argument fields.

When both parsed and raw argument fields are present:

1. If the parsed value was produced from the raw field in the same Emacs rendering path and parsing consumed the complete raw field successfully, render the parsed value only.
2. If the parsed value and raw field are both trusted complete representations of the same argument payload, render the parsed value only.
3. If Emacs cannot prove that the parsed value came from a trusted complete field or from a complete parse of the full raw field, render the parsed value first and include the raw field as a fallback.
4. If parsing the raw field fails, consumes only part of the raw field, or normalizes away data that is needed to audit the invocation, render the raw field as the auditable fallback.
5. If the only available parsed value comes from live `tool/executing` data or a rehydrated row whose argument field is stored as the canonical call argument value, trust it as complete even when no separate raw field exists.

### Expanded-detail layout
Emacs expanded tool details should use this conceptual layout:

1. Keep the existing collapsed/header summary as the row header.
2. Add a `Call` section before the existing response/output details.
3. Render the `Call` section with the tool name and arguments. Empty or nil arguments should appear explicitly, for example as `Arguments: nil` or `Arguments: {}` depending on the underlying value.
4. Keep the existing response/output rendering under a `Response` or equivalent existing output section after `Call`.
5. Preserve multiline strings, nested maps/vectors, shell commands, and long argument values in expanded details without applying summary-line truncation.
6. Use deterministic pretty-printing/serialization so equivalent Emacs inputs produce stable output for tests.

The `Call` section is part of the first-level details revealed by the existing Emacs tool-detail toggle. It must not require a second nested toggle to inspect the executed call.

### Tool-detail toggle granularity
This task preserves the existing global Emacs tool-detail toggle semantics. Emacs `C-c C-t` toggles the frontend tool-output view mode between collapsed and expanded for all tool rows. The full-call work should add `Call` details to the expanded rendering produced by that existing global mode; it should not introduce row-local expansion state, per-row selection, or a new keybinding.

Tests should align with that behavior: collapsed assertions use global collapsed mode, expanded assertions use global expanded mode, and toggled-closed assertions toggle the same global mode back to collapsed and verify the expanded `Call` and response details are absent. It is sufficient for a focused single-row fixture to prove the target row closes, but multi-row fixtures must expect all rows to follow the same global mode rather than independent row-local state.

### Tool-specific and extension renderers
Tool-specific or extension-provided Emacs renderers may improve the collapsed/header summary or add specialized expanded presentation, but they must not be the only source of audit data for the executed call. Expanded Emacs details must always include a generic full-call representation based on structured/raw Emacs data. A specialized renderer may appear alongside that generic representation, but it may not replace the generic `Call` section unless it demonstrably includes the complete tool name and complete arguments with the same raw fallback behavior.
