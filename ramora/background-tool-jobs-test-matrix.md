# Background Tool Jobs Test Matrix

Scope: `spec/background-tool-jobs.allium`

Goal: cover nominal, edge, and boundary behavior for tool-started background jobs, lifecycle tracking, terminal chat injection, and thread-scoped controls.

## Coverage Map (spec behavior → tests)

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

---

## Nominal Tests

| ID | Scenario | Arrange | Assert |
|---|---|---|---|
| N1 | Synchronous tool completion | Tool invocation completes inline | Response mode is `synchronous`; no `job_id`; no background job record |
| N2 | Background start response | Tool starts background job | Response mode is `background`; includes `job_id` + `running`; one job record created |
| N3 | Terminal status transition | Running job reaches `completed`/`failed`/`cancelled`/`timed_out` | Job status becomes terminal; `completed_at` and terminal payload recorded |
| N4 | Single terminal injection | Terminal job, turn boundary reached | Exactly one synthetic assistant message appended for that job; turn triggered |
| N5 | Ordered multi-job injection | Two+ jobs terminal before boundary | Injection order follows completion time oldest→newest |
| N6 | Cancel request (user + agent) | Running job, cancel requested by each actor type | Status moves to `pending_cancel`; cancellation request sent to tool runtime |
| N7 | Oversize payload path | Terminal payload larger than policy limits | Payload written to temp file; synthetic message includes temp file reference |
| N8 | Default list behavior | Thread with mixed statuses | `list jobs` default returns only `running` + `pending_cancel` |
| N9 | Explicit status filter | Thread with mixed statuses | `list jobs(statuses=...)` returns only requested statuses |
| N10 | Inspect in-thread | Request inspect from originating thread | Full job record returned |

## Edge Tests

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

## Boundary Tests

| ID | Scenario | Arrange | Assert |
|---|---|---|---|
| B1 | Sync/async boundary | Tool finishes at threshold between inline and background path | Exactly one mode chosen; never both result and job start |
| B2 | Global uniqueness at scale | High-volume concurrent job creation | No duplicate `job_id` across runtime |
| B3 | Same timestamp ordering tie | Multiple completions with equal/near-equal timestamps | Deterministic completion-order tie handling (stable ordering policy) |
| B4 | At-most-once under concurrent emitters | Concurrent emit attempts for same terminal job | Exactly one synthetic assistant message persisted |
| B5 | Payload size boundaries | Payload at `max_bytes`, `max_lines`, and +1 | At-limit stays inline; over-limit spills to temp file |
| B6 | Retention at limit | Exactly 20 terminal jobs in thread | No eviction |
| B7 | Retention overflow | 21+ terminal jobs | Oldest terminal evicted by completion time; newest kept |
| B8 | Mixed retention set | Terminal overflow with active non-terminal jobs present | Non-terminal jobs preserved; only terminal jobs evicted |

---

## Suggested Test Placement

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