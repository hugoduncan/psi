Approach:
- implement this as two tightly scoped vertical slices that preserve the existing architecture:
  1. session retry classification and retry-path activation
  2. workflow child-turn error propagation into canonical workflow execution failure
- keep retryability centralized in one canonical helper and keep workflow execution responsible only for truthful result shaping
- prove each slice with focused tests before relying on combined workflow behavior

Implementation plan:

1. Establish the canonical retry-classification surface
   - inspect the current session retry path centered on:
     - `components/agent-session/src/psi/agent_session/session.clj`
     - `components/agent-session/src/psi/agent_session/statechart.clj`
     - `components/agent-session/src/psi/agent_session/dispatch_handlers/statechart_actions.clj`
   - decide whether to:
     - keep `session/retry-error?` as the canonical helper, or
     - extract a small helper and make `session/retry-error?` delegate to it
   - preserve exactly one authoritative retry-classification entry point for the session statechart

2. Define the minimum retryable transport-failure allowlist for this task
   - encode the minimum required case: incomplete or prematurely terminated chunked/streamed HTTP response body delivery
   - include the observed error text as a covered case:
     - `Premature end of chunk coded message body: closing chunk expected`
   - keep additional transport patterns out unless they are clearly transport-boundary interruptions
   - explicitly avoid broadening classification to:
     - auth failures
     - invalid request errors
     - content-policy refusals
     - semantic/model errors
     - context-window/token overflow errors

3. Implement retry classification changes
   - update the canonical retry-classification helper to recognize the minimum transport-failure allowlist in addition to the existing HTTP status and overload/rate-limit handling
   - keep the implementation data-driven or locally explicit so the retryable transport patterns are easy to inspect and test
   - avoid duplicating transport-failure pattern logic at statechart call sites

4. Add focused retry-classifier tests
   - extend or add tests near the existing retry-classification coverage in:
     - `components/agent-session/test/psi/agent_session/session_test.clj`
   - prove:
     - observed chunked-stream failure text is retryable
     - existing retryable HTTP/rate-limit cases remain retryable
     - clearly non-retryable semantic/request/auth cases remain non-retryable
     - context overflow remains excluded

5. Prove session retry-path activation observably
   - add/update session/statechart tests near:
     - `components/agent-session/test/psi/agent_session/statechart_actions_test.clj`
     - `components/agent-session/test/psi/agent_session/session_lifecycle_test.clj`
     - or the smallest existing suite that already proves retry transitions
   - verify observable retry behavior for the newly classified transport failure by proving the retry transition/effect path, such as:
     - retry guard admits the failure
     - `:on-retry-triggered` path runs
     - `:retry-attempt` increments
     - retry scheduling/backoff effect is emitted
   - avoid relying only on indirect inference from final state

6. Shape workflow execution error propagation truthfully
   - inspect the current workflow execution path in:
     - `components/agent-session/src/psi/agent_session/workflow_execution.clj`
     - related progression helpers in `workflow_progression.clj` / workflow tests as needed
   - introduce a small shaping helper if useful, but keep success/failure determination local to workflow execution rather than scattered
   - make canonical child turn outcome and stop reason authoritative over text extraction
   - preserve successful-path behavior:
     - successful assistant turn -> `{:outcome :ok :outputs {:text ...}}`
   - change error-path behavior:
     - error turn -> record execution failure via workflow progression
     - failure payload must contain required field `:message`
     - include `:stop-reason`, `:turn-outcome`, and `:session-id` only when already readily available
   - ensure partial text on an error turn does not produce a success envelope

7. Add focused workflow execution tests
   - extend tests near:
     - `components/agent-session/test/psi/agent_session/workflow_execution_test.clj`
     - and related workflow integration tests only if needed
   - prove:
     - assistant error turn is not flattened into `{:outcome :ok :outputs {:text ""}}`
     - workflow execution records execution failure with required `:message`
     - workflow progression receives the failure and applies retry/fail semantics according to existing retry policy
     - existing successful workflow execution cases remain unchanged

8. Run targeted verification first, then broader regression checks
   - run the smallest focused test namespaces covering:
     - retry classification
     - session retry activation
     - workflow execution failure propagation
   - if green, run the broader workflow/session-related suites needed to ensure no regression in successful execution paths
   - record concrete test commands and outcomes in `implementation.md`

Execution notes:
- prefer minimal semantic change over helper churn
- prefer extending existing test namespaces over creating new ones unless a new focused test file materially improves clarity
- do not add provider failover behavior in this task
- do not redesign workflow result schemas beyond the minimal truthful failure propagation required here

Risks / decisions:
- the main retry risk is overmatching generic error text and turning semantic/request failures into retries; mitigate by keeping the minimum allowlist narrow and explicit
- the main workflow risk is deciding success/failure from content blocks alone; mitigate by making child turn outcome / stop reason authoritative
- if workflow execution currently lacks one clean place to shape success vs failure, introduce one small helper rather than duplicating checks across submit/record paths
