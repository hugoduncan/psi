Goal: introduce a single canonical delegated-result publication model so delegated workflow completion semantics are explicit across execution, transcript delivery, background-job state, and adapter projection.

Intent:
- Reduce drift and repeated bug-fixing around `/delegate` result visibility by making delegated completion produce one explicit publication shape before side effects are applied.

Problem statement:
- The recent `/delegate` fix sequence showed that delegated result delivery was spread across several partially overlapping representations:
  - workflow run result
  - child-session assistant result
  - parent-session transcript injection
  - background-job terminal payload
  - notification fallback
  - append-entry fallback
  - RPC/TUI/Emacs external-message projection
- The strongest boundary problem was already corrected for bounded workflow callers via `prompt-execution-result-in!`: callers should consume the canonical prompt execution result directly instead of rereading journals.
- A remaining architectural seam is publication policy. `workflow_loader` async completion still decides visible delivery through several side-effect branches inline rather than first constructing one canonical delegated-result publication value.
- This makes it harder to reason about delivery invariants, easier for adapters to drift, and harder to test the domain decision separately from transport/UI effects.

Scope:
In scope:
- define one explicit delegated-result publication shape for workflow-loader async completion
- keep that publication shape as an internal workflow-loader decision artifact, not a new public API contract unless implementation work proves a wider contract is necessary
- extract completion/publication decision logic from ad hoc branching into a small pure canonical function
- preserve existing user-visible behavior for successful delegated completion
- keep current fallback behavior semantics explicit for non-chat-delivery cases
- add focused tests for both publication decision shape and applied side effects

Out of scope:
- redesign of workflow runtime result schemas outside delegated-result publication
- changing the conversational bridge shape for successful `/delegate` completion
- broad UI redesign in RPC, TUI, or Emacs
- changing RPC/TUI/Emacs payload or rendering contracts unless a tiny adjustment is strictly required to preserve existing behavior under the refactor
- changing bounded prompt execution ownership again; `prompt-execution-result-in!` remains authoritative for that seam
- expanding the task into failure/cancellation UX redesign unless needed to preserve current semantics under the new explicit publication model

Current architecture to follow:
- bounded execution callers consume canonical execution results directly
- transcript mutation remains backend-owned and session-targeted
- adapters project externally visible messages rather than reconstruct workflow semantics locally
- workflow-loader remains the place where delegation completion is orchestrated, but not where publication semantics should remain implicit

Minimum concepts:
- delegated completion outcome: run id, workflow name, parent session id, status, result text, error
- publication decision: whether the outcome should inject into chat, append a fallback entry, emit a notification, and/or suppress terminal background-job messaging
- publication application: side-effecting the chosen publication path without re-deciding policy inline

Required decision boundary:
- introduce one pure function that derives the canonical delegated-result publication value from completion inputs
- keep side effects in the existing orchestration path, but make them consume the already-derived publication value
- after the publication value is derived, side-effecting code must not recompute delivery policy inline

Behavior matrix to preserve:
- completed + `include_result_in_context` true + nonblank result text
  - inject the existing user/assistant bridge into the originating chat
  - suppress fallback `delegate-result` append-entry delivery
  - suppress terminal background-job message for that run
  - preserve the existing terminal background-job payload content
- completed + `include_result_in_context` true + blank result text
  - do not inject a blank assistant result into chat
  - preserve current non-chat fallback behavior semantics for this case
- completed + `include_result_in_context` false
  - preserve current non-chat fallback behavior semantics
- failed/cancelled/timed-out
  - preserve current non-chat fallback behavior semantics

Clarifications:
- “successful delegated completion” in this task means a completed run with nonblank result text when discussing the conversational chat-injection path.
- Notification behavior should remain exactly on the same cases as today unless a tiny change is required to preserve current semantics after the refactor.
- Terminal background-job message suppression should remain tied to the same case as today: the conversational chat-delivery success case.
- `append-entry` remains one implementation of fallback non-chat publication, not a new canonical API surface.

Acceptance:
- workflow-loader async completion first derives one canonical delegated-result publication value before applying side effects
- the publication value is explicit enough to drive:
  - parent-session transcript injection for successful result-bearing conversational delegation
  - fallback append-entry behavior when chat injection is not selected
  - notification behavior when chat injection is not selected
  - background-job terminal payload plus terminal-message suppression only for the conversational chat-delivery success case
- current successful `/delegate` behavior is preserved:
  - immediate acknowledgement remains
  - final result is injected into the originating chat once
  - fallback `delegate-result` append-entry is suppressed for that success case
- tests prove both:
  - decision-level behavior for these mandatory completion cases:
    - completed + include-result + nonblank result
    - completed + include-result + blank result
    - completed + no include-result
    - failed/cancelled/timed-out preserving current semantics
  - side-effect-level behavior still matches current visible semantics
- no side-effect branch in async completion re-decides publication policy after the publication value is derived

Notes:
- This task is a small architectural convergence/refactoring task, not a feature expansion.
- It should leave the project with a clearer rule: execution computes canonical results; publication derives canonical delivery intent; projection/adapters render the resulting transcript/events.
