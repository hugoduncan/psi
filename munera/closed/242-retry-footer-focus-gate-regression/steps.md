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

## Slice 12 — Test-review follow-ups (test-shaper pass, 4th)

- [x] Background sub-test cannot distinguish gate-suppression from
      footer-non-production — a distinct vacuity branch from the two already
      closed. The background sub-test's sole outcome signal is
      `(is (empty? footer-events))`. Its two existing guards close different
      branches: `(is (= 3 attempts))` (Slice 8) proves the retry *turns* fired,
      and the drain comment (Slice 5 item 2) protects the synchronous queue
      drain. Neither proves the retry pipeline *produced* `footer/updated`
      frames at all. `attempts` counts `turn-runtime/execute-live-turn!` calls,
      not `:retry-updated` progress events reaching `emit-footer-updated!`. So
      `(is (empty? footer-events))` still passes both when (A) footer frames were
      produced-then-gated by `focus-allows?` (the intended behaviour under test)
      and when (B) footer frames were *never produced* for the background config
      (e.g. a regression in `footer-refresh-progress-event?` matching
      `:retry-updated`, or in `emit-footer-updated!` / footer status-line
      construction). The one-time manual mutation check recorded in
      implementation.md ("with `focus-allows?` bypassed, the background
      sub-test fails with 4 leaked frames") confirmed gating is load-bearing
      *once by hand* but is **not encoded** as a standing assertion — so a future
      footer-production regression under the background config would let
      `(is (empty? footer-events))` pass green (a false PASS masquerading as
      "correctly gated"), the exact `meaningful_failures` failure mode the
      focused/sibling tests avoid by positively asserting production. Encode the
      distinguishing positive control: prove the background config *would* have
      produced ≥1 retry `footer/updated` frame absent the focus gate, so the
      `empty?` assertion is credited only against a live-and-producing pipeline.
      Options: (a) capture the same run's pre-gate frames (drive the background
      retry once with a raw `emit!`, assert it produces retry footers, then a
      second run through `make-request-emitter` with foreign focus asserts
      `empty?`); or (b) assert the pre-gate production count equals the focused
      sub-test's, since both drive the identical
      `drive-provider-retry-through-progress-loop!` scenario. If judged
      out-of-scope for task 242's frozen coverage, record the explicit rationale
      (rely on the one-time bypass check) rather than leaving the production-vs-
      gating ambiguity as an untracked residual.

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

## Slice 10 — Test-review follow-ups (test-shaper pass, 2nd)

- [x] Slice 9's observable-timeout fix is asymmetric: it hardened only
      `await-retry-footer-text!` (used by the focused sub-test of
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`),
      but the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test` retains
      the *identical* swallowed-timeout defect Slice 9 diagnosed. Its inline
      `:provider-retry-sleep-fn` calls
      `(support/await-until … retry-footer-sync-timeout-ms)` purely for the
      blocking side-effect and **discards the returned value** (the `let` body
      is the bare `await-until` call, whose `support/timeout-token` on timeout
      is never checked). So on the sibling's exact same race (sync deadline hit
      → retry clears before delivery), its downstream `(is (some? …))`
      assertions fail generically ("retry activation must publish
      footer/updated with retry text") — indistinguishable from a genuine
      pre-gate regression, the very ambiguity Slice 9 removed for the focused
      test. Apply the same observable-timeout guard to the sibling's sleep-fn
      (detect `support/timeout-token` and fail fast naming the missing text),
      ideally by routing the sibling through the now-hardened
      `await-retry-footer-text!` helper rather than its inline copy — this also
      collapses the remaining duplicated sleep-fn / `expected-text`
      (`(str "retry in " (quot (long delay-ms) 1000) "s")`) logic that
      `retry-footer-sync-timeout-ms` only partially unified. If instead deferred
      to task 243's harness migration, forward it explicitly there (243's design
      commits to "keep the deterministic `await-retry-footer-text!`
      synchronization pattern") rather than leaving the sibling's silent
      timeout as an untracked residual.

## Slice 11 — Test-review follow-ups (test-shaper pass, 3rd)

- [x] The sibling `rpc-prompt-provider-retry-state-publishes-footer-updated-test`
      still inlines a full copy of the retry-driving body that
      `drive-provider-retry-through-progress-loop!` already encapsulates —
      `error-turn`, `attempts*`, the `with-redefs turn-runtime/execute-live-turn!`
      429→429→recovery `case`, the `execute-prepared-request!` call, and the
      `streams/start-progress-loop!` / `stop-progress-loop!` lifecycle. Prior
      passes only unified the *sleep-fn* (Slice 10) and tracked the *stub
      mechanism* migration (task 243); the retry-driving **body** remains a
      second divergent definition of the identical retry scenario. Since
      `drive-provider-retry-through-progress-loop!` is already parameterized on
      `emit!` and returns `@attempts*`, the sibling could call it directly with
      its pre-gate raw `emit!` (`(fn [event data] (swap! emitted* conj {:event
      event :data data}))`) and then inspect `@emitted*` for its
      activation/changed/clear/session-id assertions — collapsing ~60 duplicated
      lines to one call and guaranteeing the two harnesses cannot drift in the
      429 headers, attempt sequence, or thread lifecycle. This is
      `economical`/`consistent` harness reuse available **today**, distinct from
      243's ¬mock/¬stub mechanism swap. If instead folded into 243's rewrite
      (which co-migrates both sites and will naturally consolidate them),
      forward it explicitly to 243's plan rather than leaving the divergent
      inline copy as an untracked residual — do not silently defer.

## Slice 14 — Test-review follow-ups (test-shaper pass, 6th)

- [x] The retry-**clear** assertion is a bare negative on `(last footer-events)`
      with no positive control that a clear footer was actually produced — a
      distinct vacuity branch from the activation/changed/attempts/background
      controls already closed (Slices 8/12). In both
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
      (focused sub-test, ~L300) and the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test` (~L423),
      the clear is verified only as
      `(is (not (str/includes? (get-in (last footer-events) [:data :status-line]) "retry in")))`.
      Unlike the activation (`some … "retry in 8s"`) and changed-metadata
      (`some … "retry in 4s"`) frames — which are *positively* asserted to have
      been produced — the clear frame has only this negative-on-`last`
      assertion. It therefore passes both when (A) the retry genuinely cleared
      and emitted an inactive-retry footer as the last frame (intended), and
      when (B) `clear-active-retry!` / the clear footer refresh never emitted a
      footer at all but some *other* non-retry `footer/updated` frame happened
      to land last (a clear-path regression). This is the same
      production-vs-gating `meaningful_failures` gap Slices 8/12 removed for the
      earlier frames, still open for the clear transition. Add a positive
      control that a retry→inactive **clear footer** was actually produced
      (e.g. assert the final retry-bearing footer is followed by a
      distinguishable clear footer, or that a footer emitted after the last
      `retry in` frame carries the post-retry status-line), so the
      no-stale-`retry in` assertion is credited only against a live clear-path
      emission rather than an incidentally-trailing unrelated footer. If judged
      out of scope for task 242's frozen coverage or better folded into task
      243's harness rewrite, record the explicit rationale and forward it to
      243 rather than leaving the clear-frame production ambiguity as an
      untracked residual.

## Slice 24 — Review follow-ups (task-implementation-review, 2nd)

- [ ] `await-retry-footer-text!`'s Slice-9 observable-timeout guard depends on an
      **undocumented thread-affinity invariant** that no prior slice names, and
      whose violation would silently re-open the exact swallowed-timeout blind
      spot Slice 9 closed. The helper calls `clojure.test/is` (its
      `(is (not= support/timeout-token result) …)` fail-fast) from *inside* a
      `:provider-retry-sleep-fn`. `is` reports via the thread-local
      `clojure.test/*report-counters*`, so its pass/fail is only counted when the
      sleep-fn runs on the **test thread**. It does today, but only incidentally:
      `drive-provider-retry-through-progress-loop!` calls
      `turn-runtime/execute-prepared-request!` *directly* (not on the daemon
      thread started by `streams/start-progress-loop!`), so the retry loop →
      `sleep-for-retry!` → sleep-fn → `is` all run on the test thread while the
      daemon thread only drains the progress queue. This thread split is
      load-bearing for the Slice-9 guard's correctness but is **nowhere
      documented**: a future edit that moves the retry loop onto the progress /
      daemon thread (or routes the sleep-fn through an executor) would make the
      timeout `is` fire on a non-test thread, where its counters are unbound — the
      timeout would then be *silently dropped* (no pass, no fail), regressing to
      the very swallowed-timeout masquerade Slice 9 removed, while the test still
      shows green. Encode the invariant so it cannot silently break: either (a)
      add a one-line comment at `await-retry-footer-text!` / the sleep-fn call
      sites noting the `is` assertion is only valid because the sleep-fn runs on
      the test thread (and that moving the retry loop off-thread must re-home the
      timeout failure to a thread-safe channel — e.g. deliver `timeout-token` to
      an atom the test thread asserts on after the drive), or (b) restructure the
      timeout signal to be thread-safe by construction (capture the timeout in a
      promise/atom and assert it on the test thread post-drive rather than via
      `is` inside the sleep-fn). If judged out of scope for task 242's frozen
      coverage or better folded into task 243's harness rewrite (which keeps the
      `await-retry-footer-text!` sync pattern per 243's design), record the
      explicit rationale and forward the thread-affinity invariant to 243 rather
      than leaving the undocumented `is`-on-test-thread dependency standing.

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

## Slice 13 — Test-review follow-ups (test-shaper pass, 5th)

- [x] Slice 10's dedup claim is inaccurate; the `expected-text` derivation
      remains triplicated. Slice 10 (steps.md L225-226) and its implementation.md
      note both state routing the sibling through `await-retry-footer-text!`
      "collapses the remaining duplicated sleep-fn / `expected-text`
      (`(str "retry in " (quot (long delay-ms) 1000) "s")`) logic". It did not:
      the helper takes `expected-text` as a *parameter*, so the identical
      delay→text derivation
      `(fn [delay-ms] (await-retry-footer-text! <captured> (str "retry in " (quot (long delay-ms) 1000) "s")))`
      is still hand-built at **three** `:provider-retry-sleep-fn` call sites
      (rpc_prompt_test.clj L258 focused, L313 background pre-gate control, L388
      sibling). Slice 10 unified only the await/timeout mechanism, not the
      expected-text-from-delay logic. This is a `consistent`/`economical`
      residual (incidental variation across three copies: a footer-format change
      to the `"retry in Ns"` string must be edited in three places) **and** a
      doc↔code coherence gap (a completed slice claims a dedup the code does not
      reflect). Fix: fold the delay→expected-text derivation into a single
      authority — either have `await-retry-footer-text!` accept `delay-ms` and
      derive the `"retry in Ns"` text internally (call sites pass only
      `captured` + `delay-ms`), or extract a named
      `retry-footer-sleep-fn`/`expected-retry-text` builder the three sites
      share — so the sleep-fn is constructed once. If judged out of scope for
      task 242's frozen coverage or better folded into task 243's harness
      rewrite, correct the overstated Slice-10 claim (steps.md + implementation.md)
      to say only the await/timeout was unified and forward the expected-text
      dedup explicitly to 243 rather than leaving the inaccurate
      "collapses ... `expected-text`" wording standing.

## Slice 15 — Test-review follow-ups (test-shaper pass, 7th)

- [x] Slice-12's pre-gate production control does not drive the *identical*
      config it claims, weakening the production-vs-gating guarantee it was
      added to provide. In
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`'s
      background sub-test, the Slice-12 comment says it drives "the *identical*
      background config through a pre-gate raw `emit!`" so the gated
      `(is (empty? footer-events))` is credited against a live-and-producing
      pipeline. But the two runs use **different** `:provider-retry-sleep-fn`s:
      the pre-gate control uses `(retry-footer-sleep-fn pre-gate-captured)` (the
      **blocking** sync helper), while the gated run uses
      `(fn [_delay-ms] nil)` (a **no-op** sleep). So the control proves a
      *blocking-sleep* config produces footers, not the *no-op-sleep* config the
      `empty?` assertion actually exercises. This is (a) a `meaningful_failures`
      gap — a footer-production regression that manifests only under the no-op
      sleep timing (e.g. a race that the blocking sync would have masked) would
      still let the gated `empty?` pass green while the pre-gate control passes,
      so the control does not fully credit the config under test; and (b) a
      doc↔code coherence gap — the "identical background config" wording is
      inaccurate. Fix: make the pre-gate control drive the *same* sleep-fn the
      gated run uses (either both no-op relying on the documented synchronous
      `stop-progress-loop!` drain, or both blocking), so the control vouches for
      the exact config the `empty?` assertion exercises; or, if the divergence
      is deliberate (the gated run intentionally omits the blocking sync because
      it relies on the drain), correct the "identical" wording to name the
      sleep-fn difference and record why it does not undermine the control. If
      judged out of scope for task 242's frozen coverage or better folded into
      task 243's harness rewrite, record the explicit rationale and forward it to
      243 rather than leaving the config-divergence / inaccurate-"identical"
      residual standing.

## Slice 16 — Test-review follow-ups (test-shaper pass, 8th)

- [x] The deterministic-sync helper derives the awaited text with a **different
      formula** than the production footer, so the sync coincides only for the
      exact values the test happens to use — a latent `deterministic`/`robust`
      coupling distinct from every prior expected-text slice (which only *dedup'd*
      the derivation, Slices 10/13, never questioned its correctness). Chain:
      `expected-retry-text` (rpc_prompt_test.clj) computes `(quot delay-ms 1000)`
      (integer floor) from the `delay-ms` the retry loop passes to
      `:provider-retry-sleep-fn` (`turn_runtime/core.clj` L621 →
      `sleep-for-retry!` receives `(:delay-ms retry-metadata)`). The *actual*
      footer text is built by
      `app_runtime/retry_display.clj`'s `format-relative-seconds` as
      `(Math/ceil (/ (- resume-at now-ms) 1000.0))` — `ceil`, not `floor`, and
      computed from `resume-at - now-ms` re-read **at delivery time** (the async
      progress loop reads live session data when it polls, not a snapshot). So
      the helper waits for `"retry in 8s"`/`"retry in 4s"` while the footer's
      seconds depend on how much wall-clock elapsed between `mark-active-retry!`
      and the 10ms-poll delivery: `resume-at ≈ now₀ + delay-ms`, and at delivery
      `now-ms > now₀`, so `ceil((resume-at - now-ms)/1000)` can be `N-1` (e.g.
      `ceil(7998/1000) = 8` today, but `ceil(7000/1000)`… a >1s poll/GC delay
      would yield `"retry in 7s"`). Today green only because `Retry-After` = 8/4
      are whole seconds and drift stays sub-second within the 500ms bound. The
      failure mode this creates is exactly the one Slice 9 set out to remove: if
      the footer emits `"retry in 7s"` while the helper awaits `"retry in 8s"`,
      the sync *times out on the wrong expected text* and now surfaces as the
      Slice-9 "retry footer sync timed out awaiting <text>" diagnostic — a
      **false timeout** naming a text production never intended to emit, masking
      a live-and-correct pipeline as a regression. Fix: derive the awaited text
      from the *same* authority the footer uses (call
      `retry-display/format-relative-seconds` / `retry-status-text`, or compute
      `ceil` from the retry metadata's `:resume-at`/`:delay-ms` the same way),
      so the helper waits for the text production will actually emit rather than
      a floor-approximation that coincides only for whole-second `Retry-After`
      values. Alternatively, if kept as-is for task 242's frozen coverage,
      record the explicit rationale (relies on whole-second `Retry-After` +
      sub-500ms drift) and forward the formula-coupling to task 243's harness
      rewrite rather than leaving the `quot`-vs-`ceil` divergence as an
      untracked latent-desync residual.

## Slice 17 — Review follow-ups (code-shaper pass)

- [x] Assertion-side retry-frame matchers hard-code the `"retry in 8s"` /
      `"retry in 4s"` + `"remaining 2/5000"` strings, duplicated across both
      harnesses' assertion sites and coupled by hand to the driving config — a
      `consistent`/`economical` residual distinct from every prior dedup slice
      (Slices 10/13/16 unified only the *sleep-fn/await/expected-text* on the
      *sync* side; the *assertion* matchers were never touched). Sites:
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
      focused sub-test (rpc_prompt_test.clj L323, L327-328) and the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test`
      (L473, L477-479) each re-inline `(str/includes? … "retry in 8s")` and the
      `retry in 4s` + `remaining 2/5000` pair. The `8s`/`4s` literals are the
      *delivered-footer* form of the shared helper's `8000`/`4000`
      `:auto-retry-base-delay-ms` sequence, but are re-derived as raw strings on
      the assertion side rather than via the existing single-authority
      `expected-retry-text` (which Slice 16 aligned to production's `ceil`). So
      a footer-format change (`"retry in Ns"` string) or a delay change in
      `drive-provider-retry-through-progress-loop!`'s 429 `Retry-After` headers
      must be edited in ≥3 assertion places and can silently drift from the
      driving config — the assertion could still match the *old* string after
      the driver changes, passing green against a stale expectation. Fold the
      activation/changed matchers onto the shared authority: derive the awaited
      activation/changed text from `expected-retry-text` (the driver's
      first-attempt 8000ms / second-attempt 4000ms delays) plus a named
      `remaining`-fragment builder, or extract `activation-retry-footer?` /
      `changed-retry-footer?` predicates (alongside the existing
      `retry-status-line?`) both harnesses share, so the retry-frame recognition
      logic has one authority and cannot drift from the config that produces it.
      If judged out of scope for task 242's frozen coverage or better folded
      into task 243's harness rewrite, record the explicit rationale and forward
      it to 243 rather than leaving the triplicated assertion-matcher / config-
      coupling residual standing.
- [x] The `(or (get-in frame [:data :status-line]) "")` status-line accessor
      idiom is hand-repeated at ~8 sites across the two retry-footer tests and
      the helpers (`retry-status-line?` L193, focused-test L323/L326/L336,
      background pre-gate L400, sibling L473/L477-478/L484). This is a
      `consistent`/`locally_comprehensible` residual: the same nil-safe
      status-line extraction is re-spelled everywhere instead of a single named
      accessor (e.g. `frame-status-line` returning `""` on absence), so a change
      to the frame shape (`[:data :status-line]` path) must be edited at every
      site. Extract one accessor helper and route the matchers/predicates
      through it. If judged out of scope for task 242's frozen coverage or
      better folded into task 243's harness rewrite, record the rationale and
      forward it to 243.

## Slice 18 — Review follow-ups (code-shaper pass, 2nd)

- [x] Slice 17's `activation-retry-delay-ms`/`changed-retry-delay-ms` constants
      do **not** achieve their own stated goal: their docstrings claim each is
      the "single authority so the matcher tracks the driving config", but the
      *actual* driving config lives in `drive-provider-retry-through-progress-loop!`'s
      429 `error-turn` `Retry-After` header literals (`"8"` at
      rpc_prompt_test.clj L308, `"4"` at L311) — which are wholly independent
      string literals, not derived from (nor deriving) the `8000`/`4000` ms
      matcher constants (L201/L207). So the two remain **two authorities** for
      the same "8s"/"4s" retry delay: change the driver header `Retry-After "8"`
      → `"6"` and the matcher still awaits `expected-retry-text 8000` =
      `"retry in 8s"`, so `(some activation-retry-footer? footer-events)` matches
      nothing and fails as a confusing "not found" rather than a coupling error —
      exactly the "assertion could still match the *old* string after the driver
      changes, passing green against a stale expectation" drift Slice 17 set out
      to remove (steps.md L424-426). The same disconnect holds for the
      rate-limit fragment: `remaining-fragment 2 5000` (L228) hard-codes `2`/`5000`
      re-derived by hand from the driver's second-429 `RateLimit-Remaining "2"` /
      `RateLimit-Limit "5000"` headers (L309-310), a third independent copy of
      those values. This is a `consistent`/`robust` residual distinct from Slice
      17 (which unified the *assertion-side* matcher against the *sync-side*
      `expected-retry-text`, but left the **driver-header ↔ matcher-constant**
      coupling standing). Fold the driver `Retry-After`/`RateLimit-*` header
      values and the matcher delay/remaining constants onto one authority (e.g.
      derive the `error-turn` headers *from* `activation-retry-delay-ms`/`changed-retry-delay-ms`
      and the rate-limit constants, so the driver and the matchers cannot drift),
      or correct the Slice-17 docstrings/notes to say the constants only track
      the config *by convention* (a hand-maintained coupling), not as a single
      authority. If judged out of scope for task 242's frozen coverage or better
      folded into task 243's harness rewrite, record the explicit rationale and
      forward it to 243 rather than leaving the driver-vs-matcher config-coupling
      (and the inaccurate "single authority ... tracks the driving config"
      wording) standing.
- [x] The retry-driving session config `{:persist? false :config
      {:auto-retry-base-delay-ms 8000 :auto-retry-max-retries 2}}` passed to
      `support/create-session-context` is duplicated verbatim at four sites
      (rpc_prompt_test.clj L350-353 focused, L434-437 background pre-gate control,
      L451-454 background gated, L497-500 sibling). The `8000`
      `:auto-retry-base-delay-ms` is a *fourth* independent copy of the same
      "8s first-attempt delay" the driver `Retry-After "8"` header and the
      `activation-retry-delay-ms 8000` matcher constant already encode (see the
      item above), so it participates in the same drift risk *and* is repeated
      config boilerplate. This is a `consistent`/`economical` residual not
      touched by any prior slice (which addressed sleep-fn, expected-text,
      retry-driving body, and assertion matchers — never the shared
      session-context construction). Extract a single named builder (e.g.
      `retry-footer-session-context!` / a shared config constant) so the retry
      test session config has one authority. If judged out of scope for task
      242's frozen coverage or better folded into task 243's harness rewrite,
      record the explicit rationale and forward it to 243 rather than leaving the
      four-way config duplication standing.

## Slice 19 — Review follow-ups (code-shaper pass, 3rd)

- [x] The active-retry recognition literal `"retry in"` is not fully routed
      through its one authority `retry-status-line?`, leaving a
      `consistent`/`economical` residual distinct from every prior matcher slice
      (Slice 17 extracted `retry-status-line?`/`activation-retry-footer?`/`changed-retry-footer?`
      but only routed the *positive* activation/changed matchers onto shared
      authorities; the *negated* clear assertions were never touched). The two
      clear-footer negatives —
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
      focused sub-test (rpc_prompt_test.clj L426) and the sibling
      `rpc-prompt-provider-retry-state-publishes-footer-updated-test` (L564) —
      each re-spell the negation longhand as
      `(not (str/includes? (frame-status-line clear-footer) "retry in"))` rather
      than `(not (retry-status-line? clear-footer))`, so the raw `"retry in"`
      substring is a third and fourth independent copy of the literal the
      `retry-status-line?` predicate (L286) already owns (and which
      `expected-retry-text`'s `"retry in "` prefix also embeds). A footer-format
      change to the retry-text prefix must therefore be edited at ≥3 places, and
      the clear negatives can silently drift from the predicate that defines what
      "active retry text" means. Route both clear-footer negatives through
      `(not (retry-status-line? clear-footer))` so the active-retry recognition
      literal has one authority; optionally fold the `"retry in "` prefix in
      `expected-retry-text`/`retry-status-line?` onto a single shared prefix
      constant so the positive-match and substring-check spellings cannot
      diverge. If judged out of scope for task 242's frozen coverage or better
      folded into task 243's harness rewrite, record the explicit rationale and
      forward it to 243 rather than leaving the duplicated `"retry in"` literal /
      unrouted clear negation standing.

## Slice 20 — Review follow-ups (code-shaper pass, 4th)

- [x] The focus-gated emitter construction sequence is duplicated verbatim
      across the two focus-gated sub-tests of
      `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`,
      a `consistent`/`economical` residual distinct from every prior dedup slice
      (Slice 18 extracted only the *session-config* builder
      `retry-footer-session-context!`; the *emitter wiring* around it was never
      touched, and the pre-gate raw-`emit!` control uses a different capture
      strategy entirely). The focused sub-test (rpc_prompt_test.clj L398-406)
      and the background gated sub-test (L492-500) each re-spell the identical
      6-line sequence — `captured` atom, `emit-frame!` closure,
      `rpc.state/make-rpc-state`, `subscribe-topics! … rpc.events/event-topics`,
      `set-focus-session-id!`, `rpc.emit/make-request-emitter … "req-1"` —
      differing only in *which* session-id is set as focus (the retrying session
      vs `other-session-id`). So a change to the focus-gated emitter wiring (the
      topic set, the request-id, the emitter constructor, or the rpc-state
      shape) must be edited at both sites and can silently drift between the two
      sub-tests that are meant to exercise the *same* gate under opposite focus.
      Extract a single named builder (e.g.
      `focus-gated-emitter!` returning `[emit! captured]` given the
      focus-session-id) that both sub-tests share, so the focus-gated emit
      boundary has one construction authority and the focused-vs-background pair
      cannot diverge in anything but the focus session-id under test. If judged
      out of scope for task 242's frozen coverage or better folded into task
      243's harness rewrite, record the explicit rationale and forward it to 243
      rather than leaving the duplicated emitter-construction sequence standing.
- [x] The `footer/updated` frame-filter idiom
      `(filterv #(= "footer/updated" (:event %)) …)` is hand-repeated at four
      retry-footer sites (rpc_prompt_test.clj L415 focused, L487 background
      pre-gate control, L521 background gated, L554 sibling) — plus the
      unrelated L116 in another test — each re-spelling the same
      `"footer/updated"` event-topic literal + filter with no shared authority.
      This is a `consistent`/`economical` residual parallel to Slice 17's
      `frame-status-line` accessor extraction (which unified the *status-line*
      access but not the *frame selection*): the same footer-event topic string
      and filter shape are re-derived at every site, so a change to the event
      name or the frame `:event` path must be edited at ≥4 places, and a typo in
      one copy (e.g. `"footer/update"`) would silently filter to `[]` and pass
      the downstream `empty?`/`seq` assertions vacuously. Extract one named
      selector (e.g. `footer-updated-frames` over a captured-frames coll) the
      retry-footer sites share so the footer-frame selection has one authority.
      If judged out of scope for task 242's frozen coverage or better folded
      into task 243's harness rewrite, record the explicit rationale and forward
      it to 243 rather than leaving the duplicated footer-frame-filter idiom
      standing.

## Slice 21 — Review follow-ups (code-shaper pass, 5th)

- [x] The `remaining R/L` matcher fragment is hand-spelled independently of the
      production authority that builds it, a `consistent`/`robust` residual
      distinct from every prior matcher slice (Slice 16 aligned only the *delay*
      text to production's `retry-display/format-relative-seconds`; Slice 18
      unified only the driver-vs-matcher *rate-limit values* `2`/`5000`, not the
      fragment *format string*). The production footer status-line is built by
      `psi.app-runtime.retry-display/retry-status-text`, which composes the
      remaining fragment as `(str "remaining " remaining)` over
      `remaining-text`'s `(str remaining "/" limit)` — i.e. the `"remaining "`
      prefix and the `"/"` separator both live in `retry_display.clj`. The test's
      `remaining-fragment` (rpc_prompt_test.clj) re-derives that exact fragment
      as `(str "remaining " remaining "/" limit)` — a second, independent copy of
      the production format string. So a footer-format change in
      `retry-status-text`/`remaining-text` (e.g. `"remaining "` → `"rem "`, or
      `"/"` → `" of "`, or reordering to `remaining:R/L`) silently drifts the
      `changed-retry-footer?` matcher: it either stops matching (`some
      changed-retry-footer?` → "not found", surfacing as a confusing regression
      rather than a coupling error) or continues matching a stale form the
      footer no longer emits — exactly the drift class Slice 16 removed for the
      `"retry in Ns"` delay text but left open for the remaining fragment.
      Derive the matcher's remaining fragment from the same authority the delay
      text now uses: build the expected changed-frame text from
      `retry-display/retry-status-text` (or `retry-display`'s `remaining-text`)
      given the retry metadata, so the matcher tracks the production fragment
      format automatically — the delay text (Slice 16) and the remaining
      fragment then share one authority instead of one aligned + one hand-rolled.
      If judged out of scope for task 242's frozen coverage or better folded into
      task 243's harness rewrite, record the explicit rationale and forward it to
      243 rather than leaving the `remaining`-fragment format-string copy (a
      `retry_display.clj`↔matcher coupling the delay text no longer has) standing.

## Slice 22 — Review follow-ups (code-shaper pass, 6th)

- [x] The active-retry `"retry in "` **prefix** literal is still a hand-copied
      second authority in the test, un-routed to the production authority — a
      `consistent`/`robust` residual distinct from every prior prefix slice.
      Slice 19 unified the prefix only *within* the test (`active-retry-text-prefix`
      shared between the positive `expected-retry-text` builder and the
      `retry-status-line?`/clear negations), and Slice 16 (seconds via
      `format-relative-seconds`) + Slice 21 (remaining fragment via
      `retry-status-text`) folded the *variable* parts of the status-line onto
      the production authority `psi.app-runtime.retry-display`. But the fixed
      `"retry in "` prefix itself is spelled *twice*: once in production at
      `retry_display.clj` L38 (`(str "retry in " delay-text)` inside
      `retry-status-text`) and once in the test at rpc_prompt_test.clj L185
      (`active-retry-text-prefix "retry in "`). These are independent copies —
      the test's `active-retry-text-prefix` is not derived from `retry-status-text`
      nor vice versa. So a footer-format change to the prefix in
      `retry_display.clj` (e.g. `"retry in "` → `"retrying in "`, or dropping the
      trailing space) silently desyncs *every* test matcher and predicate that
      routes through `active-retry-text-prefix` (`expected-retry-text`,
      `activation-retry-footer?`, `changed-retry-footer?`, `retry-status-line?`,
      and both clear negations) while production still emits correctly — exactly
      the drift class Slices 16/21 removed for the seconds and remaining fragment
      but left open for the prefix. The failure surfaces as a confusing
      `some activation-retry-footer? → nil` "not found" or a Slice-9
      "retry footer sync timed out awaiting <text>" false timeout naming a text
      production never emits, masking a live-and-correct pipeline as a
      regression. Note that `expected-retry-text` already imports
      `retry-display` and reads `format-relative-seconds` from it — so the
      cleanest fix derives the *whole* active-retry text (prefix + seconds) from
      `retry-display/retry-status-text` (build `"retry in Ns"` from constructed
      retry metadata `{:active? true :resume-at delay-ms}` and take the leading
      `" · "`-split fragment, mirroring `remaining-fragment`'s Slice-21 pattern),
      collapsing the last hand-copied production-format literal onto the single
      authority. If judged out of scope for task 242's frozen coverage or better
      folded into task 243's harness rewrite, record the explicit rationale and
      forward it to 243 rather than leaving the duplicated `"retry in "` prefix
      (a `retry_display.clj`↔matcher coupling the seconds and remaining fragment
      no longer have) standing.

## Slice 23 — Review follow-ups (task-implementation-review)

- [x] Aggregate test-helper apparatus is disproportionate to the frozen,
      test-only, no-code-change scope — an `unnecessary_abstraction`/`simplicity`
      residual the individual code-shaper/test-shaper slices (17–22) each
      justified locally but never assessed in aggregate. The retry-footer E2E
      harness now carries ~15 extracted single-authority helpers/constants
      (`frame-status-line`, `footer-updated-frames`, `focus-gated-emitter!`,
      `retry-footer-session-context!`, `expected-retry-text`, `remaining-fragment`,
      `active-retry-text-prefix`, `activation-retry-delay-ms`,
      `changed-retry-delay-ms`, `retry-rate-limit`, `changed-retry-remaining`,
      `retry-after-seconds`, `retry-footer-sleep-fn`, `retry-status-line?`,
      `clear-footer-produced-after-retry`, `drive-provider-retry-through-progress-loop!`)
      for a *single* test pair. `active-retry-text-prefix` is the sharpest case:
      it derives the fixed `"retry in "` prefix by computing
      `retry-status-text {:active? true :resume-at 0}` at `now-ms 0`, then
      *string-length-subtracting* `(format-relative-seconds 0)` from the tail
      (`(subs status-line 0 (- (count status-line) (count seconds)))`) — an
      indirect, fragile derivation (breaks silently if production ever emits a
      trailing/leading space or reorders the fragment) that is far more complex
      than the one-line literal it replaced, to remove a drift risk on a
      *test-only* frozen harness that will (per task 243) be rewritten anyway.
      Assess whether the later dedup slices (esp. Slice 22's prefix derivation
      and the driver-header↔matcher constant folding) are net-positive for this
      frozen harness or are incidental complexity added chasing convergence; if
      the harness is being migrated by 243, prefer forwarding the format-coupling
      concerns to 243's rewrite over encoding brittle derivations in a
      soon-to-be-replaced harness. Record the explicit judgement (keep vs revert
      vs forward-to-243) rather than leaving the aggregate over-abstraction
      unassessed.

- [x] Lifecycle-ordering anomaly: design-review and plan-review passes ran on
      the task *after* it was already closed. The close commit is `58a16fd53`
      (`⊘ 242: close`, git-mv open/ → closed/), but four design-review commits
      (`4f30a1a1e`, `1c9caf4d3`, `516cb062e`, `0a616983e`) and two plan-review
      commits (`2ee8ba795`, `d48c15c9b`) landed *after* it, running the
      design/plan review turns against an already-closed, already-implemented
      task. All produced "no new feedback" so there is no functional impact, but
      running design→plan review *after* implementation+close inverts the
      intended lifecycle order (design/plan review precede implementation) and
      the reviews are largely vacuous against a frozen closed task. Confirm this
      was intentional catch-up bookkeeping (not a lifecycle-driver bug that will
      recur on other tasks); if a driver/workflow re-ran earlier lifecycle
      phases on a closed task, that ordering should be prevented rather than
      just tolerated because the passes happened to be no-ops here.
