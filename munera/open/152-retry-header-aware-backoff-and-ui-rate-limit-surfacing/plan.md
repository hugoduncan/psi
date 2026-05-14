# Plan

## Approach

1. Inspect the current retry path end-to-end so the implementation rides the existing canonical session retry mechanism rather than inventing a parallel transport-local retry path.
   - confirm where provider error headers are available today
   - confirm where assistant error messages / turn results preserve or drop headers
   - confirm the narrowest owner that should normalize retry/rate-limit headers

2. Introduce one canonical retry-metadata normalization helper in the session/retry ownership layer.
   - support case-insensitive lookup for standard and `X-` prefixed retry/rate-limit headers
   - parse `Retry-After` / `X-Retry-After`
   - parse `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` and `X-` variants
   - normalize ambiguous reset values according to one explicit documented backend rule
   - return one coherent normalized retry metadata shape suitable for session state and UI projection

3. Extend the error/result propagation path just enough to preserve provider headers to the retry scheduling point when present.
   - keep raw provider-header capture at the transport boundary
   - ensure the canonical session retry path can see the headers without making UI/projection code parse provider responses directly

4. Extend session state with one coherent retry metadata surface.
   - record selected delay ms
   - record delay source (`:retry-after` vs `:exponential-backoff`)
   - record retry resume instant
   - record normalized rate-limit metadata when available
   - clear or replace stale retry metadata when retry waiting ends or is superseded

5. Update the retry scheduling handler to use normalized provider-aware delay selection.
   - prefer valid `Retry-After`
   - otherwise fall back to the existing exponential backoff calculation
   - keep retryability classification unchanged and centralized
   - keep the existing bounded retry statechart path intact

6. Surface retry metadata through shared backend projections.
   - extend session summary / status projection with retry timing and normalized rate-limit fields
   - extend RPC `session/updated` payload with the same information
   - keep the backend projection authoritative so frontends never parse raw provider headers

7. Render the surfaced retry/rate-limit information in both interactive UIs.
   - Emacs: include retry timing and available rate-limit information in the session/status diagnostics/header-adjacent status surface
   - TUI: include retry timing and available rate-limit information in the status/footer/session summary surface
   - keep wording UI-appropriate while preserving shared semantics

8. Add focused proof.
   - header parsing and normalization
   - retry-after precedence over exponential fallback
   - fallback behavior when headers are missing/invalid
   - retry scheduling uses the selected delay
   - session/RPC projection contains retry metadata
   - UI-facing status/projection content includes retry timing and rate-limit information

## Decisions to make during implementation

- the explicit numeric interpretation rule for `RateLimit-Reset`
- whether to surface both absolute instants and preformatted relative helper text from the backend, or only normalized raw values plus existing UI formatting

## Design decisions fixed by ambiguity follow-up

- canonical retry surface: one shared nested `:retry` map with fields `:active?`, `:attempt`, `:delay-ms`, `:delay-source`, `:resume-at`, and optional nested `:rate-limit {:limit :remaining :reset-at :reset-after-ms}`
- projection parity rule: session summary, Pathom/resolver surfaces, and RPC `session/updated` all preserve that same nested shape and field naming; no projection-specific flattening/renaming
- lifecycle clearing rule: the session retry lifecycle owner clears `:retry` when active retry wait ends; `on-agent-done` and other terminal non-retrying completion paths must clear it explicitly
- TUI acceptance surface: the existing session summary/status line surface is the required visible TUI surface for this task
- Emacs acceptance surface: the existing session/status diagnostics surface is the required visible Emacs surface for this task
- transport propagation contract: provider response headers must reach the session retry owner under `:provider-error/headers` on the terminal error/result map consumed by `:on-retry-triggered`

## Risks

- provider error headers may currently be captured at transport level but dropped before the session retry scheduler sees them, requiring careful propagation through turn/runtime/session layers
- `RateLimit-Reset` semantics differ across providers, so an overly permissive parser could normalize incorrectly unless the rule is explicit and tightly tested
- surfacing new retry metadata through shared session projections may require small coordinated changes across session summary, RPC event schemas, Emacs projection state, and TUI/footer composition
- time-based UI proof can become flaky unless tests pin time and assert normalized values or deterministic formatted fragments

## Verification

- focused unit tests for retry header lookup and normalization
- focused session/statechart handler tests proving retry scheduling honors provider-specified delay when valid and falls back otherwise
- focused projection/RPC tests proving retry metadata appears in `session/updated`
- focused Emacs/TUI-facing projection tests proving retry timing and rate-limit information is visible from the surfaced backend state
- relevant focused suites for touched namespaces