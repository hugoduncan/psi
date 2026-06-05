# 211 — Steps

## Slice 1 — Trace and lock backend/RPC trigger gap

- [ ] Trace where provider/LLM retry state is written into session state and where retry clear/terminal state is written.
- [ ] Trace the RPC projection listener or event-emission path that currently publishes `footer/updated` for session/footer changes.
- [ ] Identify the missing invalidation/emission edge, or confirm the existing edge and document the exact path in `implementation.md`.
- [ ] Add backend/RPC test coverage that drives the real provider retry-state mutation/invalidation edge used by retry scheduling, and asserts an Emacs-visible `footer/updated` event is emitted.
- [ ] In that backend/RPC test, assert the emitted footer payload contains the app-runtime retry footer text (for example `retry in 8s`) and the matching `:session-id`.
- [ ] Run the focused backend/RPC test and confirm it fails without the trigger fix or acts as a regression lock if the trigger already exists.

## Slice 2 — Publish footer on retry activation/change/clear

- [ ] Implement the minimal backend/RPC/app-runtime projection-boundary fix so retry activation publishes `footer/updated`.
- [ ] Extend or add backend/RPC coverage for a backend-published retry change that alters visible retry text (wait/resume time, attempt/source, or rate-limit remaining/reset detail), asserting a fresh `footer/updated` payload with the latest projected retry text.
- [ ] Extend or add backend/RPC coverage for retry clear/terminal state, asserting a `footer/updated` payload with stale retry text removed.
- [ ] Verify the fix preserves existing footer payload fields (`:path-line`, `:stats-line`, `:status-line`, usage/model/session-activity fields) and `:session-id` matching.
- [ ] Run focused backend/RPC tests covering the retry footer trigger sequence.
- [ ] Run existing app-runtime footer/retry-display tests and confirm formatter expectations remain valid.

## Slice 3 — Emacs-visible `footer/updated` rendering coverage

- [ ] Add an Emacs UI test that handles a retry-bearing `footer/updated` event and asserts the visible buffer/projection footer includes the projected retry text.
- [ ] Add an Emacs UI test that handles a second retry-bearing `footer/updated` event with changed retry text and asserts the visible footer updates to the new text.
- [ ] Add an Emacs UI test that handles a cleared `footer/updated` event and asserts the visible footer no longer contains stale retry text.
- [ ] Ensure the Emacs tests assert rendered buffer/footer text, not only `psi-emacs-state-session-retry` or raw session storage.
- [ ] Ensure Emacs does not synthesize retry wording: tests should feed projected footer payload text and assert that text is rendered.
- [ ] Run the focused Emacs UI tests for footer/projection event handling.

## Slice 4 — Optional `session/updated` fallback only if required

- [ ] Decide from Slice 1 investigation whether a `session/updated` fallback is required; if not required, record the decision in `implementation.md` and leave this slice otherwise untouched.
- [ ] If required, implement only a trigger/reuse path for the app-runtime-owned footer projection when `footer/updated` is unavailable.
- [ ] If required, add separate Emacs-visible fallback coverage proving a retry-bearing `session/updated` event triggers or reuses app-runtime footer projection delivery.
- [ ] If required, assert the fallback path does not format retry text in Emacs and does not create a parallel Emacs-only retry display.
- [ ] Run the fallback-specific Emacs UI tests if this slice is implemented.

## Slice 5 — Final verification and docs/changelog assessment

- [ ] Run the focused backend/RPC retry footer trigger tests.
- [ ] Run the focused app-runtime footer/retry-display tests.
- [ ] Run the focused Emacs UI footer/projection tests.
- [ ] Run the relevant broader test suite(s) for touched components.
- [ ] Run lint/format checks appropriate for touched Clojure and/or Emacs Lisp files.
- [ ] Update `CHANGELOG.md` if the fix is user-visible as a bug fix.
- [ ] Update user docs only if the documented retry/footer/event behaviour changes; otherwise record in `implementation.md` that no docs update was needed.
- [ ] Confirm all design acceptance criteria are covered by tests: active retry visible, no manual refresh, clear removes stale text, backend/RPC trigger proof, active/change/clear event sequence, and optional fallback coverage only if used.
- [ ] Commit the completed implementation slices with searchable `⚒ 211` commit messages.

## Plan ambiguity follow-ups

- [x] PA1: Clarify Slice 1 backend/RPC trigger coverage: the retry activation test must exercise the same session retry-state mutation/invalidation edge used by real provider retry scheduling, not only call `footer-updated-payload` directly or manually emit `footer/updated`.
- [x] PA2: Clarify Slice 2 changed-retry coverage: name the retry fields whose backend-published changes must produce a fresh footer projection (at minimum those that alter visible retry text such as wait/resume time, attempt/source, and rate-limit remaining/reset detail), or explicitly narrow the required representative changed-state case.
