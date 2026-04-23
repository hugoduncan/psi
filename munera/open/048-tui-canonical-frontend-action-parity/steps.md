- [x] Identify the exact TUI state/update seam for adapter-local frontend actions
  - confirm how `:frontend-action` results will enter `psi.tui.app.update/handle-dispatch-result`
  - confirm what minimal frontend-action context must be stored in TUI state during selection

- [x] Add focused TUI state shape for active frontend-action context
  - store request id when present
  - store canonical `:ui/action`
  - store enough handling-mode information to distinguish dialog-backed vs session-selector-backed actions
  - keep the state minimal and avoid duplicating shared action semantics

- [x] Add `:frontend-action` handling to `psi.tui.app.update/handle-dispatch-result`
  - route generic select actions into existing dialog rendering/input machinery
  - keep existing non-frontend command results unchanged

- [x] Implement canonical select-action → TUI dialog mapping helper
  - support shared `select-model` action
  - support shared `select-thinking-level` action
  - preserve labels and selected values in canonical shape

- [x] Wire dialog submit for frontend-action-backed dialogs
  - submit must produce canonical action-result semantics
  - preserve action name and `:ui/action`
  - preserve selected value shape
  - ensure plain non-frontend dialogs still behave unchanged

- [x] Wire dialog cancel for frontend-action-backed dialogs
  - cancel must produce canonical cancelled semantics
  - preserve action name and `:ui/action`
  - ensure plain non-frontend dialogs still behave unchanged

- [x] Add focused TUI tests for `select-model`
  - backend-requested action opens selectable affordance
  - submit path returns canonical action-result semantics
  - cancel path returns canonical cancelled semantics

- [x] Add focused TUI tests for `select-thinking-level`
  - backend-requested action opens selectable affordance
  - submit path returns canonical action-result semantics
  - cancel path returns canonical cancelled semantics

- [x] Choose and implement the session-oriented semantic-convergence case
  - preferred first case: `select-resume-session`
  - decide whether it should use existing session-selector UI or generic select dialog
  - preserve canonical action identity and selected value semantics

- [x] Add focused TUI proof for `select-resume-session` semantic convergence
  - preserve canonical `:ui/action`
  - preserve submitted/cancelled status semantics
  - preserve selected session-path value shape

- [x] Evaluate whether `select-session` naturally falls out of the new bridge
  - if small, include minimal semantic path/proof in this task
  - if not small, record the remaining gap in `implementation.md` and keep the slice bounded

- [x] Add optional bounded-failure proof if needed
  - malformed or unsupported frontend action does not corrupt TUI state

- [x] Run focused TUI tests and fix regressions
  - note exact test namespaces/files exercised
  - verify existing dialog behavior and existing `/resume` or selector behavior remain intact

- [x] Record implementation notes in `implementation.md`
  - seam used for TUI frontend-action ingress
  - final TUI state shape for active frontend-action context
  - whether session-oriented convergence used selector or dialog path
  - any remaining `select-session` follow-on gap

- [x] Address review follow-up: harden unsupported frontend-action handling
  - clear any stale frontend-action selector/dialog state before surfacing unsupported-action feedback
  - add focused proof that unsupported input leaves TUI state clean even when stale frontend-action state exists

- [x] Address review follow-up: reduce dialog-handling duplication
  - reuse shared select-dialog navigation/selection helpers for frontend-action-backed dialogs where practical
  - keep frontend-action-specific logic limited to canonical submit/cancel result emission

- [x] Address review follow-up: add focused `select-session` semantic proof
  - prove switch-session submit preserves canonical `:ui/action`, status, and selected value shape
  - prove fork-session submit preserves canonical `:ui/action`, status, and selected value shape
  - prove cancel preserves canonical cancelled semantics

- [x] Update `implementation.md` with post-review convergence notes
  - document unsupported-action hardening
  - document dialog-helper reuse decision
  - document added `select-session` proof coverage
