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

- Keep `/tree` as the interactive selection/switch workflow, but make the normal TUI view visibly advertise the same context/session structure and allow direct activation from that visible surface.
- Source the visible section from app-runtime-owned context projection and deliver it to the TUI through explicit `:context-updated` events rather than a TUI-local polling callback.
- Treat `:context-updated` as authoritative for present/replace/remove semantics of the discoverable session-context surface.
- Preserve backend-owned line action commands as the canonical source for visible-section activation rather than inventing TUI-local switch semantics.

Discoveries

- TUI already had good canonical parity for actual session switching because `/tree` and backend-requested `:select-session` both converge on the same selector/action flow.
- The practical gap was discoverability in the normal view, not switching semantics.
- The app-runtime already had the exact authoritative projection needed via `context-summary/context-widget`, but the TUI startup opts did not yet expose it directly.

Risks / snags

- Direct activation now routes through backend-projected line action commands, so future widget action-shape changes should continue to preserve backend ownership rather than teaching the TUI alternate local semantics.
- The normal-view interaction is intentionally lightweight (`Ctrl+J/K`, `Alt+Enter`) rather than a broader transcript cursor/navigation redesign.
- Task 049 was reopened to address review feedback and the follow-on fixes are now implemented and verified in focused and broader relevant test slices.

Review-fix follow-on evaluation

- Evaluated whether the visible context-widget interaction helpers in `psi.tui.app.update` should move to a dedicated helper namespace.
  - Decision: not yet.
  - Reason: the current helpers are few, tightly coupled to the TUI idle/update state machine, and small enough that extraction would add namespace indirection without reducing conceptual load.
  - Trigger for later extraction: if more visible widget interaction modes accumulate beyond the current context-session-tree selection/activation path, extract them as a focused TUI widget-interaction helper surface.
- Evaluated whether TUI context invalidation should converge further with the generalized RPC projection-listener/invalidation path.
  - Decision: not in this task.
  - Reason: the current TUI ownership is intentionally narrow and event-driven; `:context-updated` already gives authoritative add/preserve/remove semantics at the runtime transition points this task needs.
  - Trigger for later convergence: if TUI begins to require broader live projection invalidation semantics outside current context transition points, revisit a shared listener/invalidation abstraction rather than growing ad hoc TUI refresh paths.
