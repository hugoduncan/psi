Goal: raise the TUI to parity with Emacs for live operator-awareness during day-to-day use by improving visibility of background activity, projected statuses, and shared action-result feedback.

Intent:
- make the TUI as operationally legible as Emacs for normal interactive work
- build on the TUI’s already-strong footer/status baseline rather than redoing it
- close the gap in live feedback: what is happening now, what just succeeded/failed/cancelled, and what asynchronous work is active

Context:
- TUI already renders a useful shared footer with:
  - worktree/branch
  - session display name
  - usage/cost/context stats
  - provider/model/thinking summary
  - extension statuses
- TUI already renders visible notifications from canonical projection data and has proof for background-jobs widget rendering from canonical projection snapshots
- Emacs has richer event-driven projection/status behavior and stronger proof around background-job/status surfaces and action-result diagnostics
- the likely remaining parity gap is not baseline footer rendering but richer live feedback and operational visibility

Problem:
- the TUI may not yet surface the same breadth of live operational cues as Emacs, especially around:
  - background-job activity and status changes
  - extension/status refresh behavior over time
  - submit/cancel/fail feedback for shared frontend-action workflows
  - other short-lived but important operator notifications
- without this, the TUI remains weaker for sustained operation even if its static footer is already good

Scope:
In scope:
- improve TUI live visibility for backend-projected operator-awareness surfaces already part of the shared runtime direction
- ensure background-job and status information is usefully visible in live operation, not only in snapshot-style tests
- ensure frontend-action submit/cancel/fail outcomes are visible enough for a TUI operator to understand what happened
- add focused proof for the live feedback behaviors introduced in this slice

Out of scope:
- redesigning background-job semantics in the backend
- inventing TUI-only diagnostic semantics where shared backend projection/action-result data already exists
- broad UX experimentation unrelated to practical operator awareness

Design constraints:
- preserve canonical backend ownership of statuses, widgets, notifications, and action-result semantics
- prefer consuming shared projection/update surfaces over building TUI-local status reconstruction
- define parity by operator usefulness, not by matching Emacs text exactly

Acceptance:
- TUI provides materially useful live visibility for backend-projected background activity and statuses during normal operation
- TUI provides visible feedback for frontend-action outcomes, including at least submitted/cancelled/failed cases where those results are part of the shared action flow
- focused proof covers at least:
  - background-job/status visibility from live projection/update paths
  - visible feedback for a cancelled shared frontend action
  - visible feedback for a failed shared frontend action
  - preservation of transcript/editor usability while these signals are shown
- existing footer/status strengths remain intact

Why this task is small and clear:
- it targets one workflow cluster: live operator awareness
- it builds on existing TUI strengths
- it stays anchored to already-shared backend projections and result semantics
