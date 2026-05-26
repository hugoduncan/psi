# Plan

Implement the full-call detail display as a small Emacs rendering change, preserving existing collapsed summaries and response rendering.

## Approach

1. Inspect the Emacs tool-row model/rendering path and its focused tests to identify the data already available for tool name, structured arguments, raw arguments, and response/output.
2. Add or reuse a deterministic Emacs formatter for expanded `Call` details that:
   - renders the canonical tool name;
   - renders complete structured arguments when available;
   - falls back to raw argument payloads when structured arguments are absent, invalid, partial, or summary-derived;
   - explicitly renders empty or nil arguments;
   - preserves multiline, nested, and long values without summary truncation.
3. Insert the Emacs `Call` section into the existing global first-level detail expansion before the existing response/output details, without changing collapsed summary behavior, `C-c C-t`, or the all-tool-rows toggle semantics.
4. Preserve existing Emacs response/output rendering apart from placing it after or alongside the new call section.
5. Add focused Emacs tests for collapsed, expanded, and toggled-closed states using arguments that are truncated or incomplete in the collapsed summary.
6. Run focused Emacs verification, then any adjacent Emacs transcript/tool-row test suites affected by the change.

## Decisions

- The row header/collapsed summary remains the compact existing summary and remains separate from expanded `Call` details.
- Tool-detail toggles remain global in Emacs: `C-c C-t` expands/collapses all tool rows rather than introducing row-local detail state.
- Expanded Emacs details always include a generic auditable `Call` section unless an existing specialized Emacs renderer demonstrably includes the complete tool name, complete arguments, and raw fallback behavior.
- Expanded call rendering uses Emacs data fields, not reconstruction from the collapsed summary.
- Missing argument data is rendered explicitly rather than making the call section disappear.
- TUI behavior is not part of task 167's acceptance or verification requirements.

## Risks

- Emacs may not currently receive full call data; if so, the smallest necessary RPC/event payload change should carry structured and/or raw call data without altering execution semantics.
- Existing specialized Emacs renderers may currently own part of the expanded detail view; the implementation should compose the generic `Call` section with them instead of replacing response-specific rendering.
- Pretty-printing differences can make tests brittle; formatters should produce deterministic output and tests should assert stable section labels and full content rather than incidental spacing where possible.
