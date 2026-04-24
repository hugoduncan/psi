Goal: make session/context navigation in the TUI discoverable and parity-aligned with the shared backend-projected context/session-tree semantics, rather than relying primarily on the hidden `/tree` command path.

Intent:
- preserve the existing useful `/tree` workflow while adding a discoverable TUI surface for context/session navigation
- consume canonical backend-projected context/session-tree data where that is part of the shared adapter contract
- close the gap between “TUI can do it if you know the command” and “TUI visibly supports the workflow”

Context:
- app-runtime has canonical context/session-tree summary projection support
- `context/updated` can carry a backend-projected `session-tree-widget`
- Emacs consumes `context/updated`, stores the context snapshot, renders the session-tree widget, preserves it across unrelated widget refreshes, and treats later `context/updated` as authoritative for removal
- TUI already has meaningful `/tree` coverage:
  - open selector
  - render hierarchy and badges
  - switch sessions
  - show fork points
  - consume shared selector ordering
- the current gap is not that TUI lacks session-tree logic; it is that the normal TUI view does not yet provide a visible, discoverable session/context navigation surface driven by the backend-projected context data

Problem:
- a TUI user may still need prior knowledge of `/tree` to access session/context navigation
- this weakens practical parity with Emacs even though shared backend context/tree semantics already exist
- if TUI solves this by inventing a second local model instead of consuming the backend-projected context surface, adapter drift will increase

Minimum concepts:
- context snapshot: canonical backend context state owned by app-runtime
- session-tree widget: authoritative backend-projected navigation surface derived from context
- discoverable navigation surface: a visible session/context section in the normal TUI view rendered from the session-tree widget
- session selection action: the canonical navigation action triggered from the visible surface
- lifecycle authority: `context/updated` is authoritative for creation, preservation, and removal of the discoverable surface

Chosen shape:
- the TUI will render a visible session/context section in the normal TUI view when the canonical backend context snapshot includes a `session-tree-widget`
- this visible section will be driven by the backend-projected widget rather than a separately invented local tree model
- the visible section is embedded in the normal TUI view; it is not a command-only affordance
- `/tree` remains available as a direct command affordance, but it must preserve or route through the same canonical navigation workflow rather than define a competing model
- for this task, the discoverable visible section is rendered only from authoritative backend-projected context/session-tree data; if that data is absent, the visible section is not synthesized from an independent local model

Scope:
In scope:
- expose session/context navigation in the TUI through a visible section in the normal TUI view driven by canonical backend-projected context/session-tree semantics
- show that visible section only when the authoritative backend context includes a `session-tree-widget`, which in current semantics corresponds to multi-session context
- preserve and integrate the existing `/tree` workflow rather than removing it blindly
- ensure the TUI correctly handles lifecycle semantics for the context/session-tree surface, including:
  - present when multiple sessions exist and authoritative context includes the widget
  - absent when only one session exists and authoritative context omits the widget
  - preserved across unrelated widget refreshes when the backend still includes it
  - removed when a later authoritative context update removes it
- add focused proof for the visible/discoverable navigation surface and its lifecycle semantics

Out of scope:
- reproducing the exact Emacs buffer layout or magit-section style in the TUI
- inventing new backend navigation semantics unrelated to the existing context/session-tree model
- broad TUI navigation redesign beyond this workflow
- changing how sessions are created, forked, labeled, or ordered beyond consuming existing canonical backend projection and ordering semantics

Design constraints:
- backend/app-runtime remains authoritative for context/session-tree semantics
- prefer consuming `context/updated` / canonical session-tree projection data over rebuilding tree semantics locally
- `/tree` may remain as a direct command affordance, but it should no longer be the only practical path to the workflow
- keep rendering adapter-appropriate while preserving shared semantics and actions

Acceptance:
- when authoritative backend context includes a `session-tree-widget`, the TUI renders a visible session/context section in the normal TUI view from that widget
- when authoritative backend context does not include that widget, the visible section is not shown
- the workflow is practical without prior knowledge of `/tree`
- the TUI does not construct a competing local tree model for the visible section
- the TUI preserves existing useful `/tree` behavior or cleanly routes it through the same canonical navigation model
- focused proof covers:
  - the visible session/context section appears when multiple sessions exist and authoritative context includes the widget
  - the visible section is absent when only one session exists and authoritative context omits the widget
  - hierarchy/current/runtime indicators remain correct
  - activating a session from the visible section switches to that session correctly
  - the active session remains visibly indicated after refresh
  - unrelated widget refreshes do not destroy or corrupt the visible section when backend context still includes it
  - a later authoritative `context/updated` can remove the visible section cleanly without leaving stale selection or focus state

Why this task is small and clear:
- it targets one workflow cluster: discoverable session/context navigation
- it builds on an existing TUI strength instead of replacing it
- it is tightly constrained by existing backend projection semantics
