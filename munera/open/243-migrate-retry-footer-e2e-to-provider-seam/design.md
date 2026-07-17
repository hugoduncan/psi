# Design — 243 Migrate retry-footer E2E tests off `with-redefs` onto the provider-registry seam

## Goal

Remove the standing ¬mock/¬stub violation in the retry-footer end-to-end test
harness by migrating both call sites off `with-redefs` of the
`turn-runtime/execute-live-turn!` logic boundary onto the confirmed injectable
provider-registry seam, so retryable provider failures are driven through a real
stub provider instead of a redefined internal fn.

## Context

Task 242 added RPC-level end-to-end retry→`footer/updated` coverage that crosses
the real `rpc.events/emit-event!` / `focus-allows?` focus gate. To drive a
retryable provider-boundary failure (429 → activation → changed metadata →
recovery/clear), the test harness redefines `turn-runtime/execute-live-turn!`
via `with-redefs` to fabricate 429/recovery turns. This is a stub of a **logic
boundary**, not a nullable/injectable seam, and violates the project's
¬mock/¬stub testing standard. It is also linked to the recorded parallel
`with-redefs` test-isolation flakiness observed in task 242.

Task 242's test-review passes evaluated and **deferred** this migration (it
exceeded task 242's frozen behaviour-preserving test-coverage scope), but
confirmed that a **clean injectable seam exists**:

- `psi.ai.core/create-context` seeds a per-ctx `:provider-registry`.
- A stub provider that emits stream `:error` events carrying `:http-status` and
  `:provider-error/headers` drives the same retry path, because
  `make-provider-event-consumer`'s `:error` case propagates those keys through
  the statechart into the assistant-message (verified in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`).

This task is the tracked exit for that deferred violation.

## Scope

Two call sites in `components/rpc/test/psi/rpc_prompt_test.clj` share the
identical `with-redefs turn-runtime/execute-live-turn!` stub and must be
co-migrated together:

1. `drive-provider-retry-through-progress-loop!` — the helper used by
   `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
   (the task-242 focus-gate boundary test).
2. `rpc-prompt-provider-retry-state-publishes-footer-updated-test` — the sibling
   pre-gate characterization test, which inlines its own identical
   `with-redefs`.

## Approach (design-only; details deferred to plan)

- Stand up a stub provider matching the provider protocol, registered via the
  per-ctx `:provider-registry` from `psi.ai.core/create-context`, that emits
  stream `:error` events carrying `:http-status` and `:provider-error/headers`
  for the retry attempts (429 with `Retry-After: 8`, then `Retry-After: 4` with
  changed `RateLimit-Remaining`), then a successful recovery turn.
- Replace both `with-redefs` sites with provider-registry injection, preserving
  the existing behaviour exactly: same three retry frames (activation
  `retry in 8s`, changed metadata `retry in 4s` + `remaining 2/5000`, and clear
  with no stale `retry in` text), same `emit-frame!` boundary assertions, same
  focused/background focus-gate assertions.
- Keep the deterministic `await-retry-footer-text!` synchronization pattern (the
  retry state is read live at footer-delivery time; a no-op sleep races the
  async progress loop).

## Constraints

- Behaviour-preserving: no product code change; test coverage and assertions
  must remain equivalent (both retry-footer tests keep asserting the same
  frames and gate behaviour).
- Do not weaken `focus-allows?` or the task-241 no-cross-session-leakage
  invariant.
- Follow the ¬mock/¬stub standard: use a real stub provider through the
  injectable seam, not `with-redefs` of an internal logic fn.

## Acceptance

- Neither retry-footer test uses `with-redefs` of
  `turn-runtime/execute-live-turn!` (nor any other logic boundary) to drive the
  retry sequence; both drive it through a stub provider registered in the
  per-ctx `:provider-registry`.
- `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  still asserts all three retry frames cross the RPC focus gate for the focused
  session, and no frames cross for the background session.
- `rpc-prompt-provider-retry-state-publishes-footer-updated-test` still verifies
  the same activation/changed/clear footer sequence at the pre-gate `emit!`.
- `bb test --focus psi.rpc-prompt-test` is green.
- The task-242 code comment recording the deferred `with-redefs` exception is
  removed (the seam is now in use), and the parallel `with-redefs`
  test-isolation flakiness attributed to this pattern is re-evaluated.

## Notes

- Origin: task 242 steps.md Slice 7 (2nd task-test-review pass) follow-up.
- Forwarded from task 242 Slice 23 (implementation-review): the retry-footer
  harness accumulated ~15 single-authority matcher/format helpers/constants
  (Slices 17–22) that are disproportionate to a single test pair. The sharpest
  case is `active-retry-text-prefix`, which derives the fixed `"retry in "`
  prefix by length-subtracting `(format-relative-seconds 0)` off
  `retry-status-text {:active? true :resume-at 0}` — indirect and brittle
  (breaks silently if production reorders/space-pads the status-line fragment).
  When this rewrite reconstructs the matcher/format helpers, prefer deriving the
  awaited retry-footer text directly from the production authority
  `psi.app-runtime.retry-display/retry-status-text` (or an explicit literal)
  rather than the length-subtraction idiom, and collapse the aggregate helper
  count where the provider-seam harness no longer needs the separate driver-vs-
  matcher config-coupling authorities. Task 242 kept these helpers in place
  (green, frozen) and forwarded the over-abstraction concern here rather than
  churn a soon-to-be-replaced harness.
- Forwarded from task 242 Slice 28 (code-shaper review): `focus-gated-emitter!`
  (Slice 20) and `default-focus-emitter!` (Slice 25) are near-duplicate 6-line
  emitter builders in `rpc_prompt_test.clj` differing *only* in the
  `set-focus-session-id!` argument (explicit `session-id` for the explicit-focus
  branch vs `nil` for the `default-session-id` fallback branch). Both re-spell
  the identical `make-rpc-state → subscribe-topics! → set-focus-session-id! →
  make-request-emitter` sequence, so a change to the shared emitter wiring must
  be edited at both sites and can drift between the two builders meant to
  exercise the *same* gate under explicit-vs-fallback focus. When this rewrite
  reconstructs the harness, collapse them onto one parameterized builder (e.g.
  `focus-emitter! [session-id focus]` where `focus` is the explicit `session-id`
  or `nil`) co-migrated alongside the two `with-redefs` call sites. Task 242 left
  the two builders in place (green, frozen) and forwarded the dedup here per
  Slice 23's aggregate-over-abstraction judgement, rather than encode yet another
  helper in the soon-to-be-replaced harness.
- Related: `munera/closed/242-retry-footer-focus-gate-regression/` (see its
  implementation.md Slice 6 for the seam-existence verification),
  `munera/closed/241-emit-only-focused-session-events/` (focus-gate origin).
- Design-only: plan.md / steps.md to be written before execution.
