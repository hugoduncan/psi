2026-04-23

Implemented TUI canonical frontend-action parity as an adapter-local bridge over shared `psi.app-runtime.ui-actions` semantics.

Seams used
- ingress for backend-requested actions: `components/tui/src/psi/tui/app/update.clj` via `handle-dispatch-result` on `{:type :frontend-action ...}`
- generic dialog-backed frontend actions: local frontend-action dialog state in TUI (`:frontend-action/dialog`, `:frontend-action/request-id`, `:frontend-action/ui-action`)
- session-oriented frontend actions: session-selector state tagged with `:frontend-action? true`
- backend application of canonical action results in TUI runtime wiring: `components/app-runtime/src/psi/app_runtime.clj` via `frontend-action-handler-fn!`

Final TUI state shape
- dialog-backed frontend actions:
  - `:frontend-action/request-id`
  - `:frontend-action/ui-action`
  - `:frontend-action/dialog`
- selector-backed frontend actions:
  - same frontend-action identity fields above
  - `:session-selector` tagged with `:frontend-action? true`
  - `:session-selector-mode` remains `:resume` or `:tree`
- plain legacy TUI selectors remain unchanged and are not tagged as frontend actions

Implementation choices
- `select-model` and `select-thinking-level` use the existing TUI dialog rendering surface, but submit/cancel now route through explicit canonical frontend-action result generation instead of extension dialog resolution.
- `select-resume-session` and `select-session` use the existing TUI session-selector surface when requested as frontend actions.
- existing command-local `/tree` behavior remains intact; frontend-action selector handling is distinguished by explicit `:frontend-action?` tagging.
- TUI runtime now returns canonical frontend-action result semantics to an app-runtime-owned handler instead of inventing TUI-local payloads.

Runtime bridge behavior in app-runtime
- `/model` now returns `{:type :frontend-action :ui/action ...}` in TUI with the canonical shared model picker action.
- `/thinking` now returns `{:type :frontend-action :ui/action ...}` in TUI with the canonical shared thinking picker action.
- `/resume` now returns `{:type :frontend-action :ui/action ...}` in TUI with the canonical shared resume-session action.
- submitted frontend-action results are applied in app-runtime by `frontend-action-handler-fn!`, which:
  - sets model for `select-model`
  - sets thinking level for `select-thinking-level`
  - resumes sessions for `select-resume-session`
  - switches/forks sessions for `select-session`
- cancelled frontend-action results surface the canonical shared cancellation message via ordinary TUI text command results.

Proof added
- focused TUI tests for dialog-backed canonical frontend actions:
  - `select-model` submit
  - `select-model` cancel
  - `select-thinking-level` submit
  - `select-thinking-level` cancel
- focused TUI semantic-convergence proof for session-oriented canonical frontend actions:
  - `select-resume-session`
  - `select-session` switch submit
  - `select-session` fork submit
  - `select-session` cancel
- bounded-failure proof:
  - unsupported frontend action reports a clear assistant message and clears any stale frontend-action selector/dialog state without corrupting input editing
- existing `/tree` selector tests remained green, proving no regression of plain TUI selector semantics.

Post-review follow-up changes
- unsupported frontend-action handling now clears stale frontend-action state before surfacing unsupported-action feedback.
- frontend-action select dialogs now reuse shared dialog-selection helpers from `components/tui/src/psi/tui/app/support.clj` for selected-value lookup and up/down movement.
- semantic unification is complete for the supported action family, while presentation remains intentionally split:
  - dialog-backed for `select-model` and `select-thinking-level`
  - session-selector-backed for `select-resume-session` and `select-session`

Focused verification run
- `clojure -M:test --focus psi.tui.app-update-runtime-test --focus psi.tui.app-session-selector-test`
- result: `35 tests, 154 assertions, 0 failures`

Notes
- this slice achieves semantic parity with RPC/Emacs frontend-action handling without forcing TUI through RPC transport framing.
- canonical shared action identity and result normalization remain owned by `psi.app-runtime.ui-actions`.
