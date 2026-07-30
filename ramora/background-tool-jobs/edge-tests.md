# Edge Tests

| ID | Scenario | Arrange | Assert |
|---|---|---|---|
| E1 | Attempt second background job for same tool call | Same `tool_call_id` starts job twice | Contract violation/rejection; still at most one job for tool call |
| E2 | Duplicate `job_id` collision | Attempt create with existing `job_id` | Rejected; uniqueness invariant preserved |
| E3 | Non-terminal updates do not inject | Running/pending-cancel updates occur | No synthetic assistant message created |
| E4 | Duplicate emit attempt | Emit requested after `terminal_message_emitted=true` | No additional terminal injection message |
| E5 | Best-effort cancel still completes | Cancel requested, tool finishes anyway | Final status allowed as `completed` |
| E6 | Cancel/complete race | Cancel arrives after execution already finished | Final status is `completed` |
| E7 | Idle completion wake-up | Thread idle when terminal observed | Runtime requests next turn boundary, then injects message |
| E8 | Cross-thread list/cancel isolation | Same `job_id` queried from non-origin thread | Not visible/cancel denied by thread scope |
| E9 | Inspect outside thread | Inspect from wrong thread | Canonical not-found-in-thread error |
| E10 | Payload exactly-over-limit formatting | Payload near policy thresholds | Correct branch selected (inline vs file), no malformed message |
| E11 | Manual retry request | `RetryJobRequested` invoked | Canonical "manual retry not supported" error |
| E12 | Process restart | Jobs exist, process restarts | Registry reinitialized; prior jobs not recovered |
| E13 | Internal retryable LLM HTTP error | Retryable HTTP error while job running | No terminal injection queued; external job status unchanged |
