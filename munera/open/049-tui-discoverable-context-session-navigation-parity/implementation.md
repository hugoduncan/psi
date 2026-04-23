Implementation notes

Use this file as append-only local memory while executing task 049.

Initial expectations
- backend-projected `session-tree-widget` remains authoritative for visible session/context navigation in the normal TUI view
- TUI should own only storage, rendering, interaction, and cleanup for that projected widget
- `/tree` may remain as direct command plumbing, but it must not drift into a competing tree model

Execution log

- Inspected current TUI update/render path.
- Confirmed `/tree` already reuses canonical shared selector/session-switch actions via `session-selector-fn` and frontend-action plumbing.
- Implemented runtime-supplied `:context-widget-fn` so the normal TUI view can consume authoritative backend-projected session-tree widget data without inventing a second local tree model.
- Added TUI state storage for `:context-session-tree-widget` and refresh ownership in `update-tick-state`.
- Added visible normal-view rendering for a discoverable `Session Context` section driven by the authoritative widget.
- Added focused tests for visible-section presence, absence, preservation across unrelated refreshes, and authoritative removal.
- Reworked ownership so the discoverable session/context surface is now updated through explicit `:context-updated` events instead of TUI-local polling of a `:context-widget-fn` callback.
- Added visible-section selection state plus direct activation from the normal TUI view using the backend-projected line action command.
- Preserved `/tree` as the underlying canonical navigation path for session switching while making the visible section directly actionable.

Decisions

- Keep `/tree` as the interactive selection/switch workflow, but make the normal TUI view visibly advertise the same context/session structure through a read-only discoverable section.
- Source the visible section from app-runtime-owned context projection (`context-summary/context-widget`) supplied through a dedicated runtime callback, rather than synthesizing it from extension UI widgets or rebuilding it locally inside the TUI.
- Treat each refresh of `:context-widget-fn` as authoritative: present widget replaces state; `nil` removes it.

Discoveries

- TUI already had good canonical parity for actual session switching because `/tree` and backend-requested `:select-session` both converge on the same selector/action flow.
- The practical gap was discoverability in the normal view, not switching semantics.
- The app-runtime already had the exact authoritative projection needed via `context-summary/context-widget`, but the TUI startup opts did not yet expose it directly.

Risks / snags

- The visible section is currently informational/discoverable; actual activation still goes through `/tree` / selector flow rather than direct normal-view cursor interaction.
- Review feedback concluded this does not fully satisfy the task acceptance criteria around activation from the visible surface.
- Review feedback also identified the runtime-supplied `:context-widget-fn` polling seam as weaker parity than consuming canonical `context/updated` ownership directly.
- Task 049 has therefore been reopened for follow-on convergence on direct visible-surface activation and canonical context-update ownership.
