# 167 — Frontend tool details show full call

## Intent
Make frontend tool-detail expansion show the complete tool invocation as well as the tool response. In Emacs this applies to `C-c C-t`; in the TUI it applies to the corresponding tool-detail expansion/toggle behavior. Users should be able to inspect arguments that were truncated or summarized in collapsed transcript rows.

## Problem
Frontend transcripts currently have compact tool summary lines and expanded/detail views. The expanded view shows the tool response, but not always the full tool call. When the summary line truncates or omits call arguments, users cannot inspect exactly what was executed from the UI. This is especially painful for large structured calls such as `psi-tool`, `delegate`, `edit`, or `bash` invocations where the full arguments determine whether the result is trustworthy.

Emacs and TUI should provide equivalent auditability: if a tool row can be expanded for details, the expanded details should include the complete call and the response.

## Scope
- Update the Emacs frontend behavior for tool rows toggled by `C-c C-t`.
- Update the TUI tool-detail expansion/toggle behavior to show equivalent call details.
- Preserve existing collapsed summary behavior in both frontends.
- Preserve existing expanded response rendering in both frontends.
- Add the full tool call details to expanded views in a readable, deterministic form.
- Cover the behavior with focused Emacs and TUI tests.

## Out of scope
- Changing the backend tool execution semantics.
- Changing what tools are allowed to execute.
- Redesigning transcript row models or command keybindings.
- Removing or weakening summary-line truncation.
- Adding a separate command for copying tool calls, unless it is a small local helper needed by the display implementation.

## Acceptance
1. Collapsed tool rows remain compact in Emacs and TUI and do not display the full call arguments inline.
2. Toggling Emacs tool details with `C-c C-t` expands the row to show both:
   - the full tool call details, including tool name and complete arguments available to the frontend;
   - the existing tool response/output details.
3. Expanding or toggling TUI tool details shows both:
   - the full tool call details, including tool name and complete arguments available to the frontend;
   - the existing tool response/output details.
4. Long or nested tool-call arguments are not silently truncated in expanded details views.
5. The call-details rendering is deterministic enough for tests and stable user inspection in both frontends.
6. Existing response/output display behavior remains unchanged apart from being accompanied by call details.
7. Toggling the same row closed removes the expanded call and response details, returning to the collapsed row, for frontends that support closing detail rows.
8. Focused Emacs tests cover collapsed, expanded, and toggled-closed states for a tool row with arguments that would be truncated or incomplete in the summary line.
9. Focused TUI tests cover the equivalent collapsed and expanded/detail states for a tool row with arguments that would be truncated or incomplete in the summary line.

## Design constraints
- Use data already available to each frontend if possible; only change the RPC/event payload contract if the full call is not currently available.
- If a frontend receives both a display summary and structured/raw tool-call data, render the structured/raw data in the expanded details view rather than trying to reconstruct it from the summary.
- Prefer a simple labeled layout, for example separate `Call` and `Response` sections, over dense inline formatting.
- Avoid hiding call details behind another nested toggle; `C-c C-t` should reveal the information needed to audit the tool execution.
- Keep formatting robust for multiline strings, EDN/JSON-like maps, shell commands, and empty/nil arguments.

## Initial implementation notes
Likely areas to inspect:

- `components/emacs-ui/psi-tool-rows.el` for Emacs tool-row rendering and detail toggling.
- Tool-row tests under `components/emacs-ui/test/`, especially existing tests around tool output/detail mode.
- TUI transcript/tool rendering code and tests, to find the TUI equivalent of tool-detail expansion.
- The RPC/event shape that creates or updates tool rows, to verify whether full call details already reach each frontend.

Suggested focused verification should include the relevant Emacs tool-row test file(s), the relevant TUI tool/transcript test file(s), plus any adjacent transcript tests touched by the change.
