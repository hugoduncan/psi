Goal: Adopt `magit-section` as the primary structured-rendering primitive across the psi Emacs UI, replacing ad-hoc text insertion with collapsible, navigable, keyboard-accessible sections.

Context:
- The psi Emacs UI currently renders assistant messages, tool output, thinking blocks, and session navigation through manual text insertion into a single buffer region model.
- `magit-section` (part of the `magit-section` package, distributed independently of Magit itself) provides a general-purpose structured buffer rendering library: collapsible sections, point-motion commands, and face/keymap composition — widely available in any Emacs with Magit or `magit-section` installed.
- Task `021` specifically targets the session-tree buffer rendered with `magit-section`; this task is the broader umbrella for adopting `magit-section` as a first-class dependency across the Emacs adapter.
- The psi Emacs adapter is the `components/emacs-ui/` component; rendering logic lives across `psi-assistant-render.el`, `psi-tool-rows.el`, `psi-widget-renderer.el`, and `psi-widget-projection.el`.
- The RPC/runtime boundary is stable; no backend changes are required for pure rendering improvements.

Required behaviour:
- `magit-section` is declared as a required Emacs package dependency in the psi package metadata.
- Tool-output rows are rendered as magit sections: collapsible by default when output exceeds a threshold, with the tool name as section heading and output as section body.
- Thinking/reasoning blocks are rendered as collapsible magit sections, collapsed by default.
- Assistant message content blocks (text, thinking, tool calls) are rendered as a section hierarchy, enabling point-motion (`magit-section-forward`/`backward`) to navigate between blocks.
- The session-tree buffer (task `021`) uses `magit-section` for session and message hierarchy — this task ensures the shared infrastructure and dependency are in place for `021` to land cleanly.
- Standard magit-section keybindings (`TAB` to toggle, `M-1`/`M-2`/`M-3` for visibility levels) work in psi buffers where sections are present.

Acceptance:
- `magit-section` is listed as a package dependency in the psi Emacs package declaration.
- Tool output rows collapse/expand via `TAB` when point is on the section heading.
- Thinking blocks collapse/expand via `TAB`; collapsed by default.
- Point-motion commands navigate between assistant content sections.
- The session-tree buffer (task `021`) can be implemented using the section infrastructure introduced here without additional dependency wiring.
- Behaviour is covered by focused Emacs ERT tests for section rendering and collapse state.

Constraints:
- `magit-section` package only — do not depend on the full `magit` package.
- No backend (JVM/Clojure) changes; this is an Emacs adapter concern only.
- Preserve existing buffer text content: rendering changes must not alter what text appears, only how it is structured and navigable.
- Do not break the RPC event handler contract; section rendering is a pure projection concern downstream of events.
- Keep the existing region/overlay model intact where sections are not yet adopted; incremental adoption is acceptable.
