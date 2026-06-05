# Emacs retry backoff footer visibility

## Goal

When an LLM/provider request enters retry backoff, Emacs must visibly surface the
retry wait state in the normal psi buffer UI, using the existing footer retry
message path where possible.

## Problem

Retry/backoff state is already represented and tested in backend/app-runtime
projections:

- `psi.app-runtime.retry-display/retry-status-text` formats human-readable footer
  text such as `retry in 8s` with rate-limit details.
- `psi.app-runtime.footer/footer-model-from-data` includes that retry text in the
  footer status line when `:psi.agent-session/retry` is active.
- RPC/session summary tests prove retry metadata and `status-session-line` can be
  produced.
- Emacs tests prove `session/updated` preserves nested retry payloads.

However, during real Emacs usage, no visible message appears while waiting for a
retry to back off. This suggests the display code exists but is not being
triggered or refreshed through the Emacs-visible footer path during retry state
changes.

## Scope

Investigate and fix the end-to-end trigger path from active retry state to Emacs
footer visibility.

In scope:

- Determine whether `footer/updated` is emitted when `:psi.agent-session/retry`
  becomes active, changes, and clears.
- Ensure Emacs receives and renders a footer update while a retry backoff is
  active.
- Preserve the existing footer retry wording/formatting unless the investigation
  proves it is structurally unsuitable.
- Add tests that fail without the trigger/refresh fix and prove the retry message
  is visible in Emacs, not merely stored in internal state.
- Ensure retry clear/terminal states remove the visible retry footer message.

Out of scope:

- Redesigning provider retry policy, retry scheduling, or retry metadata shape.
- Changing retry timing/backoff calculations.
- Adding a new transcript assistant/error message for retry backoff unless the
  footer path cannot satisfy the visibility requirement.
- Broad Emacs footer/projection redesign unrelated to retry-trigger visibility.

## Acceptance criteria

- When a session enters active retry backoff, the Emacs buffer visibly displays
  the existing footer retry text, e.g. `retry in 8s` and any available
  rate-limit details.
- The visible retry text appears without requiring a manual refresh or separate
  status diagnostics command.
- When retry state clears, the visible footer no longer shows stale retry text.
- Tests cover the Emacs-visible behavior by asserting rendered buffer/footer text
  after retry-related events, not only `psi-emacs-state-session-retry` storage.
- Tests cover the backend/RPC trigger path enough to prove active retry state
  causes a footer refresh/update to be sent to Emacs.
- Tests cover the retry footer event sequence for active retry state, changed
  retry state, and cleared retry state. The primary expected path is
  `footer/updated`; if the implementation uses a `session/updated` fallback
  because `footer/updated` is unavailable, that fallback path must have separate
  Emacs-visible coverage proving it triggers or reuses the app-runtime-owned
  footer projection.
- Existing app-runtime footer formatting tests remain valid and unchanged unless
  a small wording adjustment is explicitly justified.

## Likely investigation points

- `components/app-runtime/src/psi/app_runtime/footer.clj`
  - Already includes `:psi.agent-session/retry` in `footer-query` and footer
    status-line formatting.
- `components/app-runtime/src/psi/app_runtime/retry_display.clj`
  - Owns retry footer text.
- RPC event publishing for `footer/updated`
  - Verify retry state changes are included in whatever invalidation/refresh path
    emits footer projections.
- `components/emacs-ui/psi-events.el`
  - Handles `footer/updated` and `session/updated`; confirm retry updates use the
    path that re-renders the projection footer.
- `components/emacs-ui/test/psi-extension-ui-test.el` or adjacent Emacs UI tests
  - Add focused visible-footer coverage.
- Existing retry tests in `components/emacs-ui/test/psi-streaming-runtime-test.el`
  - These prove state preservation but should not be mistaken for visibility
    coverage.

## Design notes

Prefer making the existing footer projection path fire correctly over adding a
parallel Emacs-only retry display. The footer is already the adapter-neutral UI
surface for compact session status and has tested retry formatting; the likely
root cause is missing invalidation/event emission rather than missing formatting.

### Retry footer freshness

The footer must refresh when retry state is first published, when the backend
publishes materially changed retry state (for example a new wait duration,
attempt, or rate-limit detail), and when retry state clears. It does not need an
Emacs-local countdown timer that ticks every second during a single unchanged
backoff window. If the visible text says `retry in Ns`, it may remain at the
last app-runtime-projected value until the backend publishes another retry/footer
projection or clears the retry state. This keeps the app-runtime footer
projection as the owner of retry wording and time calculations, and avoids a
parallel Emacs countdown model.

### Required event coverage

Implementation tests must distinguish storage from visible rendering. Required
coverage is:

- backend/RPC trigger coverage proving retry activation causes an Emacs-visible
  footer update path to be published;
- Emacs-visible `footer/updated` coverage for active retry state showing the
  rendered footer includes the existing retry text;
- Emacs-visible `footer/updated` coverage for changed retry state showing the
  rendered footer updates to the latest app-runtime-projected retry text;
- Emacs-visible `footer/updated` coverage for cleared retry state showing stale
  retry text is removed;
- if and only if the implementation relies on a `session/updated` fallback,
  separate Emacs-visible coverage that a retry-bearing `session/updated` event
  triggers or reuses the app-runtime-owned footer projection rather than merely
  preserving nested retry data in `psi-emacs-state-session-retry`.

If the investigation shows that `session/updated` reliably arrives but
`footer/updated` does not, the fix should be at the backend/RPC/app-runtime
projection boundary so all footer consumers stay coherent. Only use a
`session/updated` fallback to trigger or reuse delivery of the app-runtime-owned
footer projection when `footer/updated` is unavailable. The fallback must not
synthesize retry footer wording in Emacs and must not create a parallel
Emacs-only retry display.
