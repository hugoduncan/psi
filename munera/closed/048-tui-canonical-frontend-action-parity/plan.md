Approach:
- implement TUI canonical frontend-action parity as an adapter-local bridge over existing shared action semantics
- keep shared action definitions and normalization in `psi.app-runtime.ui-actions`
- avoid RPC-specific coupling in TUI; preserve semantic parity rather than transport identity
- use the smallest existing TUI seams:
  - ingress: `psi.tui.app.update/handle-dispatch-result`
  - generic select rendering/input: existing `:active-dialog` path (`render-dialog` + `handle-dialog-key`)
  - existing session-oriented selector path for convergence proof where appropriate

Key decisions:
1. Introduce a TUI adapter-local `:frontend-action` result path
- treat `:frontend-action` in TUI the same way RPC treats `ui/frontend-action-requested`, but at adapter-local result/dispatch level
- this keeps backend/shared action semantics canonical while respecting that TUI is not an RPC adapter
- the new TUI path should carry at least canonical `:ui/action`, and request id when present

2. Split rendering strategy by action family
- generic select actions:
  - `select-model`
  - `select-thinking-level`
  should map onto the existing TUI `:active-dialog` select affordance
- session-oriented actions:
  - `select-resume-session`
  - `select-session`
  may continue to use the session-selector path if that yields cleaner parity and lower change risk
- do not force all action families into one rendering primitive if that adds complexity without semantic benefit
- do require one shared semantic handling layer for action identity, submit/cancel status, and selected value shaping

3. Produce canonical action-result semantics through TUI-local dispatch/result flow
- on submit/cancel, TUI should produce the same semantic fields shared backend handling expects:
  - request id when available
  - action name
  - preserved `:ui/action`
  - submitted/cancelled status
  - selected value in canonical shape
- use `psi.app-runtime.ui-actions/action-result` as the canonical normalization/reference point where helpful
- avoid inventing TUI-only action-result shapes

4. First convergence proof uses `select-resume-session`
- it has the smallest selected-value shape (session path string)
- it is already close to current TUI `/resume` behavior
- it gives proof that existing selector workflows can align with the shared action semantics without taking on the full `select-session` switch/fork branching immediately

Implementation steps:

1. Add a TUI `:frontend-action` dispatch-result branch
- extend `psi.tui.app.update/handle-dispatch-result` to recognize `{:type :frontend-action ...}`
- define a small adapter-local representation in state sufficient to remember the active frontend action context during selection/dialog interaction
- keep this state minimal and explicit, for example:
  - current action request id if present
  - canonical `:ui/action`
  - action family / handling mode (`:dialog-select` vs `:session-selector`)
- do not duplicate the shared action model; store it and adapt from it

2. Implement generic select-action mapping for `select-model` and `select-thinking-level`
- add a helper that converts canonical select actions from `app-runtime.ui-actions` into the existing TUI dialog shape:
  - title/prompt
  - options as label/value pairs
- route `select-model` and `select-thinking-level` through this helper
- use existing `render-dialog` + `handle-dialog-key` behavior for navigation/submit/cancel
- ensure the chosen option value is preserved in the shared canonical shape:
  - model → `{:provider ... :id ...}`
  - thinking level → string/keyword shape accepted by shared normalization

3. Add TUI-local result emission for dialog-backed frontend actions
- when the active dialog corresponds to a frontend action, resolve/cancel should not only mutate dialog UI state
- it must also produce canonical action-result semantics back through the TUI adapter boundary actually used by the runtime
- keep this logic localized so normal non-frontend dialogs continue to work unchanged
- if needed, use a small adapter-local dispatcher/helper to separate:
  - plain dialog resolution
  - frontend-action submit/cancel resolution

4. Add a session-oriented convergence path for `select-resume-session`
- choose one of two paths based on smallest clear implementation:
  a. map `select-resume-session` into the existing session-selector UI with preserved `:ui/action`
  b. or map it into the same generic select-dialog path if that produces simpler canonical parity
- preference:
  - use the existing session-selector path if it preserves current `/resume` behavior cleanly and avoids flattening richer selector behavior
- ensure submit/cancel from that flow produces canonical action-result semantics

5. Decide whether `select-session` needs immediate implementation or proof-only treatment in this slice
- if the new frontend-action bridge naturally supports `select-session`, include it
- if not, keep the first slice bounded:
  - implement `select-model`
  - implement `select-thinking-level`
  - implement/prove `select-resume-session`
  - leave `select-session` as the immediate next slice within the same task if still needed
- only do this if the task acceptance is updated to reflect staged completion; otherwise include a minimal `select-session` semantic path before closing the task

Proof plan:

1. Add TUI-focused tests for generic select frontend actions
- new or existing TUI runtime/update tests should prove:
  - backend-requested `select-model` opens selectable TUI affordance
  - submit produces canonical action-result semantics
  - cancel produces canonical cancelled semantics
  - backend-requested `select-thinking-level` opens selectable TUI affordance
  - submit produces canonical action-result semantics
  - cancel produces canonical cancelled semantics
- keep tests at the TUI adapter boundary, not just pure shared normalization unit tests

2. Add one semantic-convergence test for `select-resume-session`
- prove that a session-oriented TUI selection workflow preserves:
  - canonical action identity
  - preserved `:ui/action`
  - submitted/cancelled status
  - selected session-path value shape
- prefer proving this in existing TUI selector/runtime tests rather than only in app-runtime or agent-session tests

3. Optional robustness test
- malformed/unsupported frontend action does not corrupt state and fails in a bounded, diagnosable way

Risks / watchpoints:
- overfitting the TUI implementation to the RPC transport vocabulary instead of the shared semantics
- conflating plain extension dialogs with frontend-action-backed dialogs in a way that breaks existing extension UI behavior
- forcing session-selector and generic dialog handling into one abstraction too early
- broadening the slice to full `select-session` fork/switch convergence before `select-model` / `select-thinking-level` parity is landed

Stop conditions for the first slice:
- TUI can accept `:frontend-action` results for `select-model` and `select-thinking-level`
- TUI renders them via the existing select dialog
- submit/cancel paths preserve canonical action semantics
- one session-oriented action (`select-resume-session`) is proven semantically aligned
- no new legacy compatibility branches are introduced
