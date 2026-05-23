# 167 — Emacs tool details show full call

## Intent
Make Emacs `C-c C-t` tool-detail toggling show the complete tool invocation as well as the tool response, so users can inspect arguments that were truncated or summarized in the collapsed transcript row.

## Problem
The Emacs transcript currently has a compact tool summary line and an expanded details view. The expanded view shows the tool response, but not the full tool call. When the summary line truncates or omits call arguments, users cannot inspect exactly what was executed from Emacs. This is especially painful for large structured calls such as `psi-tool`, `delegate`, `edit`, or `bash` invocations where the full arguments determine whether the result is trustworthy.

## Scope
- Update the Emacs frontend behavior for tool rows toggled by `C-c C-t`.
- Preserve the existing collapsed summary behavior.
- Preserve the existing expanded response rendering.
- Add the full tool call details to the expanded view in a readable, deterministic form.
- Cover the behavior with focused Emacs tests.

## Out of scope
- Changing the backend tool execution semantics.
- Changing what tools are allowed to execute.
- Redesigning the transcript row model or command keybinding.
- Removing or weakening summary-line truncation.
- Adding a separate command for copying tool calls, unless it is a small local helper needed by the display implementation.

## Acceptance
1. A collapsed tool row remains compact and does not display the full call arguments inline.
2. Toggling tool details with `C-c C-t` expands the row to show both:
   - the full tool call details, including tool name and complete arguments available to the frontend;
   - the existing tool response/output details.
3. Long or nested tool-call arguments are not silently truncated in the expanded details view.
4. The call-details rendering is deterministic enough for tests and stable user inspection.
5. Existing response/output display behavior remains unchanged apart from being accompanied by call details.
6. Toggling the same row closed removes the expanded call and response details, returning to the collapsed row.
7. Focused Emacs tests cover collapsed, expanded, and toggled-closed states for a tool row with arguments that would be truncated or incomplete in the summary line.

## Design constraints
- Use data already available to the Emacs frontend if possible; only change the RPC/event payload contract if the full call is not currently available.
- If the frontend receives both a display summary and structured/raw tool-call data, render the structured/raw data in the expanded details view rather than trying to reconstruct it from the summary.
- Prefer a simple labeled layout, for example separate `Call` and `Response` sections, over dense inline formatting.
- Avoid hiding call details behind another nested toggle; `C-c C-t` should reveal the information needed to audit the tool execution.
- Keep formatting robust for multiline strings, EDN/JSON-like maps, shell commands, and empty/nil arguments.

## Initial implementation notes
Likely areas to inspect:

- `components/emacs-ui/psi-tool-rows.el` for tool-row rendering and detail toggling.
- Tool-row tests under `components/emacs-ui/test/`, especially existing tests around tool output/detail mode.
- The RPC event shape that creates or updates tool rows, to verify whether full call details already reach Emacs.

Suggested focused verification should include the relevant Emacs tool-row test file(s), plus any adjacent transcript tests touched by the change.
