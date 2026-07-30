# Suggested Test Placement

- Unit:
  - `components/agent-session/test/...background_jobs*_test.clj`
  - `components/agent-session/test/...job_injection*_test.clj`
- Integration:
  - agent-session runtime + tool dispatcher integration tests
  - synthetic assistant message append/turn-trigger pipeline tests
- E2E:
  - REPL/TUI/Emacs/RPC parity tests for list/inspect/cancel and terminal injection visibility

## Pass Criteria

- All N/E/B IDs implemented and green.
- No duplicate terminal injections under concurrency.
- Ordering and retention behavior deterministic.
- Cross-thread isolation preserved for list/inspect/cancel.
- Oversize payload path is observable and debuggable (temp file reference).
