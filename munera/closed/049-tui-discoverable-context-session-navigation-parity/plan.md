Approach

Implement task 049 by making the TUI consume and render the canonical backend-projected `session-tree-widget` from `context/updated` as a visible session/context section in the normal TUI view, while preserving `/tree` as an alternate entry to the same workflow rather than a competing model.

The plan keeps backend/app-runtime authoritative for context/session-tree semantics and limits TUI ownership to storage, rendering, interaction, and lifecycle handling of the projected widget.

Implementation shape

1. Trace the current TUI context update path
- Identify where `context/updated` is received, normalized, and stored in TUI state.
- Confirm how the canonical context snapshot and any widget payloads are currently represented.
- Identify the existing `/tree` command path and the selector/session-switch path it ultimately drives.

2. Introduce or tighten canonical TUI state for the projected session-tree widget
- Store the backend-projected `session-tree-widget` in TUI state as authoritative context-derived UI data.
- Ensure the state shape supports:
  - widget present when included by authoritative `context/updated`
  - widget absent when omitted by authoritative `context/updated`
  - preservation across unrelated widget/UI refreshes
- Do not derive or persist a second independent local tree model for the visible section.

3. Render a visible section in the normal TUI view from the projected widget
- Add a visible session/context section in the normal TUI view driven by the stored `session-tree-widget`.
- Reuse existing selector/session navigation behavior where possible instead of re-implementing switching semantics.
- Preserve canonical indicators from the backend-projected widget, including hierarchy/current/runtime markers.

4. Align interaction with canonical navigation behavior
- Ensure activating an entry from the visible section switches to the correct session.
- Ensure the active/current session remains visibly indicated after selection and later refresh.
- Ensure focus/selection state is cleaned up correctly when a later authoritative context update removes the widget.

5. Preserve `/tree` without letting it drift
- Keep `/tree` available as a direct command affordance.
- Make the backend-projected widget authoritative for hierarchy/order/indicator semantics.
- Where practical, route `/tree` through the same session selection path used by the visible section.
- If `/tree` still needs local command plumbing, that plumbing must not introduce divergent hierarchy, ordering, or indicator semantics.

6. Add focused proof
- Add or update tests covering:
  - visible section appears when authoritative context includes a multi-session `session-tree-widget`
  - visible section is absent when authoritative context omits it for single-session context
  - hierarchy/current/runtime indicators render correctly
  - activating from the visible section switches sessions correctly
  - active indication survives later unrelated refreshes
  - unrelated widget refreshes do not remove/corrupt the visible section while authoritative context still includes it
  - a later authoritative `context/updated` removes the visible section cleanly without stale selection/focus state
- Keep test scope centered on discoverable navigation parity rather than broad TUI layout concerns.

Risks and decisions

- Main risk: accidental duplication between the existing `/tree` local model and the new visible section.
  - Mitigation: make the projected widget authoritative for the visible section and reuse existing navigation actions rather than tree derivation logic.

- Main UX decision: exact placement and rendering within the normal TUI view.
  - Constraint: it must be visibly discoverable during normal use, but remains adapter-appropriate rather than Emacs-identical.

- Main lifecycle risk: unrelated refreshes or later authoritative removal leaving stale widget/focus state.
  - Mitigation: treat `context/updated` as the authoritative create/remove signal and keep non-context refreshes from clearing context-owned widget state.

Done means

- TUI visibly exposes session/context navigation in the normal TUI view from canonical backend-projected context data.
- The visible section is authoritative when the backend includes `session-tree-widget` and absent when it does not.
- `/tree` remains useful without becoming a divergent model.
- Focused tests prove visible-section lifecycle and interaction semantics.
