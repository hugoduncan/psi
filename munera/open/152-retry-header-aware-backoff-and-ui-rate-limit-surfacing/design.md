# 152 — retry header-aware backoff and UI rate-limit surfacing

## Goal

Make session auto-retry timing provider-informed and user-visible by honoring retry/rate-limit response headers, normalizing them into canonical retry metadata, and surfacing both retry delay and rate-limit state in interactive UIs.

## Why

ψ already has bounded session auto-retry with exponential backoff and explicit retryability classification for transient provider failures. That solves whether to retry, but not how long to wait according to provider guidance, nor what the operator can see while waiting.

Today, retry timing is incomplete in three ways:

- the runtime does not honor `Retry-After` when providers send an explicit wait duration
- the runtime does not normalize standard `RateLimit-*` or legacy `X-RateLimit-*` headers into a canonical retry/rate-limit view
- interactive UIs show that a session is retrying, but not when the next retry will occur or what rate-limit information the provider returned

This makes retry behavior less respectful of provider policy, harder to reason about, and harder to debug in live TUI/Emacs sessions.

## Problem

Provider/API failures that are already classified as retryable may include response headers that carry authoritative or useful retry guidance:

- `Retry-After`
- `X-Retry-After`
- `RateLimit-Limit`
- `RateLimit-Remaining`
- `RateLimit-Reset`
- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

Today:

- retry delay is always chosen by local exponential backoff
- rate-limit headers are not parsed into canonical session state
- `session/updated` only exposes retry attempt count, not retry timing or rate-limit context
- Emacs and TUI can show `retrying`, but cannot tell the user `retrying in 8s`, `rate limit resets at ...`, or `remaining 0 / limit 5000`

As a result, users can observe an error and a retrying phase, but not the most actionable operational information about what the runtime is waiting for.

## Intent

Create one canonical retry-metadata surface that:

1. prefers provider-supplied retry delay when present and valid
2. falls back to the existing exponential backoff when provider guidance is absent or invalid
3. records normalized retry/rate-limit metadata in session state
4. exposes that metadata through backend projections and RPC events
5. renders the retry timing and available rate-limit information in the interactive UI surfaces

## Desired outcome

When a session encounters a retryable provider failure:

- the retry path still uses the existing bounded session retry mechanism
- if the provider supplied a valid `Retry-After` or `X-Retry-After` value, that value determines the retry delay
- otherwise the runtime uses the existing exponential backoff calculation
- any available `RateLimit-*` / `X-RateLimit-*` metadata is parsed and stored in normalized form
- the session summary and RPC `session/updated` payload expose the current retry timing and rate-limit metadata
- Emacs and TUI can render human-meaningful retry state such as `retrying in 8s` and available rate-limit context such as `remaining 0/5000` or `reset in 32s`
- retry metadata is cleared or replaced when it is no longer the current active retry state

## In scope

- canonical parsing of retry-related headers from retryable provider error events
- support for both standard and `X-` prefixed forms of:
  - `Retry-After`
  - `RateLimit-Limit`
  - `RateLimit-Remaining`
  - `RateLimit-Reset`
- canonical session-owned retry metadata describing:
  - selected retry delay
  - delay source
  - next retry time
  - parsed rate-limit data when present
- wiring the selected retry delay into the existing `:on-retry-triggered` path
- surfacing retry timing and rate-limit metadata through backend projections and RPC events
- surfacing retry timing and rate-limit information in UI status surfaces used by Emacs and TUI
- focused tests for parsing, backoff selection, state projection, and UI-visible payloads

## Out of scope

- adding provider-transport-local retry loops outside the canonical session retry path
- broad retry policy redesign beyond header-aware delay selection
- changing which failures are retryable except where necessary to preserve the existing retry path
- speculative retries for otherwise non-retryable failures
- provider-specific UX beyond the canonical shared retry/rate-limit surface

## Canonical concepts

### Retry metadata

The canonical session-owned summary of the currently scheduled retry wait.

Canonical shape for this task:

- `:retry` → one nested map surfaced unchanged across session-owned summary/projection layers
  - `:active?` — whether a retry wait is currently scheduled and not yet cleared
  - `:attempt` — retry attempt count for the currently scheduled retry
  - `:delay-ms` — selected delay in milliseconds
  - `:delay-source` — `:retry-after` or `:exponential-backoff`
  - `:resume-at` — absolute retry resume instant in epoch milliseconds
  - `:rate-limit` — nested normalized rate-limit map when any rate-limit headers were present
    - `:limit`
    - `:remaining`
    - `:reset-at` — absolute reset instant in epoch milliseconds when derivable
    - `:reset-after-ms` — relative reset duration in milliseconds when derivable

Projection rule:

- session summary/pathom/RPC `session/updated` should all expose the same backend-owned nested `:retry` map and field names rather than flattening or renaming per projection
- UI-specific rendering may choose different wording, but it must consume this same authoritative shape

Lifecycle rule:

- active retry metadata is owned by session/runtime state for the current scheduled wait only
- the canonical clearing point is the session retry lifecycle owner that transitions out of active retry wait
- successful or otherwise terminal non-retrying completion, including `on-agent-done`, must clear `:retry` so stale retry metadata does not survive past the active wait
- a newly scheduled retry replaces the previous `:retry` map wholesale rather than mutating it piecemeal

Optional supporting fields may include raw header provenance if they remain clearly secondary to the canonical normalized values above.

### Rate-limit metadata

The canonical normalized view of the provider's currently known rate-limit state.

Minimum supported fields when present:

- limit
- remaining
- reset instant or reset-relative timing

The runtime should normalize this into the nested `:retry/:rate-limit` shape above so UI and projections do not parse provider headers independently.

### UI retry surface

The backend-projected surface that gives interactive frontends enough information to render:

- that the session is retrying
- how long until the next retry
- what rate-limit state is known

This surface must be shared and authoritative so Emacs and TUI do not drift semantically.

Backend-owned visible surfaces for acceptance:

- TUI: the session summary/status line surface that already renders session activity state is the required visible surface for this task
- Emacs: the session/status diagnostics surface that already renders session activity state is the required visible surface for this task

Acceptance for UI surfacing is satisfied when those named surfaces visibly include retry timing and any available normalized rate-limit information derived from the shared `:retry` map.

## Canonical behavior

1. a provider failure is classified as retryable by the existing session retry classifier
2. the terminal provider/assistant error shape available to the retry path includes response headers when present under one canonical key/path carried to `:on-retry-triggered`
   - canonical propagation contract for this task: the retry scheduling path receives provider response headers from the terminal error/result map at `:provider-error/headers`
   - transport/provider code may use local/raw shapes internally, but the turn/runtime boundary must normalize them onto `:provider-error/headers` before the session retry owner consumes them
3. the retry path attempts to extract normalized retry metadata from those headers
4. if a valid `Retry-After` or `X-Retry-After` exists, it determines the retry delay
5. otherwise the delay falls back to the existing exponential backoff function
6. any valid `RateLimit-*` / `X-RateLimit-*` values are normalized into canonical rate-limit metadata
7. the session records the retry metadata before or as the retry is scheduled
8. the scheduled retry effect uses the chosen delay
9. while the session remains in retry wait, backend projections expose the retry/rate-limit metadata
10. Emacs and TUI render the waiting period and rate-limit information from the shared backend surface
11. once retry waiting ends or the retry state is superseded, stale retry metadata is cleared or replaced

## Header support requirements

### Retry-After

Support both:

- `Retry-After`
- `X-Retry-After`

The parser must accept the standard valid forms relevant here:

- delta seconds
- HTTP date when present and parseable

If parsing fails, the header must be ignored and the runtime must fall back to exponential backoff.

### Rate-limit headers

Support both standard and `X-` prefixed names:

- `RateLimit-Limit` / `X-RateLimit-Limit`
- `RateLimit-Remaining` / `X-RateLimit-Remaining`
- `RateLimit-Reset` / `X-RateLimit-Reset`

Header lookup must be case-insensitive by behavior, either because headers are normalized first or because lookup explicitly handles case variants.

## Normalization requirements

The runtime must choose one canonical interpretation for ambiguous numeric reset values so the ambiguity is resolved once in backend code rather than repeatedly in the UI.

Canonical numeric `RateLimit-Reset` rule for this task:

- parse the value as an integer when possible
- values `>= 1000000000000` are interpreted as epoch milliseconds
- otherwise values `>= 1000000000` are interpreted as epoch seconds and normalized to epoch milliseconds
- otherwise values are interpreted as relative seconds from now and normalized into both `:reset-after-ms` and derived absolute `:reset-at`

This rule is backend-owned, must be documented in code, and must be asserted directly by focused tests.

The same applies to `Retry-After` HTTP-date parsing: the backend must normalize it to an absolute retry instant and derived delay.

## State ownership requirements

- retry timing and rate-limit data must be owned by session/runtime state, not recomputed independently by each UI
- UI consumers must receive normalized values through projections/events rather than raw provider headers
- provider transport namespaces may continue to capture raw headers on error events, but canonical interpretation belongs in the retry/session layer

## Projection and UI requirements

The backend projection layer must expose enough information for UI rendering without frontend header parsing.

Minimum required surfaced information:

- whether a retry wait is active
- retry attempt count (existing)
- selected retry delay or time-until-retry
- retry resume instant or equivalent canonical wait target
- delay source (`retry-after` vs exponential backoff)
- normalized rate-limit fields when available

The user-visible UI requirement is not merely backend availability; the interactive UI must actually surface the information.

Minimum visible UI outcome:

- Emacs must surface retry timing and available rate-limit information in its session/status diagnostics surface
- TUI must surface retry timing and available rate-limit information in its status/footer/session summary surface

The exact rendered wording may differ by UI, but both must be driven from the same normalized backend semantics.

## Preferred implementation shape

Preferred order:

1. preserve the existing retryability classifier
2. add a canonical retry-header parsing / normalization helper in the retry/session ownership layer
3. extend session state with one coherent retry metadata shape rather than many loosely related scalar fields, unless existing projection constraints strongly favor a small fixed set
4. update the retry scheduling handler to use normalized provider-guided delay with exponential fallback
5. extend session summary / RPC payloads with explicit retry metadata
6. update Emacs and TUI projections to render the surfaced retry/rate-limit information

## Acceptance

- valid `Retry-After` causes the retry delay to follow provider guidance instead of exponential fallback
- valid `X-Retry-After` is treated equivalently
- missing or invalid retry-after headers fall back to the existing exponential backoff behavior
- `RateLimit-Limit`, `RateLimit-Remaining`, and `RateLimit-Reset` are parsed when present
- `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` are parsed equivalently
- reset values are normalized according to one explicit documented backend rule
- the chosen retry timing and parsed rate-limit metadata are stored in canonical session-owned state
- `session/updated` exposes the retry/rate-limit data needed by frontends
- Emacs visibly surfaces retry timing and available rate-limit information
- TUI visibly surfaces retry timing and available rate-limit information
- stale retry metadata does not survive past the active retry state incorrectly
- focused tests prove:
  - retry-after parsing and precedence
  - fallback backoff behavior
  - rate-limit header normalization
  - retry scheduling uses the selected delay
  - projection/RPC payload contains the surfaced retry metadata
  - UI-facing projection content includes retry/rate-limit information

## Notes

This task is about retry mechanics and visibility, not about redefining retryability itself. Existing retry classification should remain centralized and authoritative; this task refines the timing and observability of that existing retry path.
