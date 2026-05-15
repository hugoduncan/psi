2026-05-14 ambiguity review
- Ambiguity: task lacks `plan.md`, so the implementation approach/order/risk surface is not reviewable against the design.
- Ambiguity: task lacks `design-steps.md`, so there is no canonical place to record and track design follow-up items required by the review workflow.
- Ambiguity: design assigns failed terminal `provider_request_finished` ownership to `:on-agent-done`, but does not state the exact predicate/data path that proves the pending agent-end event is the unretried terminal provider failure rather than another terminal path; the emission guard is therefore underspecified.
- Ambiguity: design says `:attempt-id` can reuse prepared request / turn id because retries create a new prepared request / new turn id in the existing flow, but it does not cite or require proof of that invariant at the retry owner boundary, so the uniqueness contract for retry attempts is asserted but not anchored.
