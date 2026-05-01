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
