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
- the current gap is not that TUI lacks all tree logic; it is that the discoverable, backend-projected context surface is missing or unproven

Problem:
- a TUI user may still need prior knowledge of `/tree` to access session/context navigation
- this weakens practical parity with Emacs even though shared backend context/tree semantics already exist
- if TUI solves this by inventing a second local model instead of consuming the backend-projected context surface, adapter drift will increase

Scope:
In scope:
- expose session/context navigation in the TUI through a discoverable visible surface driven by canonical backend-projected context/session-tree semantics where available
- preserve and integrate the existing `/tree` workflow rather than removing it blindly
- ensure the TUI correctly handles lifecycle semantics for the context/session-tree surface, including:
  - present when multiple sessions exist
  - absent when only one session exists
  - preserved across unrelated widget refreshes when the backend still says it exists
  - removed when a later authoritative context update removes it
- add focused proof for the visible/discoverable navigation surface and its lifecycle semantics

Out of scope:
- reproducing the exact Emacs buffer layout or magit-section style in the TUI
- inventing new backend navigation semantics unrelated to the existing context/session-tree model
- broad TUI navigation redesign beyond this workflow

Design constraints:
- backend/app-runtime remains authoritative for context/session-tree semantics
- prefer consuming `context/updated` / canonical session-tree projection data over rebuilding tree semantics locally
- `/tree` may remain as a direct command affordance, but it should no longer be the only practical path to the workflow
- keep rendering adapter-appropriate while preserving shared semantics and actions

Acceptance:
- TUI exposes a discoverable session/context navigation surface tied to canonical backend-projected context/session-tree semantics
- the workflow is practical without prior knowledge of `/tree`
- TUI preserves existing useful `/tree` behavior or cleanly routes it through the same canonical navigation model
- focused proof covers:
  - visible session/context surface appears when multiple sessions exist
  - surface is absent when only one session exists
  - hierarchy/current/runtime indicators remain correct
  - unrelated widget refreshes do not destroy the context surface when backend context still includes it
  - a later authoritative context update can remove it
  - selection/focus behavior remains correct from the discoverable surface

Why this task is small and clear:
- it targets one workflow cluster: discoverable session/context navigation
- it builds on an existing TUI strength instead of replacing it
- it is tightly constrained by existing backend projection semantics
