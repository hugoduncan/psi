# 154 — provider metrics telemetry

## Goal

Extend the metrics extension and the core extension-event surface so ψ can persist accurate provider/LLM operational statistics: provider request counts, retries, total retry backoff time, failures, successes, and error counts by canonical type.

## Why

The current `psi/metrics` extension gives useful project-scoped visibility into tools and token usage, but it cannot answer operational questions about model/provider reliability:

- how many provider requests were made?
- how many succeeded vs failed?
- how often did retryable failures occur?
- how many retries were scheduled?
- how much total time was spent waiting in retry backoff?
- which canonical error kinds dominate for a given provider/model?
- how many request sequences still failed after retries were exhausted?

Some of the necessary information already exists in core logic:

- the session retry path classifies retryable failures
- the retry scheduler computes a concrete delay
- provider/model identity is known at turn execution time
- terminal success/failure is known at the turn/runtime boundary

But none of that is currently emitted on the extension event bus in a canonical, structured form. As a result, the metrics extension cannot observe provider behavior accurately without brittle string parsing or incomplete inference from `session_turn_finished`.

## Problem

Today `psi/metrics` subscribes only to:

- `tool_call`
- `tool_result`
- `session_turn_finished`

That is enough for tool counts and per-model token aggregation, but not for provider telemetry.

Specifically:

- successful turns may emit `session_turn_finished`, but failed provider attempts may not
- the extension cannot distinguish first attempt from retry attempt
- the extension cannot observe the selected retry delay or total backoff time
- provider/model identity is not surfaced on a dedicated provider-lifecycle event
- failure classification is not emitted as a structured canonical field
- the extension cannot determine whether a failure was recovered by retry or remained a terminal failure after retries were exhausted

Without new core events, any provider metrics would be partial or heuristic.

## Intent

Create one small canonical provider-telemetry event surface in core, then extend `psi/metrics` to consume it and persist provider statistics.

The task should:

1. define the canonical unit of counting as a **provider execution attempt**
2. emit structured extension events for provider attempt start, provider retry scheduling, and provider attempt finish
3. include canonical provider/model identity and canonical failure classification in those events
4. extend the metrics schema with provider aggregates at provider and per-model levels
5. update `/metrics` and `metrics/summary` to surface provider statistics clearly
6. keep the event surface minimal and shared so other extensions can consume it later

## Desired outcome

After this task:

- every provider execution attempt is counted, including attempts that later retry
- retries are counted separately from request attempts
- selected retry delay contributes to cumulative backoff totals
- final failures after retries are counted explicitly
- canonical error-kind counts are accumulated by provider and by model
- the metrics extension can answer operational questions about provider reliability without parsing free-text errors
- the new event surface remains small, transport-safe, and useful beyond metrics

## In scope

- defining canonical provider telemetry event names and payloads on the extension event bus
- choosing the canonical provider-attempt counting unit and documenting it in code/tests
- surfacing provider/model/session/turn identity needed for aggregation
- surfacing canonical success/failure status for provider attempts
- surfacing canonical error classification and retryability when present
- surfacing retry scheduling events with the chosen delay in milliseconds
- extending `psi.metrics.schema` and `psi.metrics.counters`
- extending `psi.metrics.extension` to subscribe to the new provider telemetry events
- rendering provider metrics in `/metrics`
- focused tests for event emission, aggregation, persistence round-trip, and summary rendering

## Out of scope

- provider-specific cost accounting or pricing integration
- time-series metrics or bucketed historical telemetry
- cross-project/global aggregation
- redesign of the session retry policy itself
- transport-local retry loops outside the canonical session retry path
- changing token accounting behavior already owned by `session_turn_finished`

## Canonical counting model

### Provider request

For this task, one **provider request** means one **provider execution attempt**.

That means:

- the initial execution attempt counts as one request
- each retry counts as an additional request
- request count therefore reflects actual outbound attempt volume, not just user turns

This definition is intentional because it makes provider load and failure/retry behavior visible.

### Retry

A **retry** means one scheduled re-attempt after a prior provider attempt finished with a retryable failure.

Retry count is therefore distinct from request count:

- request count = all attempts
- retry count = attempts after the first, as scheduled by the retry path

### Final failure

A **final failure** means a provider attempt sequence that ended in failure without a later successful retry.

This must be counted once per exhausted/terminal attempt sequence, not once per failed intermediate retryable attempt.

### Error-kind count

An **error-kind count** increments per failed provider attempt using a canonical backend-owned classification such as:

- `:rate-limit`
- `:timeout`
- `:transport`
- `:overloaded`
- `:auth`
- `:invalid-request`
- `:provider-unavailable`
- `:unknown`

The exact set should stay small, explicit, and backend-owned. The metrics extension must aggregate the emitted canonical keyword/string, not derive its own taxonomy.

## Canonical event surface

The extension event bus should gain three events.

### 1. `provider_request_started`

Emitted when a provider execution attempt begins.

Payload:

```clojure
{:type "provider_request_started"
 :session-id "s1"
 :turn-id "t1"
 :attempt-id "provider-attempt-1"
 :provider "openai"
 :model-id "gpt-5.4"
 :retry-attempt 0}
```

Required fields:

- `:session-id`
- `:turn-id`
- `:attempt-id` — canonical unique id for this provider execution attempt within the runtime owner that emits it
- `:provider` — normalized provider id string
- `:model-id` — normalized model id string when known, else `"unknown"`
- `:retry-attempt` — `0` for the first attempt, incremented for retries

Behavior:

- emitted once per actual provider execution attempt
- emitted for both first attempts and retries

### 2. `provider_retry_scheduled`

Emitted when the canonical session retry path schedules a provider retry delay.

Payload:

```clojure
{:type "provider_retry_scheduled"
 :session-id "s1"
 :turn-id "t1"
 :provider "openai"
 :model-id "gpt-5.4"
 :retry-attempt 1
 :delay-ms 2000
 :delay-source :exponential-backoff
 :error-kind :transport}
```

Required fields:

- `:session-id`
- `:turn-id` when available from the retry owner; if not already available at that boundary, this task must either propagate it or explicitly document a narrower canonical owner without `:turn-id`
- `:provider`
- `:model-id`
- `:retry-attempt` — the retry attempt being scheduled
- `:delay-ms`
- `:delay-source` — existing retry metadata source such as `:retry-after` or `:exponential-backoff`

Optional fields:

- `:error-kind`
- `:retryable?` (expected true when emitted, but allowed for clarity)

Behavior:

- emitted once per scheduled retry
- used by metrics to increment retries and sum total backoff time

### 3. `provider_request_finished`

Emitted exactly once when one provider execution attempt finishes.

Ownership rule for this task:

- successful attempts emit from `turn-runtime.core/execute-prepared-request!`
- failed attempts emit from the session statechart owner that knows retry-vs-terminal semantics

Payload:

```clojure
{:type "provider_request_finished"
 :session-id "s1"
 :turn-id "t1"
 :attempt-id "provider-attempt-1"
 :provider "openai"
 :model-id "gpt-5.4"
 :retry-attempt 0
 :status :failed
 :retryable? true
 :final? false
 :error-kind :transport}
```

or success:

```clojure
{:type "provider_request_finished"
 :session-id "s1"
 :turn-id "t1"
 :attempt-id "provider-attempt-2"
 :provider "openai"
 :model-id "gpt-5.4"
 :retry-attempt 1
 :status :succeeded
 :final? true}
```

Required fields:

- `:session-id`
- `:turn-id`
- `:attempt-id`
- `:provider`
- `:model-id`
- `:retry-attempt`
- `:status` — `:succeeded` or `:failed`
- `:final?` — true when this attempt ended the sequence with no further retry pending; false for failed attempts that will retry

Optional fields when failed:

- `:retryable?`
- `:error-kind`
- `:stop-reason`
- `:error-message` — transport-safe short text only if already available and useful diagnostically; metrics itself does not aggregate on this field

Behavior:

- emitted once per provider execution attempt
- failed intermediate retryable attempts must use `:final? false`
- exhausted/non-retryable terminal failures must use `:final? true`
- successful completion should use `:status :succeeded` and `:final? true`

## Canonical aggregation shape

Extend metrics with a top-level `:providers` map keyed by provider id.

Proposed persisted shape:

```clojure
{:providers
 {"openai"
  {:requests 120
   :successes 110
   :failures 10
   :final-failures 3
   :retries 14
   :retry-backoff-ms 32000
   :error-types {"rate-limit" 4
                 "timeout" 3
                 "transport" 2
                 "auth" 1}
   :models
   {"gpt-5.4"
    {:requests 100
     :successes 93
     :failures 7
     :final-failures 2
     :retries 11
     :retry-backoff-ms 26000
     :error-types {"rate-limit" 4
                   "timeout" 2
                   "transport" 1}}}}}
 :updated-at "2026-05-14T10:00:00Z"}
```

Aggregation rules:

- `:requests` increments on `provider_request_started`
- `:retries` increments on `provider_retry_scheduled`
- `:retry-backoff-ms` adds `:delay-ms` from `provider_retry_scheduled`
- `:successes` increments on `provider_request_finished` with `:status :succeeded`
- `:failures` increments on `provider_request_finished` with `:status :failed`
- `:final-failures` increments on `provider_request_finished` with `:status :failed` and `:final? true`
- `:error-types` increments on failed `provider_request_finished` when `:error-kind` is present; use the emitted canonical keyword name/string as the persisted key

The same increments apply both at provider level and nested per-model level.

## Schema additions

Extend `psi.metrics.schema/metrics-schema` with:

```clojure
(def provider-counter-schema
  [:map
   [:requests :int]
   [:successes :int]
   [:failures :int]
   [:final-failures :int]
   [:retries :int]
   [:retry-backoff-ms :int]
   [:error-types [:map-of :string :int]]])

(def provider-metrics-schema
  [:map
   [:requests :int]
   [:successes :int]
   [:failures :int]
   [:final-failures :int]
   [:retries :int]
   [:retry-backoff-ms :int]
   [:error-types [:map-of :string :int]]
   [:models [:map-of :string provider-counter-schema]]])

(def metrics-schema
  [:map
   [:tools [:map-of :string tool-counter-schema]]
   [:workflows [:map-of :string counter-schema]]
   [:commands [:map-of :string counter-schema]]
   [:operations [:map-of :string counter-schema]]
   [:tokens [:map-of :string token-totals-schema]]
   [:providers [:map-of :string provider-metrics-schema]]
   [:updated-at [:maybe :string]]])
```

If implementation finds repetition too high, helper schemas/factored shapes are acceptable, but the persisted semantics above are the acceptance surface.

## Summary rendering requirements

`/metrics` should gain a provider section, for example:

```markdown
### Providers (2 tracked)
| Provider | Requests | Successes | Failures | Final Failures | Retries | Backoff |
|----------|----------|-----------|----------|----------------|---------|---------|
| openai   | 120      | 110       | 10       | 3              | 14      | 32,000ms |
```

and optionally a nested per-model section such as:

```markdown
### Provider Models
| Provider | Model | Requests | Successes | Failures | Final Failures | Retries | Backoff |
|----------|-------|----------|-----------|----------|----------------|---------|---------|
| openai   | gpt-5.4 | 100    | 93        | 7        | 2              | 11      | 26,000ms |
```

Error-type detail does not need full nested table rendering in the first slice, but it must be present in `metrics/summary` data and preserved in the persisted map.

## Preferred implementation direction

### Concrete owner map discovered from the current code

The current codebase already gives a narrow, coherent ownership split for this telemetry.

#### 1. Provider attempt start owner

**Owner:** `components/turn-runtime/src/psi/turn_runtime/core.clj`

**Specific function:** `execute-prepared-request!`

Why this owner is canonical:

- it owns one prepared provider execution attempt end-to-end
- it already has `session-id`, `turn-id`, resolved `ai-model`, and response-mode selection in hand
- both streaming and non-streaming paths converge here before/after execution
- request counting should happen once per actual provider execution attempt, which is exactly the semantic owned here

Implementation direction:

- emit `provider_request_started` from `execute-prepared-request!` immediately after `turn-id`/`ai-model`/`session-id` are resolved and before dispatching into either `execute-live-turn!` or `execute-non-streaming-turn!`
- use `(:extension-registry ctx)` plus the existing extension-dispatch path used elsewhere in agent-session/runtime-owned code

#### 2. Provider retry scheduled owner

**Owner:** `components/agent-session/src/psi/agent_session/dispatch_handlers/statechart_actions.clj`

**Specific function/handler:** `:on-retry-triggered`

Why this owner is canonical:

- it already owns the retry transition semantics for sessions
- it already computes `retry-metadata` via `retry-metadata-for`
- it already persists retry metadata onto session state and schedules the actual delay effect
- this is the authoritative point where the selected delay is known, including provider-header override vs exponential fallback

Implementation direction:

- emit `provider_retry_scheduled` from the `:on-retry-triggered` handler after `retry-metadata` is computed and before returning the scheduling effect
- the emitted `:delay-ms` must come directly from `retry-metadata`
- the emitted `:delay-source` must come directly from `retry-metadata`
- provider/model identity should be read from current session data (`sd`) because this handler already loads canonical session state
- `retry-attempt` in the emitted event should describe the retry being scheduled, i.e. `(inc (:retry-attempt sd))`, because the root-state update increments after the event is shaped

#### 3. Provider attempt finish owner

**Owner:** `components/turn-runtime/src/psi/turn_runtime/core.clj`

**Specific function:** `execute-prepared-request!`

Why this owner is canonical:

- it produces the canonical `execution-result` shape
- it already normalizes terminal provider outcome details such as assistant message, stop reason, error message, http status, and provider headers
- it is the single convergence point for both streaming and non-streaming execution
- it has enough information to emit one finish event per actual provider execution attempt

Implementation direction:

- emit `provider_request_finished` from `execute-prepared-request!` only for **successful** attempts, after `assistant-message` has been classified into the returned execution-result fields and before returning the execution-result map
- successful finish payload uses `:status :succeeded` and `:final? true`
- do **not** emit failed finish events from turn-runtime, because turn-runtime cannot know whether the failed attempt is terminal or will retry

### Final chosen finish-event strategy

The current code makes one strategy clearly preferable:

- turn-runtime knows **attempt identity** and **raw success/failure outcome**
- the session statechart path knows **whether a failed attempt will retry** or **terminate**

So this task chooses one-way ownership for `provider_request_finished`:

- **successful attempt finishes** are emitted from `components/turn-runtime/src/psi/turn_runtime/core.clj` `execute-prepared-request!`
- **failed attempt finishes** are emitted from the agent-session statechart boundary that already distinguishes retry vs terminal failure

Concrete failure-finish owners:

- retrying failed attempt → `components/agent-session/src/psi/agent_session/dispatch_handlers/statechart_actions.clj` `:on-retry-triggered`
  - emits `provider_request_finished` with `:status :failed` and `:final? false`
- terminal failed attempt → `components/agent-session/src/psi/agent_session/dispatch_handlers/statechart_actions.clj` `:on-agent-done`
  - when the pending agent-end event is an error and retry will not occur, emits `provider_request_finished` with `:status :failed` and `:final? true`

Why this is the chosen strategy:

- it keeps `provider_request_finished` authoritative and single-shot for each attempt
- it avoids provisional failed-finish events that later need de-duplication or terminalization
- it aligns event ownership with the code that actually knows the semantics being emitted
- it preserves simple metrics aggregation rules: one start, zero-or-one retry-scheduled, one finish per attempt

Current decision points that justify this choice:

- provider/turn failure is shaped in `turn-runtime.core/execute-prepared-request!`
- child/shared-session loop completion is fed into the session statechart through `agent-session.prompt-loop/finish-agent-loop!`
- retryability is decided by `statechart/should-retry?` using stop reason, error text, session retry config, and current retry attempt count
- the statechart transitions already fork failed attempts into retrying vs terminal paths before `:on-retry-triggered` and `:on-agent-done`

Implementation rule for this task:

- there must be exactly one `provider_request_finished` event per provider execution attempt
- success attempts finish in turn-runtime
- failed attempts finish in the statechart owner, never earlier in turn-runtime

### Terminal failed-finish guard at `:on-agent-done`

The terminal failed `provider_request_finished` emission must not fire for every `:on-agent-done` path.

Exact guard for this task:

- read the statechart-carried pending event from the `data` argument already supplied to the `:on-agent-done` handler boundary
- emit a terminal failed provider finish only when all of the following hold:
  1. `(:type pending-agent-event)` is `:agent-end`
  2. the last assistant message in `(:messages pending-agent-event)` exists and has `:stop-reason :error` (or string `"error"`)
  3. the pending event represents the provider execution result path, evidenced by provider-turn failure fields already shaped at the loop boundary (`:provider-error/headers`, assistant `:error-message`, optional `:http-status`) rather than an unrelated terminal/session-local completion path
  4. retry will not occur for that same pending event, i.e. this handler is reached only after the statechart retry branch declined to take `:on-retry-triggered`

Non-provider terminal paths excluded by this guard:

- normal successful agent completion
- tool-use completion paths
- abort-only cleanup paths
- terminal paths whose pending event does not carry the provider-shaped assistant error result

Data source rule:

- the emission guard is evaluated from the statechart-owned `data` / `:pending-agent-event` payload, not reconstructed later from reset session state
- if implementation finds that current dispatch into `:on-agent-done` does not yet preserve this data at the handler boundary, the handler input must be narrowed or extended so the terminal provider failure proof remains explicit at the point of emission

Payload derivation rule for this owner:

- `:attempt-id`, `:provider`, `:model-id`, and failed outcome details should be derived from the same pending terminal provider result path that reached `:on-agent-done`, using session state only for stable session/model fallback values where needed

### Concrete payload derivation rules from existing owners

#### Provider/model identity

Use the resolved model already present in `execute-prepared-request!`:

- provider → `(or (some-> ai-model :provider name) (some-> ai-model :provider str) "unknown")`
- model-id → `(or (:id ai-model) "unknown")`

For `:on-retry-triggered`, use the canonical session model in `sd` with the same normalization fallback.

#### Retry attempt

Use session-owned retry count semantics already present in session state:

- started/finished attempt events should use the current `(:retry-attempt sd)` when the attempt begins
- retry-scheduled should use `(inc (:retry-attempt sd))` because it describes the next attempt being scheduled, not the just-failed attempt

If turn-runtime does not already have direct access to `sd`, it may read it from `ss/get-session-data-in ctx session-id` at event-shaping time.

#### Attempt id

Use the already canonical prepared-turn id as the attempt id for this slice.

Concrete rule:

- `:attempt-id` = `(:prepared-request/id prepared-request)` / returned `turn-id`

Anchoring invariant for retry flows:

- retries must execute through a freshly prepared request whose `:prepared-request/id` is a fresh turn id for that retry attempt
- this is not merely a design assumption; implementation must pin it with focused proof at the retry flow boundary
- the proof surface for this task is:
  - request assembly already defines `:prepared-request/id` from normalized `:turn/id`
  - execution result echoes that same id as both `:execution-result/turn-id` and `:execution-result/prepared-request-id`
  - retry flow tests must show that after `:on-retry-triggered` → `:on-retry-resume` → next prepared execution, the next attempt runs with a different prepared-request/turn id than the failed prior attempt

Verification expectation added for this design choice:

- focused retry-flow proof demonstrates that each retry attempt receives a fresh prepared request / turn id, making `:attempt-id == prepared-request/turn-id` unique across attempts in the same overall user turn sequence

Why this is acceptable once proved:

- it is already unique per prepared provider execution attempt
- retries create a new prepared request / new turn id in the existing retry flow
- it avoids inventing a second provider-attempt identity layer unnecessarily

#### Error kind

Current code has retryability heuristics in `psi.session-state.model/retry-error?` but not a reusable canonical emitted error-kind classifier.

This task resolves that gap directly.

Chosen helper:

- add one tiny shared helper in `components/session-state/src/psi/session_state/model.clj` near `retry-error?`
- canonical name: `provider-error-kind`
- signature:

```clojure
(provider-error-kind stop-reason error-message http-status)
```

Return contract:

- returns one canonical keyword when `stop-reason` is `:error`
- returns `nil` for non-error stop reasons

Chosen canonical vocabulary for this task:

- `:rate-limit`
- `:timeout`
- `:overloaded`
- `:auth`
- `:provider-unavailable`
- `:transport`
- `:invalid-request`
- `:unknown`

Chosen precedence rule:

1. non-error stop reason → `nil`
2. explicit auth status/patterns → `:auth`
3. explicit rate-limit status/patterns → `:rate-limit`
4. explicit timeout message from turn-runtime idle timeout → `:timeout`
5. explicit overloaded patterns → `:overloaded`
6. explicit invalid-request status/patterns → `:invalid-request`
7. retryable provider 5xx statuses → `:provider-unavailable`
8. transport/chunk termination/network-like patterns → `:transport`
9. any remaining error stop reason → `:unknown`

Concrete mapping for this slice:

- `http-status` 401 or 403, or message matching `(?i)unauthorized|forbidden|invalid api key|authentication` → `:auth`
- `http-status` 429, or message matching existing rate-limit patterns such as `(?i)rate.limit|too.many.requests|status[ .:_]429` → `:rate-limit`
- message exactly/clearly matching the current turn-runtime timeout text `"Timeout waiting for LLM response"` → `:timeout`
- message matching `(?i)overloaded` → `:overloaded`
- `http-status` 400, 404, or 422, or message matching `(?i)invalid request|bad request|unprocessable` → `:invalid-request`
- `http-status` 500, 502, 503, or 529 → `:provider-unavailable`
- message matching transport-style failures already used by retry heuristics, such as `(?i)premature end of chunk coded message body|closing chunk expected`, plus other network/stream terms if needed locally, → `:transport`
- any other `:error` stop reason → `:unknown`

Important local distinction chosen here:

- provider-declared service failures represented by retryable 5xx HTTP statuses map to `:provider-unavailable`
- lower-level stream/connection/body corruption failures map to `:transport`

This keeps backend metrics operationally useful without introducing a broad taxonomy system.

Relationship to existing `retry-error?`:

- `retry-error?` remains the yes/no retryability predicate
- `provider-error-kind` is the explanatory classifier used for emitted telemetry
- the two helpers may share local private regex sets if implementation wants to avoid drift, but the public responsibilities must stay separate

This helper should be shared by:

- failed `provider_request_finished` event shaping
- `provider_retry_scheduled` event shaping when an error-kind is available

Focused proof requirements for the helper:

- 429 → `:rate-limit`
- 401/403 → `:auth`
- timeout message → `:timeout`
- overloaded message → `:overloaded`
- retryable 5xx → `:provider-unavailable`
- chunked transport failure text → `:transport`
- invalid request shape/status → `:invalid-request`
- unknown `:error` → `:unknown`
- non-error stop reason → `nil`

### Minimal event emission mechanism

The simplest current-compatible mechanism is to keep using the extension registry directly.

Concrete approach:

- in runtime owners that already receive `ctx`, call `psi.agent-session.extensions/dispatch-in` on `(:extension-registry ctx)`
- event payload should follow the existing extension event convention and include `:type` matching the event name string
- do not introduce a new dispatch effect solely for this task unless implementation discovers a purity/ownership reason strong enough to justify it

### Concrete test owner map

Focused proof should land near the owners above.

- `components/turn-runtime/test/psi/turn_runtime/core_test.clj`
  - request-start emission for streaming and/or non-streaming execution
  - successful request-finish emission
  - failed request event shaping if any part remains owned here
- `components/agent-session/test/psi/agent_session/statechart_actions_test.clj`
  - retry-scheduled emission with selected `:delay-ms` and `:delay-source`
  - terminal failed-finish emission with `:final? true`
  - retrying failed-finish emission with `:final? false` if this owner takes failed-finish responsibility
- `extensions/metrics/test/psi/metrics/extension_test.clj`
  - provider aggregation behavior from synthetic provider telemetry events
- `extensions/metrics/test/psi/metrics/{schema,persistence,counters}_test.clj`
  - provider schema/counter/persistence shape proofs

### Slice A — minimal core telemetry surface

Add the provider telemetry events at the discovered narrow owners above:

1. `provider_request_started` from `turn-runtime.core/execute-prepared-request!`
2. `provider_retry_scheduled` from `dispatch_handlers/statechart_actions.clj` `:on-retry-triggered`
3. `provider_request_finished`
   - success path from `turn-runtime.core/execute-prepared-request!`
   - failed-attempt finality path from the session statechart owner that already knows retry vs terminal

The event names and payload contracts above remain authoritative.

### Slice B — metrics aggregation

Extend metrics to:

- subscribe to the three new event names
- add pure counter helpers for provider stats
- persist the new `:providers` branch
- render the provider summary section

### Slice C — classifier shaping

Implement the now-required tiny backend helper `psi.session-state.model/provider-error-kind` and use it as the sole emitted `:error-kind` source for this task. Do not build a broader error taxonomy subsystem.

## Key invariants

1. provider request count reflects actual provider execution attempts, including retries
2. retry count reflects scheduled retries, not inferred success/failure transitions
3. backoff totals are the sum of the actual selected retry delays, not recomputed later
4. final failure count increments once per terminal failed attempt sequence end, not for intermediate retryable failures
5. provider metrics use backend-emitted canonical error kinds, not metrics-local text parsing
6. event emission must be transport-safe and must not change provider execution behavior
7. existing tool/token metrics behavior remains unchanged

## Edge cases

- **unknown model id** — persist under `"unknown"`
- **unknown provider id** — persist under `"unknown"` rather than dropping the event
- **failed attempt with no canonical error kind** — increment failure counters without touching `:error-types`
- **retry scheduled after a failed attempt** — the failed attempt counts under `:failures`; the later retry scheduling increments `:retries`; the later retry attempt also increments `:requests`
- **non-retryable failure** — emit `provider_request_finished` with `:status :failed`, `:final? true`, and no `provider_retry_scheduled`
- **success after retries** — intermediate failed attempts count as failures but not final failures; final successful attempt counts as success
- **reload** — metrics `defonce` store preserves provider aggregates just as it preserves other aggregates

## Verification expectations

1. exactly one `provider_request_started` event is emitted for each provider execution attempt
2. exactly one `provider_request_finished` event is emitted for each provider execution attempt
3. `provider_retry_scheduled` carries the selected delay actually used by the retry scheduler
4. metrics increments provider/model request counts from started events
5. metrics increments retry counts and backoff totals from retry-scheduled events
6. metrics increments successes/failures/final-failures from finished events using `:status` and `:final?`
7. metrics increments error-type counts from emitted canonical `:error-kind`
8. persisted EDN with provider metrics round-trips through load/save and validates against schema
9. `/metrics` includes a provider section when provider data exists
10. existing metrics tests for tools/tokens remain green without semantic regression

## Acceptance criteria

1. The extension event bus exposes `provider_request_started`, `provider_retry_scheduled`, and `provider_request_finished` with the canonical payload contracts described here.
2. `provider_request_started` is emitted once per provider execution attempt, including retries.
3. `provider_retry_scheduled` is emitted once per scheduled retry and includes the actual selected `:delay-ms`.
4. `provider_request_finished` is emitted once per provider execution attempt and distinguishes success/failure plus `:final?` for terminal failures.
5. `psi.metrics.schema` accepts persisted provider aggregates under top-level `:providers`.
6. `psi.metrics.extension` subscribes to the provider telemetry events and persists request, retry, backoff, success, failure, final-failure, and error-type counts by provider and nested by model.
7. `metrics/summary` returns the provider metrics data unchanged in the persisted summary map.
8. `/metrics` renders a human-readable provider summary section when provider metrics exist.
9. Provider metrics are derived from structured backend events, not metrics-local parsing of free-text error messages.
10. Existing tool and token metrics behavior remains intact.
