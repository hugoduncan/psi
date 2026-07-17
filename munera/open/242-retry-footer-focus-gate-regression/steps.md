# Steps — 242 Retry backoff footer no longer visible in Emacs

## Slice 1 — Diagnose

- [x] Read `components/rpc/src/psi/rpc/events.clj` (`focus-allows?`,
      `emit-event!`) and `components/rpc/src/psi/rpc/session/emit.clj`
      (`emit-footer-updated!`) to confirm the current gate + session-id
      stamping behaviour.
- [x] Build a minimal retry reproduction: force a retryable provider-boundary
      failure (429 / bad key via nullable provider) so `mark-active-retry!`
      fires and a `:retry-updated` progress event flows.
- [x] Probe the focused-session case: with the retrying session as effective
      focus, check whether the retry `footer/updated` frame reaches
      `emit-frame!`.
- [x] Probe the background case: with focus on another session, confirm the
      retry `footer/updated` frame is suppressed (expected by task-241 gate).
- [x] Record the diagnosis (focused-broken vs background-only) in
      `implementation.md`.

## Slice 2 — End-to-end regression-lock test

- [x] Add an RPC-level test (in `components/rpc/test/psi/rpc_prompt_test.clj`,
      alongside the existing raw-`emit!` retry/footer characterization test,
      driving the same retry scenario but through `rpc.events/emit-event!` /
      `focus-allows?`) that drives a provider-boundary retry in the focused
      session through the progress path and asserts a `footer/updated` frame
      with a retry-backoff `:status-line` (matching `retry in`) reaches
      `emit-frame!`.
- [x] Make the test deterministic: the retry-backoff sleep-fn blocks (bounded,
      500ms) until the awaited footer text has actually been captured, instead
      of a zero/no-op sleep — a no-op sleep raced the async progress-loop
      (10ms poll) and let `clear-active-retry!` clear the retry state before
      the loop delivered the corresponding frame, producing a false failure.
- [x] Run the new test and record its initial result (failing vs passing) in
      `implementation.md` — this is the AC1 branch evidence.

## Slice 3 — Fix or determination (branch on diagnosis)

If focused session is broken:
- [ ] Identify the minimal repair so the retry `footer/updated` `:session-id`
      matches effective focus at retry time (do not weaken `focus-allows?`).
- [ ] Implement the fix; new E2E test transitions failing → passing.
- [ ] Record the root cause and fix in `implementation.md`.

If background-only (working as intended):
- [x] Record the "working as intended" determination in `implementation.md`,
      with the evidence from Slice 1.
- [x] Note (without implementing) whether/where background retry state could
      surface (e.g. session-activity line) as a possible follow-up task.

## Slice 4 — Verify and record

- [x] Run existing task-241 focus-gate tests; confirm green (no cross-session
      leakage regression).
- [x] Run the full test suite (`bb test`); confirm green (modulo pre-existing,
      baseline-reproduced parallel-`with-redefs` flakiness unrelated to this
      change — see implementation.md).
- [x] Lint changed files (`clj-kondo`) and repair formatting
      (`clj-paren-repair`) as needed.
- [x] Update `implementation.md` with final diagnosis, outcome, and any scope
      notes; check doc coherence (no user-facing doc change needed — the
      focused footer behaviour did not change, only test coverage was added).
- [x] Commit with a symbol-tagged message (⊘/⚒) referencing task 242.

## Slice 6 — Test-review follow-ups (task-test-review)

- [x] Focus-gate coverage is asymmetric with the pre-gate sibling test: the
      focused sub-test in
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
      only asserts the retry *activation* footer (`retry in 8s`) crosses the
      gate. The regression is per-frame focus gating, and the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test` verifies
      all three retry frames (activation `retry in 8s`, changed metadata
      `retry in 4s` + `remaining 2/5000`, and clear = no stale `retry in`
      text). Extend the focused sub-test to assert the changed-metadata and
      cleared footers also reach `emit-frame!` through the gate — otherwise a
      regression that gates only the later frames would go undetected.
- [x] Infra-dep is a `with-redefs` stub of a logic boundary, not a nullable:
      `drive-provider-retry-through-progress-loop!` redefines
      `turn-runtime/execute-live-turn!` to fabricate 429/recovery turns.
      implementation.md already attributes parallel `with-redefs`
      test-isolation flakiness to this pattern (shared with the sibling test).
      Evaluate an injectable/nullable provider seam (e.g. a provider stub
      passed via the provider-registry / ai-ctx) so the retry-footer E2E tests
      can drive retryable failures without `with-redefs` of a logic boundary,
      per the project's ¬mock/¬stub testing standard. If left as-is, record the
      explicit rationale (no clean seam at `execute-live-turn!`) so future
      readers know it is a deliberate, bounded exception.

## Slice 7 — Test-review follow-ups (2nd task-test-review pass)

- [x] Standing ¬mock/¬stub violation has no tracked exit: Slice 6 item 2
      *evaluated and deferred* the `with-redefs turn-runtime/execute-live-turn!`
      logic-boundary stub, and the recorded rationale confirms a **clean
      injectable seam exists** (stub provider via
      `psi.ai.core/create-context`'s per-ctx `:provider-registry`, emitting
      stream `:error` events carrying `:http-status` /
      `:provider-error/headers`). Deferring migration is defensible for task
      242's frozen scope, but no concrete follow-up captures the eventual
      removal of the violation — so it risks becoming a permanent unnoticed
      exception. Create (or reference) a dedicated follow-up task to migrate the
      retry-footer E2E harness onto the confirmed provider-registry seam,
      scoped to co-migrate **both** call sites that share the stub:
      `drive-provider-retry-through-progress-loop!` (used by
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`)
      **and** the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test`, which
      inlines its own identical `with-redefs`. This also targets the recorded
      parallel `with-redefs` test-isolation flakiness attributed to the same
      pattern.
      - Resolved: created dedicated follow-up task
        `munera/open/243-migrate-retry-footer-e2e-to-provider-seam/`
        (design-only), scoped to co-migrate both call sites onto the confirmed
        provider-registry seam and re-evaluate the parallel `with-redefs`
        flakiness.

## Slice 8 — Test-review follow-ups (3rd task-test-review pass)

- [x] Background sub-test lacks a "retry actually fired" positive control:
      `drive-provider-retry-through-progress-loop!` returns `@attempts*` (retry
      attempts driven), and the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test` asserts
      `(is (= 3 @attempts*))` to prove the full activate→change→clear retry
      sequence ran. The new
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
      discards that return value in **both** sub-tests. For the **background**
      sub-test this is a distinct vacuity risk from the drain dependency
      already documented (Slice 5 item 2): `(is (empty? footer-events))` passes
      both when "retry fired but was gated by `focus-allows?`" (intended) and
      when "retry never fired at all" (e.g. the no-op `:provider-retry-sleep-fn`
      or a mis-wired background config silently skips the retry loop) — the
      assertion cannot distinguish a working-and-gated pipeline from a
      dead/no-op one. Capture the returned attempt count and add a positive
      control `(is (= 3 attempts))` (matching the sibling) to both sub-tests so
      the empty-footer assertion is only credited when the retry sequence is
      proven to have executed. (Optional: also assert the focused sub-test drove
      all 3 attempts.)

## Slice 9 — Test-review follow-ups (test-shaper pass)

- [x] `await-retry-footer-text!` silently swallows its timeout, defeating
      meaningful-failure signal. It calls `support/await-until` (which returns
      `timeout-token`, not an exception, on the 500ms deadline) purely for the
      blocking side-effect and **discards the return value**. If the awaited
      retry footer never arrives within 500ms (e.g. CI load, GC pause), the
      sleep-fn returns anyway, the retry can clear before delivery, and the
      subsequent `(is (some … "retry in Ns") footer-events)` assertion then
      fails with a generic "not found" message that **cannot distinguish** a
      genuine focus-gate regression (the behaviour under test) from a mere
      timing timeout (a flake). This reopens exactly the race the
      `await-retry-footer-text!` pattern was introduced to close (Slice 2 /
      implementation.md test-construction pitfall). Make the timeout observable:
      have `await-retry-footer-text!` detect `support/timeout-token` and fail
      fast with a message that names the missing expected-text (e.g.
      `(is (not= support/timeout-token …) "retry footer sync timed out awaiting <text>")`),
      so a sync timeout surfaces as its own diagnosable failure rather than
      masquerading as a footer-gating regression. Task 243 explicitly *keeps*
      this sleep-fn pattern, so the fix belongs here (or must be explicitly
      forwarded to 243), not silently deferred.
- [x] The 500ms sync bound is an unnamed magic number duplicated across three
      call sites (`await-retry-footer-text!` and the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test`'s inline
      `support/await-until … 500`). Extract a single named constant (e.g.
      `retry-footer-sync-timeout-ms`) so the deterministic-sync bound has one
      authority and future tuning does not drift between the two harness copies.

## Slice 5 — Review follow-ups (task-implementation-review)

- [x] Tick the Slice-4 "Commit with a symbol-tagged message" checkbox — the
      implementation commit (`d8a32994b`) was made but the step is still
      unchecked (bookkeeping drift only; no functional impact).
- [x] Document the background-case test-net dependency in
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`:
      the background `(is (empty? footer-events))` assertion is only meaningful
      because `stop-progress-loop!` drains the progress queue synchronously
      before the assertion runs (verified: removing `focus-allows?` makes the
      background sub-test fail, so it is load-bearing today). Add a one-line
      comment noting this drain dependency so a future edit to the background
      sleep-fn / drain path does not silently make the assertion pass
      vacuously (the focused sub-test is already guarded by
      `await-retry-footer-text!`; the background sub-test has no such guard).
