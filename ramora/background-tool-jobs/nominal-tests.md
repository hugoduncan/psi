# Nominal Tests

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
