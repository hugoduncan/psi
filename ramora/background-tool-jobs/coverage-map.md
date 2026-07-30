# Coverage Map (spec behavior → tests)

Scope: `spec/background-tool-jobs.allium`

- Dual-mode tool result (sync vs background) → N1, N2, B1
- `job_id` only for background starts → N1, N2
- One background job per tool invocation → N2, E1
- Globally unique `job_id` → E2, B2
- Thread-scoped visibility/controls → N8, E8, E9
- In-memory only tracking (no restart recovery) → E12
- Cancel by user/agent → N6
- Best-effort cancel (`pending_cancel`, may still complete) → N6, E5
- Completion wins cancel race when already finished → E6
- Terminal states only trigger injection → N3, E3
- One synthetic assistant message per terminal job → N4, E4
- Completion-time ordering across multiple terminal jobs → N5, B3
- Turn-boundary injection + idle terminal triggers next boundary → N4, E7
- At-most-once injection semantics → E4, B4
- Payload constraints follow tool output limits → N7, E10, B5
- Oversized payload writes temp file and message references it → N7, E10
- `list jobs` default non-terminal set → N8
- `list jobs` explicit status filtering → N9
- `inspect job` in-thread success / cross-thread rejection → N10, E9
- Manual retry unsupported → E11
- Internal retryable LLM HTTP errors are internal-only (no external terminal injection) → E13
- Terminal retention bounded to 20 per thread, evict oldest terminal, preserve non-terminal → B6, B7, B8
