Initialized from user request on 2026-04-29.

Intent

- Create a small convergence/refactoring task that makes delegated-result publication explicit and canonical without changing current successful `/delegate` behavior.

Starting point

- `/delegate` result delivery is now working end-to-end.
- The recent fix sequence identified one corrected seam (`prompt-execution-result-in!` for bounded callers) and one remaining shaping seam: async delegated completion still mixes policy derivation and side effects inline.

Implementation notes

- Added `extensions.workflow-loader.orchestration/delegated-result-publication` as the single pure decision point for async delegated completion publication.
- `on-async-completion!` now derives one publication map first, then applies background-job terminal marking, chat injection, notification, and append-entry side effects from that map without re-deciding policy inline.
- Preserved current semantics:
  - completed + include-result + nonblank result => inject into parent chat, suppress fallback append-entry, suppress terminal background-job message
  - blank-result include path => no chat injection, retain non-chat fallback semantics
  - include-result false => retain append-entry fallback semantics
  - non-completed statuses => retain non-chat semantics
- Added decision-level tests for the mandatory publication cases alongside the existing side-effect-level completion test coverage.
- Tightened focused live `/delegate` e2e harnesses to assert the preserved semantic contract (ack + user bridge + assistant result + no visible filler) rather than one exact model-specific assistant string.

Review note

- Review verdict: acceptable implementation; matches the task design and fits the pre-existing workflow-loader orchestration architecture.
- Strengths:
  - publication policy is now derived once by a pure function and then consumed by side-effecting code
  - transcript mutation remains backend-owned and session-targeted
  - adapters continue to project surfaced events rather than reconstruct workflow semantics locally
  - decision-level and side-effect-level tests cover the intended seam well
- Minor review feedback:
  - the `delegated-result-publication` shape is explicit and effective, but carries some duplication across `:completion`, `:background-job`, and `:chat-injection`; acceptable for this task, but worth watching if the pattern spreads
  - the focused live TUI/Emacs `/delegate` e2e helpers were relaxed from exact model text to semantic checks, which is correct, but the current assistant-result predicates are slightly broad because they may accept any later non-ack assistant line rather than specifically the post-bridge assistant result
- No blocking issues were found.
- Follow-up execution after review:
  - tightened both focused live `/delegate` e2e helpers so assistant-result detection now requires the assistant result to appear after the observed user bridge marker
  - re-ran focused TUI and Emacs live `/delegate` verification successfully
  - reassessed the internal `delegated-result-publication` shape and kept it unchanged because there is not yet concrete reuse pressure that justifies introducing a shared `:outcome` subshape
