# 190 — AI request retry/backoff observability and reliability

## Goal

Make AI provider request retry with backoff visibly and reliably work for transient request and connection failures, so users can tell when retry is happening and ordinary transient failures are retried before the turn fails.

## Why

Psi already has an AI request retry/backoff mechanism, but in live use it does not appear to be effective: users still see request and connection errors and do not see convincing evidence that retry/backoff was attempted.

This creates two problems:

1. **Reliability problem** — transient provider/network failures may still fail the whole turn without exhausting the intended retry policy.
2. **Observability problem** — even if retry is happening internally, users and developers cannot easily confirm which attempt failed, how it was classified, how long backoff waited, or why the final failure surfaced.

The intended outcome is not merely “add more retries”; it is to make retry behavior trustworthy, testable, and inspectable.

## Problem statement

When an AI provider request fails with request-level or connection-level errors, current behavior is ambiguous or unsatisfactory:

- the turn may fail with a request/connection error despite the configured retry/backoff policy;
- no visible retry/backoff evidence may appear in the transcript, logs, metrics, or debug surfaces;
- it may be unclear whether a failure was classified as retryable or terminal;
- it may be unclear whether retry attempts reused stale request/turn state incorrectly;
- the final error may not say whether retry was skipped, exhausted, or interrupted.

This task should investigate the existing retry/backoff path and repair the smallest coherent set of issues needed to make the behavior real and visible.

## Scope

In scope:

- Audit the current AI request retry/backoff implementation for streaming and non-streaming provider request paths that are active in normal turn execution.
- Identify which request and connection failures are intended to be retryable.
- Ensure retryable request/connection failures schedule and execute retry attempts according to the configured backoff policy.
- Ensure retry attempts create fresh provider request execution state where required, rather than reusing consumed streams or stale per-attempt data.
- Preserve provider telemetry events for each attempt, including retry scheduling and final completion/failure.
- Make retry/backoff visible enough that a user or developer can confirm it occurred from existing or lightly extended surfaces.
- Make retry/backoff introspectable via `psi-tool` / EQL so a caller can ask whether a session, turn, or provider request had retries, how many retries occurred, what errors were retried, and what final outcome followed.
- Preserve or intentionally replace the existing retry surfacing in TUI and Emacs/app-runtime projections, including visible retry-in/backoff status while a retry delay is active.
- Improve final error shaping so exhausted retries are distinguishable from non-retryable failures.
- Add focused tests proving retry occurs for representative transient request and connection failures.

Out of scope:

- Redesigning the entire provider abstraction.
- Adding a new global retry configuration UI unless implementation proves no existing configuration surface exists.
- Retrying non-idempotent tool execution or replaying tool side effects. Retrying an AI provider request that contains already-recorded tool result messages remains in scope.
- Masking permanent authentication, authorization, invalid request, schema, or model capability errors as retryable.
- Changing provider model selection or fallback policy except where it directly conflicts with request retry behavior.
- Broad metrics redesign beyond the minimum needed to expose retry attempts coherently.

## Desired behavior

### Retry classification

The runtime should classify AI request failures into at least these practical categories:

- **retryable transient failures**, such as connection resets, timeouts, temporary network failures, provider overload/rate-limit responses where retry is appropriate, and comparable transport failures;
- **terminal failures**, such as invalid API keys, unauthorized access, malformed request payloads, unsupported model/capability combinations, schema validation failures, or deterministic client-side construction errors;
- **unknown failures**, which should have an explicit default policy rather than accidentally falling through silently.

The exact classification should reuse existing provider-error classification where possible rather than introducing a parallel taxonomy.

Unknown provider/request failures default to **terminal non-retryable**. A failure may be retried only when observable data maps it to a retryable classification through the shared provider-error classifier, such as retryable HTTP status, timeout, rate-limit, overloaded, provider-unavailable, or transport evidence. If the classifier returns `:unknown` after inspecting the available stop reason, message, exception data, and HTTP status, the retry boundary must not schedule a retry. The final error and EQL/telemetry projections should expose `:error-kind :unknown`, `:retryable? false`, and a terminal/non-retryable failure reason (for example `:failure-reason :non-retryable`) so callers can distinguish "not retried because unknown" from retry exhaustion. This conservative default avoids masking permanent provider/client bugs behind repeated requests while still allowing newly observed transient subtypes to become retryable by extending the shared classifier rather than adding ad hoc retry rules.

### Backoff execution

For retryable failures:

- the first failed attempt records the failure;
- a retry is scheduled with the configured backoff delay;
- the next attempt actually executes after that delay;
- each attempt has a distinct attempt number;
- retry stops when either an attempt succeeds or the configured maximum attempts is exhausted.

For terminal failures:

- no retry is scheduled;
- the final error should explain that the failure was not retryable, or otherwise expose enough classification data to see why retry did not happen.

For exhausted retries:

- the final error should preserve the last failure cause;
- the final surface should indicate that retry attempts were exhausted;
- telemetry/logs/metrics should show all attempts and scheduled backoffs.

### Visibility and introspection

Retry behavior must be inspectable without source-code debugging. Logs/metrics/transcript output are useful, but `psi-tool` / EQL introspection is a required surface.

Existing user-facing retry surfacing must also be preserved unless the implementation records an explicit replacement. Current app-runtime/TUI/Emacs-facing projections already know how to display active retry state from session retry data, such as retry-in/backoff timing and retrying runtime state. Moving retry mechanics down to the provider request boundary must not silently remove that visible status.

A caller must be able to ask questions such as:

- Did this session have any provider request retries?
- Did this turn have any retries?
- Did this provider request have any retries?
- How many retry attempts happened?
- What retryable errors were retried?
- What was each error kind/classification?
- What backoff delay was scheduled for each retry?
- Did the request eventually succeed, fail as exhausted, or fail as terminal/non-retryable?

Minimum visibility requirements:

- each provider request attempt is identifiable;
- retry scheduling records the failure classification and backoff delay;
- attempt numbering follows the existing provider telemetry contract: `:retry-attempt` is zero-based (`0` for the first provider execution attempt, `1` for the first retry execution attempt);
- final failure indicates whether retry was skipped as terminal or exhausted after attempts;
- successful retry-after-failure leaves evidence that earlier attempts failed and a later attempt succeeded;
- active retry delay remains visible through existing app-runtime/TUI/Emacs-facing retry status projections or an explicitly documented replacement;
- retry telemetry is persisted or retained in session state strongly enough for EQL resolvers to answer session/turn/request retry questions after the request completes.

Recommended EQL/introspection shape:

- Session-level summary:
  - `:psi.agent-session/provider-retry-count`
  - `:psi.agent-session/provider-retried-request-count`
  - `:psi.agent-session/provider-retries` as a collection of retry records or grouped request summaries.
- Turn/request-level detail, reachable from existing provider request introspection where possible:
  - `:psi.provider-request/retry-count`
  - `:psi.provider-request/retry-attempts`
  - `:psi.provider-request/final-status`
  - `:psi.provider-retry/attempt`
  - `:psi.provider-retry/error-kind`
  - `:psi.provider-retry/error-message`
  - `:psi.provider-retry/http-status`
  - `:psi.provider-retry/delay-ms`
  - `:psi.provider-retry/delay-source`
  - `:psi.provider-retry/resume-at`
  - `:psi.provider-retry/final?`

Retry detail resolvers may expose a user-friendly `:psi.provider-retry/attempt` value, but if they do it must be explicitly documented as either a projection alias of the zero-based `:retry-attempt` or a display ordinal. Internal telemetry, metrics aggregation, existing session/UI projections, and resolver joins must preserve `:retry-attempt` as the canonical attempt coordinate.

Exact attribute names should follow the existing resolver/attribute conventions discovered during implementation, but the information above must be queryable through `psi-tool`.

### Streaming and non-streaming paths

The task should verify the active provider request paths used by normal turn execution.

If retry is intended to cover both streaming and non-streaming request execution, both paths should either be fixed and tested or the design/implementation notes should explicitly document a deliberate narrower scope.

If one path already has working retry and the other does not, the task should align behavior where practical rather than leaving a silent discrepancy.

## Architectural intent

Retry/backoff should live at the provider request execution boundary, not in higher workflow or UI code.

Preferred shape:

- request execution reports structured provider/request errors;
- a shared retry/backoff coordinator decides whether to retry, how long to wait, and when attempts are exhausted;
- provider telemetry records attempt lifecycle and retry scheduling;
- higher turn/workflow/session code receives a canonical success or final failure result without duplicating retry policy.

The implementation should prefer repairing and simplifying the current mechanism over adding an independent second retry layer.



## Retry history storage and EQL projection

Completed provider retry history has one authoritative source: session-owned provider lifecycle telemetry captures retained in the agent-session root state. The retry coordinator at the prepared provider-request boundary must emit the canonical provider telemetry events for every attempt and schedule decision, and those retained lifecycle captures are the durable records from which retry history is projected. Do not maintain a second completed-retry ledger in UI state, workflow state, or provider-local mutable state.

This is distinct from the existing HTTP provider request/reply capture streams. `:provider-requests` and `:provider-replies` continue to store outbound HTTP request and inbound reply/debug captures. Provider lifecycle events (`provider_request_started`, `provider_retry_scheduled`, `provider_request_finished`) should be retained as their own session telemetry stream, for example `:provider-events`, because they describe logical request-attempt lifecycle and retry decisions rather than raw HTTP payloads. The same lifecycle event payloads may still be dispatched through `/ext/provider-telemetry` for metrics/log consumers; EQL retry projection reads from the retained session lifecycle event stream, not from extension dispatch side effects and not from raw HTTP captures.

The authoritative record identity is hierarchical:

- `session-id` identifies the owning agent session;
- `turn-id` identifies the prepared provider request / turn execution;
- `provider-request-id` is the telemetry request identifier for the request lifecycle, using the existing provider telemetry request id when present and otherwise the prepared request id;
- `:retry-attempt` is the existing zero-based provider telemetry attempt index for each provider execution attempt for that request; first attempt is `0`, first retry execution is `1`.

Telemetry lifecycle events must include enough data for resolvers to reconstruct request retry summaries without parsing prose:

- `provider_request_started` for every attempt, including session id, turn/request id, provider/model when known, `:retry-attempt`, and a unique `:attempt-id`;
- `provider_retry_scheduled` for every retry delay, including session id, turn/request id, the next `:retry-attempt` to execute, the failed attempt number when distinct/available, error kind/classification, error message, optional HTTP status, delay ms, delay source, and resume-at timestamp when available;
- `provider_request_finished` for every attempt outcome, including session id, turn/request id, `:retry-attempt`, metrics-compatible `:status` (`:succeeded` or `:failed`), `:final?`, and the final error classification/cause when failing. Exhaustion is represented as a failed final attempt with explicit exhaustion metadata such as `:exhausted? true` or `:failure-reason :retry-exhausted`, not by replacing `:status :failed` with a new status.

EQL / `psi-tool` retry answers are projections over retained provider lifecycle telemetry captures. Existing provider telemetry resolvers should be extended where possible rather than introducing a parallel read model. Minimum queryable projections are:

- session-level retry summary: provider retry count, retried provider-request count, and grouped provider retry request summaries for a session;
- turn/request-level detail: retry count, ordered retry attempts/scheduled delays, final retry status, and final error classification;
- retry-attempt records: attempt number, error kind, error message, HTTP status when known, delay ms, delay source, resume-at, and whether the attempt is final.

`provider-request-id` / `turn-id` plus zero-based `:retry-attempt` is the canonical logical coordinate for grouping and ordering retries in EQL and metrics. `:attempt-id` remains part of the provider telemetry event contract as an event identity for one concrete provider execution attempt and therefore must be unique per execution attempt when retries occur inside one prepared request. The implementation should derive it deterministically from the stable request id and attempt coordinate, for example `"<provider-request-id>#attempt-<retry-attempt>"`, while preserving the existing first-attempt value only if doing so does not create duplicate attempt ids across retries. Consumers that group attempts must use the canonical request id + `:retry-attempt`; consumers that need event idempotency or trace identity may use `:attempt-id`. Existing code paths that currently set `:attempt-id` to the prepared request/turn id for every provider event must be updated for retried attempts so no two provider execution attempts for the same prepared request share the same `:attempt-id`.

If implementation discovers that the existing telemetry retention is insufficient for completed EQL answers after a request finishes, the smallest acceptable storage change is to retain the same canonical telemetry capture data under session state and still project EQL from that retained telemetry. UI/app-runtime active retry state is not authoritative for completed retry history.

## Active retry/backoff visibility

Provider-boundary retry must keep the existing app-runtime/TUI/Emacs retry projection accurate while a retry delay is pending. The retry coordinator owns execution and timing, but it must publish active retry state into the existing session retry fields before sleeping and clear them when the delay ends or the request reaches a terminal outcome.

During a pending retry delay:

- session phase/status visible to app-runtime projections must indicate retry/backoff activity, preserving the existing `:retrying` semantics where those projections depend on it;
- session retry data must include the current failed attempt, the next attempt number, error kind/classification, error message, delay ms, delay source, and resume-at timestamp;
- `:retry-attempt` must preserve existing zero-based semantics and reflect the next provider execution attempt to be executed, not a replay of the whole agent loop;
- active retry state must be observable before the sleep begins, so UI projections can render retry-in/backoff status while waiting.

When the retry delay completes and the next provider attempt starts, pending delay fields should be cleared or marked inactive while preserving completed retry history in provider telemetry. When the request finally succeeds, fails terminally, or exhausts retries, active retry fields must be cleared so TUI/Emacs/app-runtime do not continue to show a stale backoff.

This preserves the old statechart retry visibility contract but replaces the old statechart retry engine. The existing app-runtime/TUI/Emacs status path derives runtime state from `ss/sc-phase-in`, so provider-boundary retry must make that projection report `:retrying` during the pending delay. The required design is to split the existing statechart `:retrying` state into a visibility/protocol state and a retry engine: entering or remaining in `:retrying` is allowed only to expose active provider-boundary retry metadata, while the old `:on-retry-resume` whole-agent-loop effect (`:runtime/agent-start-loop`) must not be used for provider-boundary retries. Provider-boundary retry should either drive a dedicated statechart event that transitions to `:retrying` with metadata and then back to the prior provider-execution state/idle without `agent-start-loop`, or make `sc-phase-in` explicitly project `:retrying` from active provider retry fields before falling back to the statechart configuration. In both shapes, app-runtime/TUI/Emacs projections must derive their active retry status from the same session retry fields that provider-boundary retry sets before sleeping and clears after sleep/terminal outcome. The old statechart retry transition from terminal `:session/agent-event` may remain only for compatibility until removed, but it must not be the path used by canonical prompt lifecycle provider retries and must not rerun local tools or the whole agent loop for this task. Focused coverage must prove that while the provider-boundary delay is pending, the existing app-runtime/TUI/Emacs-facing phase/status surface reports retrying, and that retry resume continues the same prepared provider request rather than dispatching `:runtime/agent-start-loop`. If implementation chooses a different active surface, it must document that replacement and add focused coverage proving app-runtime/TUI/Emacs-facing retry status remains visible while a provider retry delay is pending.

## Retry limit semantics

The configured retry limit is retry-count oriented, not total-attempt oriented. `:auto-retry-max-retries` names the maximum number of retry executions allowed after the first provider execution attempt fails. It does not include the initial provider execution attempt.

Canonical mapping:

- first provider execution attempt uses `:retry-attempt 0`;
- first retry execution attempt uses `:retry-attempt 1`;
- the retry execution attempt with `:retry-attempt N` is allowed only when `N <= :auto-retry-max-retries`;
- no retry is scheduled after a failure from attempt `N` when `N >= :auto-retry-max-retries`; that failure is final and should be recorded as `:status :failed`, `:final? true`, and `:exhausted? true` / `:failure-reason :retry-exhausted` when the failure was otherwise retryable.

Examples with the default `:auto-retry-max-retries 3`:

- attempt `0` fails retryably → schedule retry attempt `1`;
- attempt `1` fails retryably → schedule retry attempt `2`;
- attempt `2` fails retryably → schedule retry attempt `3`;
- attempt `3` fails retryably → do not schedule attempt `4`; surface exhausted retries with the last failure cause.

Thus the maximum number of provider execution attempts for a retryable request is `1 + :auto-retry-max-retries` when retries are enabled. Setting `:auto-retry-max-retries` to `0` means execute the provider request once and never schedule a retry. Existing policy fields and UI/session projections that expose `:retry-attempt` must preserve the zero-based attempt coordinate; any display text may describe retry execution number separately only as a projection.

## Acceptance criteria

- A focused audit identifies the current retry/backoff path and records whether the bug was missing classification, missing scheduling, missing execution, stale attempt state, invisible telemetry, or another concrete cause.
- A representative retryable connection/request failure is retried before the turn fails.
- A representative retryable failure followed by a successful provider response returns success to the caller.
- A representative retryable failure repeated through the maximum attempt count returns a final exhausted-retries error that preserves the last cause.
- A representative terminal provider/client error is not retried.
- Retry scheduling uses backoff delays from the configured policy rather than immediate unbounded retry.
- Provider telemetry/logging/metrics show per-attempt request start/finish and retry scheduling for retried failures.
- `psi-tool` / EQL can answer whether a session had provider request retries, how many retry attempts occurred, which turn/request they belonged to, which errors were retried, and whether the final result succeeded or exhausted retries.
- Existing TUI and Emacs/app-runtime retry surfacing remains accurate while a retry backoff is active, or the task documents and tests an intentional replacement surface.
- The final user/developer-visible error distinguishes at least terminal non-retryable failure from exhausted retry attempts.
- Focused tests cover retry success-after-failure, retry exhaustion, and no-retry terminal failure.
- Tests avoid real network dependency by using controlled provider/request seams rather than external provider availability.
- Existing behavior for successful first-attempt requests is preserved.

## Likely investigation areas

Likely files or concepts to inspect during planning/implementation:

- AI provider request execution boundary for streaming requests.
- AI provider request execution boundary for non-streaming requests, if active.
- Existing retry/backoff coordinator or helper.
- Existing provider error classification helper.
- Provider telemetry events such as request started, retry scheduled, and request finished.
- Turn runtime request execution and final error shaping.
- Existing provider request EQL resolvers and telemetry state under session telemetry.
- Metrics or EQL surfaces that expose provider attempt counts and retry data.

These are investigation hints, not fixed implementation instructions. Runtime behavior and existing ownership should decide the final edit set.

## Constraints

- Do not hide real permanent configuration or request-construction bugs behind repeated retries.
- Do not retry local tool execution or duplicate tool side effects.
- Do retry the provider request that posts already-recorded tool results back to the model when that provider request fails with a retryable request/connection error.
- Do not introduce sleeps that make the test suite slow; tests should use injectable or controlled backoff timing where possible.
- Keep retry policy deterministic and bounded.
- Prefer one authoritative retry policy over provider-specific ad hoc loops.
- Preserve existing provider telemetry event names and shapes where possible; extend only when necessary.

## Investigation — current retry placement

Initial source audit indicates the current retry/backoff mechanism is **above** the provider request execution boundary, in the session statechart terminal-turn path, not around the request execution itself.

Current observed request flow for the prompt-lifecycle path:

```text
submit-prompt-turn-in!
→ :session/prompt-submit
→ :session/prompt
→ :session/prompt-prepare-request
→ effect :runtime/prompt-execute-and-record
→ turn-runtime/execute-prepared-request!
→ execute-live-turn! or execute-non-streaming-turn!
→ :session/prompt-record-response
→ :session/prompt-finish
→ effect :statechart/send-event :session/reset
```

Current retry-related code locations:

- Retry classification and backoff helpers live in `psi.session-state.model`:
  - `retry-error?`
  - `provider-error-kind`
  - `exponential-backoff-ms`
  - `retry-metadata`
- Retry guard lives in `psi.agent-session.statechart/should-retry?` and only runs on `:session/agent-event` while the session chart is in `:streaming`.
- Retry scheduling lives in `psi.agent-session.dispatch-handlers.statechart-actions/:on-retry-triggered`, which emits `provider_request_finished` / `provider_retry_scheduled`, increments `:retry-attempt`, stores `:retry`, sleeps, then sends `:session/retry-done`.
- Retry resume lives in `:on-retry-resume`, which starts the agent loop again via `:runtime/agent-start-loop`.
- Provider request start/success events are emitted in `psi.turn-runtime.core/execute-prepared-request!`.
- Provider request failures are emitted later from statechart action handlers, after an `:agent-end`-shaped event.

This placement looks suspicious for the current prompt-lifecycle path:

1. `:session/prompt-finish` synthesizes an `:agent-end` payload but dispatches it to the **dispatch event** `:on-agent-done`, not to the session statechart as `:session/agent-event`.
2. The same finish result then emits `:statechart/send-event :session/reset` without passing pending-agent-event data.
3. `execute-effect! :statechart/send-event` currently calls `sc/send-event!` without forwarding extra data, so the statechart retry guard cannot inspect the terminal assistant message on that path.
4. Therefore the statechart-level `should-retry?` / `:on-retry-triggered` path may be bypassed entirely for the canonical prompt lifecycle, which matches the user symptom: request/connection errors surface, but retry/backoff is not visibly entered.

There is an older/shared helper `psi.agent-session.prompt-loop/finish-agent-loop!` that does send `:session/agent-event` with `:pending-agent-event` into the statechart. That path appears better aligned with the current retry mechanism, but it does not appear to be the canonical prompt-lifecycle path used by `submit-prompt-turn-in!`.

Preliminary conclusion:

- The existing retry mechanism is likely **not in the correct place** for current turn execution.
- It is attached to session terminal lifecycle events after the provider request has already been converted into an assistant error message.
- More importantly, the canonical prompt lifecycle appears to bypass the statechart guard that schedules retries.
- Even if that bypass is fixed, retry remains split across turn-runtime telemetry, session statechart guards, and agent-loop restart behavior. That makes it harder to prove retries re-execute the correct prepared request with fresh per-attempt provider state.

## Placement decision

Retry should be owned at the **prepared provider request execution boundary**, inside or immediately around `psi.turn-runtime.core/execute-prepared-request!`.

The retry unit is one prepared provider request, not the whole prompt lifecycle and not the whole agent loop.

Retries apply to:

- the initial model request for a user prompt;
- continuation model requests after tool results have already been recorded;
- any other provider request represented by a prepared request and failing with a retryable transient request/connection error.

Retries do not apply to:

- local tool execution;
- replaying tool side effects;
- replaying the whole prompt lifecycle from before tool execution;
- replaying the whole agent loop.

Tool-result post rule:

```text
assistant asks for tool
→ ψ runs tool once
→ ψ records tool result once
→ ψ sends provider request containing that tool result
→ transient network/request error
→ retry the same prepared provider request containing the already-recorded tool result
→ do not rerun the tool
```

This placement is safer than the current statechart-level retry because it retries only the failed provider call. The session statechart may still expose retry visibility/state for UI and diagnostics, but it should not be the engine that decides how to replay request execution.

Recommended direction to validate before implementation:

- Add or run a focused failing proof that a retryable `execute-prepared-request!` error through `submit-prompt-turn-in!` does **not** enter `:retrying` or emit `provider_retry_scheduled` today.
- Implement bounded retry around `turn-runtime/execute-prepared-request!` / provider execution, not by replaying the statechart or whole agent loop.
- Keep session retry state as visibility/projection data while provider request execution remains the authority for whether and how a request is retried.
- Add a focused tool-result-post proof: when a provider request containing an already-recorded tool result fails transiently, the provider request is retried but local tool execution is not rerun.

Focused proof added/run:

- Test: `psi.agent-session.prompt-lifecycle-test/prompt-execution-result-retryable-error-enters-retrying-and-schedules-retry-test`
- Command: `clojure -M:test --focus psi.agent-session.prompt-lifecycle-test/prompt-execution-result-retryable-error-enters-retrying-and-schedules-retry-test`
- Result: failing as expected.
- Observed failure:
  - expected statechart phase `:retrying`, actual `:idle`
  - expected telemetry `["provider_retry_scheduled"]`, actual `[]`
  - provider execution stub was called once, proving the canonical prompt lifecycle reached a retryable terminal provider/request error but did not schedule retry/backoff.

## Notes

The motivating report is from live user experience: “we have an AI request retry with backoff, but I never see it actually working, and still get request and connection errors.” The implementation should treat that as both a reliability bug and an observability bug.
