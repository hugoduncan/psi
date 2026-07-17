# Implementation notes

- architectural review: no architectural-fit feedback — design conforms to the
  RPC focus-gating delivery rule (doc/architecture.md), app-runtime/RPC footer
  ownership boundary, and task-241 no-cross-session-leakage invariant. The
  push-based direct emit (`emit-footer-updated!` via progress-queue) vs the
  `:projection/ui-changed` recompute-at-delivery convergence target is a
  pre-existing pattern; re-architecting it would widen the frozen scope, so it
  was not filed as an actionable misfit for this design.
- ambiguity review added 1 new design step — AC1's unconditional
  "failing-then-passing" focused-session test conflicts with the design's own
  "focused case may be working as intended" branch (a passing-immediately test
  cannot be failing-then-passing).
- inconsistency review: no new inconsistency feedback. Verified the design's
  quoted `focus-allows?` matches actual `components/rpc/src/psi/rpc/events.clj`
  (semantically identical; real code binds `effective-focus` in a `let`). The
  AC1-vs-working-as-intended tension is already captured by the ambiguity pass,
  not re-filed here.

## AC1 ambiguity resolved (design-follow-up)

- AC1 rewritten to make failing-then-passing **contingent** on Approach step 1's
  diagnosis: mandatory only if the focused session is the actual regression;
  otherwise the same test stands as a green regression-lock characterization test
  plus the recorded "working as intended" determination. Scope unchanged (no
  cross-session leakage; task-241 invariant preserved). The prior design-step
  notes below drove this resolution.

## Notes for the design-steps task (AC1 ambiguity resolution)

- Principle: do not resolve by changing the frozen scope. The design-step asks
  the human/author to clarify AC1's intent, not to widen/narrow scope. Preserve
  the task-241 focus-gating invariant (no cross-session leakage) in any answer.
- Diagnosis-first is load-bearing: the failing-then-passing requirement only
  makes sense on the branch where the *focused* session is actually broken.
  Resolve AC1 to be contingent on that branch, or specify the proof AC1 demands
  in the "working as intended (background-only)" outcome (likely a green
  characterization/regression-lock test + recorded determination, not
  failing-then-passing).
- Relevant project files verified during review:
  - `components/rpc/src/psi/rpc/events.clj` — `focus-allows?` (session-scoped
    focus gate) + `emit-event!` (ANDs topic-subscribed? with focus-allows?).
  - `components/rpc/test/psi/rpc_events_test.clj` — existing focus-gate
    characterization tests (pattern to follow for the E2E retry→footer test).
  - `munera/closed/241-emit-only-focused-session-events/` — origin of the focus
    gate; its steps.md/implementation.md explain the structural-rule rationale.
  - Pipeline halves cited in design.md Context: `turn-runtime/core.clj`
    (`mark-active-retry!`), `rpc/session/streams.clj`
    (`footer-refresh-progress-event?`), `app-runtime/footer.clj` + retry_display,
    `emit.clj` (stamps `:session-id`), Emacs `psi-events.el` "footer/updated".

## plan-review session — outcome

- plan-review inconsistency pass: no inconsistency review feedback. Verified
  design.md ↔ plan.md ↔ steps.md agree on AC1 contingency, test location/boundary
  (`emit-frame!`), retry-text matcher (`retry in`), gate-preserving fix
  constraint, pipeline function references, and 4-slice order. No new design-steps.
- plan-review ambiguity pass: no new ambiguity review feedback. plan.md/steps.md
  are diagnosis-first and coherent with the resolved (contingent) AC1; the
  fix-location and reproduction/flakiness uncertainties are already captured as
  bounded Risks in plan.md, and "retry backoff text" is pinned to a `:status-line`
  matching `retry in`. No new design-steps filed.

## design-review session (arch + ambiguity + inconsistency) — outcome

- Re-ran all three design-review passes: no new design-steps filed. The only
  design-step (AC1 ambiguity) is already resolved in design.md, so no open
  actionable design-steps remain for a follow-up task to address.
- Principle for any AC1-adjacent work: keep failing-then-passing **contingent**
  on Approach step 1's focused-vs-background diagnosis; do not make it
  unconditional, and never resolve by widening scope or reintroducing
  cross-session event leakage (task-241 invariant).
- Verified during inconsistency pass: `footer/updated` carries `:status-line`
  via `emit-footer-updated!` (components/rpc/src/psi/rpc/session/emit.clj:18);
  `required-event-payload-keys` `#{:path-line :stats-line}`
  (components/rpc/src/psi/rpc/events.clj:71) is a required-keys set, not an
  exhaustive allow-list — `:status-line` presence is expected, not a mismatch.
- Retry footer status-line is built in
  components/app-runtime/src/psi/app_runtime/footer.clj (~L279/L317) from
  `:psi.agent-session/retry` only when `(:active? retry)`.

## Slice 1–4 outcome — diagnosis: focused session is NOT broken (working as intended)

- Gap identified: `components/rpc/test/psi/rpc_prompt_test.clj` already had
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test`, but it
  captures at the **pre-gate** `emit!` (a plain fn passed straight into
  `streams/start-progress-loop!`), never touching `rpc.events/emit-event!` /
  `focus-allows?`. So the actual RPC focus-gate boundary the design's AC1
  targets had zero coverage — that was the real gap, not a code regression.
- Added `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  in the same namespace. It builds real `psi.rpc.state` connection state
  (`make-rpc-state` + `subscribe-topics!` + `set-focus-session-id!`), wires
  `emit!` via `psi.rpc.session.emit/make-request-emitter` (the real emitter
  used in production, which routes through `rpc.events/emit-event!` →
  `focus-allows?`), then drives the same provider-boundary retry sequence
  (429 → activation → changed metadata → recovery/clear) through
  `turn-runtime/execute-prepared-request!` + the real
  `streams/start-progress-loop!`.
  - **Focused case** (focus-session-id = the retrying session): asserts
    `footer/updated` frames reach the captured `emit-frame!` output and one
    contains `retry in 8s` in `:status-line`. **Result: passes** — the focus
    gate does not suppress the focused session's retry footer.
  - **Background case** (focus-session-id = a different session, retry
    happens in a non-focused session): asserts no `footer/updated` frames
    reach `emit-frame!` at all. **Result: passes** — this is the intended
    task-241 no-cross-session-leakage behaviour, not a bug.
- **Diagnosis: background-only / working as intended.** The focused-session
  retry→footer path was never actually broken by task 241's focus gate. AC1's
  contingent branch applies: no failing-then-passing sequence was required or
  produced; the new test stands as a green regression-lock characterization
  test for the focused case, plus this recorded determination.
- **Test-construction pitfall discovered (not a product bug):** an initial
  draft of the focused-case test used a no-op `:provider-retry-sleep-fn`
  (`(fn [_] nil)`). That raced the async `streams/start-progress-loop!`
  (10ms poll interval) against `turn-runtime.core`'s
  `mark-active-retry!` → `clear-active-retry!` sequence — with no
  synchronizing sleep, the retry state could be written *and cleared* before
  the progress loop ever polled and delivered the footer frame, since
  `footer-updated-payload` reads **live** session data at delivery time, not
  a snapshot from the triggering event. This produced a false failure
  (`status-line` observed `nil` on every captured frame) that looked like the
  suspected regression but was purely a test race. Fixed by making
  `:provider-retry-sleep-fn` block (bounded, 500ms, matching the existing
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test` pattern)
  until the expected retry text has actually been captured by `emit-frame!`,
  keeping retry state live until the async loop catches up. Anyone adding
  similar retry/footer E2E tests should reuse this synchronization pattern
  (`await-retry-footer-text!`) rather than a no-op sleep.
- **Possible follow-up (not implemented, out of scope):** background/delegated
  session retry state is currently invisible to the focused connection by
  design. If that's ever desired, a session-activity-line style surface (see
  `footer/session-activity`) would be the natural place — a *different*
  aggregate signal, not routing the raw session-scoped `footer/updated` event
  around the focus gate.
- **Verification:** `bb test --focus psi.rpc-prompt-test` (6/6 pass, incl. new
  test) and `bb test --focus psi.rpc-events-test` (20/20 pass, task-241
  focus-gate invariants untouched) are green. Full `bb test` run: baseline
  (stashed, pre-change) shows 2450 passed/24 failed/38 errored from
  pre-existing parallel `with-redefs` test-isolation flakiness (same failing
  test names with and without this change); with this change: 2451
  passed/24 failed/38 errored — the +1 pass is the new test, no new
  failures/errors introduced.
- No code fix was needed; only test coverage was added. No CHANGELOG entry
  (no user-visible behaviour change).

## implementation-review session — outcome

- Verified the new test is genuinely load-bearing: with `focus-allows?`
  bypassed, the background `(is (empty? footer-events))` sub-test fails (4 leaked
  frames) — so the focus-gate regression lock is real, not vacuous.
- added 2 steps (Slice 5): tick the deferred Slice-4 commit checkbox, and
  document the background sub-test's synchronous-drain dependency (unguarded,
  unlike the focused sub-test's `await-retry-footer-text!`).

## Slice 5 — review follow-ups addressed

- addressed 2 implementation-review follow-up steps: ticked the deferred
  Slice-4 commit checkbox (commit `d8a32994b`), and added a one-line comment in
  `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  documenting that the background sub-test's `(is (empty? footer-events))` is
  non-vacuous only because `drive-provider-retry-through-progress-loop!` drains
  the progress queue synchronously via `stop-progress-loop!` before the
  assertion (unlike the guarded focused sub-test). Test still green (6/6).

## implementation-review session (2nd pass) — outcome

- Re-reviewed against design/plan/acceptance criteria; verified E2E test crosses
  the real `emit-event!`/`focus-allows?` boundary via `make-request-emitter`,
  and confirmed the background `(empty? footer-events)` assertion is non-vacuous
  (retry `:retry-updated` events are always queued, drained by
  `stop-progress-loop!`, and dropped only at the focus gate). Tests green
  (rpc-prompt 6/6, rpc-events 20/20), lint clean. No new follow-up steps added.

## task-test-review session — outcome

- added 2 steps (Slice 6): focused sub-test only locks the retry *activation*
  footer through the gate (sibling pre-gate test verifies activation + changed
  + clear); and the `with-redefs` stub of `execute-live-turn!` is a logic
  boundary rather than a nullable/injectable seam (linked to the recorded
  parallel `with-redefs` flakiness).

## Slice 6 — test-review follow-ups addressed

- addressed 2 test-review follow-up steps.
- **Gate-coverage asymmetry (item 1):** extended the focused sub-test in
  `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  to assert all three retry frames cross the RPC focus gate, matching the
  sibling pre-gate test's coverage: activation (`retry in 8s`), changed metadata
  (`retry in 4s` + `remaining 2/5000`), and clear (last footer has no stale
  `retry in` text). Previously only the activation frame was asserted through
  the gate, so a per-frame regression gating only the later frames would have
  gone undetected. The existing `await-retry-footer-text!` sleep-fn already
  synchronizes per-attempt (keyed by each delay), so the changed-metadata frame
  is delivered before its `clear-active-retry!`; the new assertions pass without
  further sync changes. Test green (rpc-prompt 6/6, 50 assertions).
- **Provider seam evaluation (item 2):** a clean injectable/nullable seam **does
  exist** — `psi.ai.core/create-context` seeds a per-ctx `:provider-registry`,
  and a stub provider that emits stream `:error` events carrying `:http-status`
  and `:provider-error/headers` would drive the same retry path, because
  `make-provider-event-consumer`'s `:error` case propagates those keys through
  the statechart to the assistant-message (verified in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`). So the earlier
  "logic boundary, not a nullable" framing was imprecise: the seam is available,
  not absent.
- **Decision: deferred as a bounded, intentional exception (left as-is).**
  Migrating off `with-redefs` would also require rewriting the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test` and standing up
  a stub provider matching the provider protocol — a larger refactor that
  exceeds task 242's frozen behaviour-preserving test-coverage scope (design.md
  Constraints/Approach are scoped to *adding* E2E footer coverage, not
  restructuring the retry-turn test harness). Recorded the explicit rationale in
  a code comment above the `with-redefs` in
  `drive-provider-retry-through-progress-loop!` so future readers know the seam
  exists and the `with-redefs` is a deliberate, scoped deferral — a natural
  follow-up task (migrate both retry-turn E2E tests to a stub provider seam) if
  the parallel `with-redefs` flakiness is worth eliminating.

## task-test-review session (2nd pass) — outcome

- added 1 step (Slice 7): the confirmed ¬mock/¬stub violation (`with-redefs
  execute-live-turn!`, shared by both retry-footer E2E tests) was evaluated and
  deferred in Slice 6 but has no tracked exit — recommend a dedicated follow-up
  task to migrate both call sites onto the confirmed provider-registry seam.

## Slice 7 — 2nd test-review follow-up addressed

- addressed 1 test-review follow-up step: created dedicated follow-up task
  `munera/open/243-migrate-retry-footer-e2e-to-provider-seam/` (design-only) as
  the tracked exit for the deferred ¬mock/¬stub violation, scoped to co-migrate
  both retry-footer E2E call sites (`drive-provider-retry-through-progress-loop!`
  and the sibling `rpc-prompt-provider-retry-state-publishes-footer-updated-test`)
  onto the confirmed per-ctx `:provider-registry` seam. No code/test change in
  task 242 (the migration itself is task 243's scope).

## task-test-review session (3rd pass) — outcome

- added 1 step (Slice 8): background sub-test discards
  `drive-provider-retry-through-progress-loop!`'s `@attempts*` return, so
  `(is (empty? footer-events))` cannot distinguish "retry fired then gated"
  from "retry never fired" — a positive control `(is (= 3 attempts))` (as in
  the sibling test) is missing. Distinct from the already-documented drain
  dependency.

## Slice 8 — 3rd test-review follow-up addressed

- addressed 1 test-review follow-up step: captured
  `drive-provider-retry-through-progress-loop!`'s returned attempt count in both
  sub-tests of
  `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  and added the positive control `(is (= 3 attempts))` (matching the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test`). The
  background `(is (empty? footer-events))` is now only credited when the full
  activate→change→clear retry sequence is proven to have executed, so it can no
  longer pass vacuously if the retry never fires (no-op sleep-fn / mis-wired
  config). Added the same control to the focused sub-test (the optional part).
  Test green (rpc-prompt 6/6, 52 assertions, +2 from the new controls), lint
  clean.

## task-test-review session (test-shaper pass) — outcome

- added 2 steps (Slice 9): `await-retry-footer-text!` discards `await-until`'s
  `timeout-token`, so a sync timeout fails as a generic "footer not found"
  (indistinguishable from a real focus-gate regression) rather than a
  diagnosable timeout — reopening the very race the pattern was added to close;
  and the 500ms sync bound is an unnamed magic number duplicated across three
  call sites. (Harness/`with-redefs` duplication is already tracked by task 243;
  not re-filed.)

## task-test-review session (4th pass) — outcome

- no new follow-up steps. Re-verified the E2E footer tests independently:
  pipeline routes `emit-footer-updated!` → `make-request-emitter` →
  `rpc.events/emit-event!` → `focus-allows?` (real gate), `:status-line`
  sourced from `footer/lines`; per-attempt `await-retry-footer-text!` sync makes
  the changed-metadata (`retry in 4s`) frame reliably captured before its
  clear; positive `(is (= 3 attempts))` controls guard both sub-tests; a
  missing-required-keys regression would surface as an `error` frame and fail
  the `retry in …` text assertions (so absence-of-error coverage is effectively
  present). The `focus-allows?` default-session-id fallback arm is covered at
  the unit level by `rpc-events-test/emit-event-nil-focus-uses-default-session-id-test`,
  so it is not a gap here. Sole infra-dep exception (`with-redefs`
  `execute-live-turn!`) remains documented with tracked exit (task 243).
  Tests green (rpc-prompt 6/6, 52 assertions).

## task-test-review session (test-shaper pass, 2nd) — outcome

- added 1 step (Slice 10): Slice 9 hardened only `await-retry-footer-text!`; the
  sibling `rpc-prompt-provider-retry-state-publishes-footer-updated-test`
  retains the identical swallowed-timeout defect (inline sleep-fn discards
  `support/await-until`'s `timeout-token`), so its sync-timeout still
  masquerades as a pre-gate regression.

## Slice 9 — test-shaper follow-ups addressed

- addressed 2 test-shaper follow-up steps.
- **Observable await timeout (item 1):** `await-retry-footer-text!` now captures
  `support/await-until`'s return and asserts `(is (not= support/timeout-token
  result) …)` naming the missing expected status-line text. A sync timeout now
  fails as its own diagnosable "retry footer sync timed out awaiting <text>"
  assertion instead of silently returning and letting the later `retry in Ns`
  text assertion fail generically (indistinguishable from a real focus-gate
  regression). Fix kept here per the step (task 243 explicitly keeps this
  sleep-fn pattern). The per-attempt call adds 2 assertions (52 → 54).
- **Named sync-bound constant (item 2):** extracted `retry-footer-sync-timeout-ms`
  (500) as the single authority for the deterministic retry-footer sync bound,
  replacing the three duplicated literal `500`s: both uses in
  `await-retry-footer-text!` and the inline `support/await-until … 500` in the
  sibling `rpc-prompt-provider-retry-state-publishes-footer-updated-test`.
  Future tuning no longer drifts between the two harness copies.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 54 assertions),
  `clj-kondo` clean, `clj-paren-repair` clean. No product/behaviour change; no
  CHANGELOG entry (test-only). Slice 3 items remain the untouched diagnosis
  branch (background-only outcome was taken); the Slice-3 "focused broken" arm
  is intentionally left unchecked per the recorded working-as-intended
  determination.

## Slice 10 — test-shaper 2nd pass follow-up (addressed 1 review step)

- **Symmetric observable-timeout (item 1):** routed the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test`'s inline
  `:provider-retry-sleep-fn` through the now-hardened `await-retry-footer-text!`
  helper (shared with the focused sub-test), replacing its bare
  `support/await-until … retry-footer-sync-timeout-ms` that discarded the
  return value. The sibling now inherits the same fail-fast timeout guard: a
  sync deadline miss surfaces as "retry footer sync timed out awaiting <text>"
  rather than masquerading as a generic "must publish footer/updated"
  regression — closing the residual swallowed-timeout defect Slice 9 removed
  only for the focused test. This also collapses the duplicated sleep-fn /
  `expected-text` logic that `retry-footer-sync-timeout-ms` had only partially
  unified; both harnesses now share one helper.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 56 assertions),
  `clj-kondo` clean, `clj-paren-repair` clean. Test-only; no product/behaviour
  change, no CHANGELOG entry.

## task-test-review session (test-shaper pass, 3rd) — outcome

- added 1 step (Slice 11): Slice 10 unified only the sleep-fn, but the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test` still inlines
  the whole retry-driving body (`error-turn`, `with-redefs`, attempt `case`,
  `execute-prepared-request!`, progress-loop lifecycle) that the parameterized
  `drive-provider-retry-through-progress-loop!` already encapsulates — a
  divergent second copy reusable today (distinct from 243's mechanism migration).

## Slice 11 — test-shaper 3rd pass follow-up (addressed 1 review step)

- **Retry-driving body dedup:** collapsed the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test`'s ~60-line
  inline retry body (`error-turn`, `attempts*`, `with-redefs
  turn-runtime/execute-live-turn!` 429→429→recovery `case`,
  `execute-prepared-request!`, `start-progress-loop!`/`stop-progress-loop!`
  lifecycle) into a single call to the already-parameterized
  `drive-provider-retry-through-progress-loop!`, passing its pre-gate raw
  `emit!` (`(fn [event data] (swap! emitted* conj {:event event :data data}))`)
  and asserting against `@emitted*` for the activation/changed/clear/session-id
  footers. The two harnesses now share one definition of the 429 headers,
  attempt sequence, and thread lifecycle, so they cannot drift; only the
  `emit!` capture strategy differs (pre-gate raw `emitted*` here vs the focus-
  gated `make-request-emitter` in the sibling). The single remaining
  `with-redefs turn-runtime/execute-live-turn!` (inside the shared helper) is
  the tracked ¬mock/¬stub exception whose exit is task 243. Removed the now-
  unused `psi.session-state.state` require (session-id now comes from
  `create-session-context`'s return, matching the focused test).
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 56 assertions —
  unchanged, behaviour-preserving), `clj-kondo` clean, `clj-paren-repair`
  clean. Test-only; no product/behaviour change, no CHANGELOG entry.

## task-test-review session (test-shaper pass, 4th) — outcome

- added 1 step (Slice 12): background sub-test's `(is (empty? footer-events))`
  cannot distinguish gate-suppression from footer-non-production — a distinct
  vacuity branch from Slice 8 (retry-never-fired) and Slice 5 item 2 (drain).
  `(= 3 attempts)` proves retry turns fired, not that `footer/updated` frames
  were produced, so a background footer-production regression would pass green;
  the load-bearing evidence is only a one-time manual bypass check, not encoded.

## Slice 12 — test-shaper 4th pass follow-up (addressed 1 review step)

- **Production-vs-gating positive control:** encoded the distinguishing control
  the review flagged (option (a)). Before the gated run, the background sub-test
  now drives the *identical* background retry config through a pre-gate raw
  `emit!` (synchronized via the shared `await-retry-footer-text!`) and asserts
  it produces ≥1 retry `footer/updated` frame (`retry in …`). Only then does the
  second run (through `make-request-emitter` with foreign focus) assert
  `(is (empty? footer-events))`. This credits the `empty?` assertion against a
  live-and-producing pipeline: a footer-production regression (e.g. in
  `footer-refresh-progress-event?` matching `:retry-updated` or in
  `emit-footer-updated!` / status-line construction) now fails the pre-gate
  production assertion instead of passing green as a false "correctly gated".
  The prior one-time manual bypass check is now encoded as a standing
  assertion. `(= 3 attempts)` still proves the retry turns fired; the new
  pre-gate footer assertion proves frames were produced.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 60 assertions,
  +4 from the pre-gate control), `clj-kondo` clean, `clj-paren-repair` clean.
  Test-only; no product/behaviour change, no CHANGELOG entry.

## task-test-review session (test-shaper pass, 5th) — outcome

- added 1 step (Slice 13): Slice 10's dedup claim is inaccurate — the
  `expected-text` derivation `(str "retry in " (quot (long delay-ms) 1000) "s")`
  is still triplicated across three `:provider-retry-sleep-fn` sites; Slice 10
  unified only the await/timeout mechanism, not the delay→text logic
  (a `consistent`/`economical` residual + a doc↔code coherence gap).

## Slice 13 — test-shaper 5th pass follow-up (addressed 1 review step)

- **expected-text dedup:** folded the triplicated delay→text derivation
  `(str "retry in " (quot (long delay-ms) 1000) "s")` into a single authority.
  Added `expected-retry-text` (delay-ms → "retry in Ns") and a
  `retry-footer-sleep-fn` builder that constructs the `:provider-retry-sleep-fn`
  once from `captured`, deriving the text internally via `expected-retry-text`.
  All three call sites (focused L~254, background pre-gate control L~313, sibling
  L~388) now read `(retry-footer-sleep-fn <captured>)` instead of a hand-built
  `(fn [delay-ms] (await-retry-footer-text! <captured> (str "retry in " …)))`.
  Slice 10 had unified only the await/timeout mechanism, not the delay→text
  logic — this closes that `consistent`/`economical` residual and the doc↔code
  coherence gap (a completed slice claiming a dedup the code did not reflect).
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 60 assertions —
  unchanged, behaviour-preserving), `clj-kondo` clean, `clj-paren-repair` clean.
  Test-only; no product/behaviour change, no CHANGELOG entry.
- addressed 1 review step.

## task-test-review session (test-shaper pass, 6th) — outcome

- added 1 step (Slice 14): the retry-**clear** frame is verified only by a
  bare negative on `(last footer-events)` in both retry-footer tests, with no
  positive control that a clear footer was actually produced — a distinct
  production-vs-gating vacuity branch (clear-path) from the
  activation/changed/attempts/background controls closed in Slices 8/12.

## Slice 14 — test-shaper 6th pass follow-up (addressed 1 review step)

- **Clear-frame production-vs-gating positive control:** added
  `clear-footer-produced-after-retry` (returns the `footer/updated` frame that
  follows the *last* active-retry frame, or `nil` if none) plus a
  `retry-status-line?` predicate, and asserted `(is (some? clear-footer))` in
  both retry-footer tests (focused sub-test of
  `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  and the sibling `rpc-prompt-provider-retry-state-publishes-footer-updated-test`).
  The no-stale-`retry in` check is now applied to that positively-identified
  clear frame instead of a bare `(last footer-events)`. Verified frame sequence
  via nREPL: activation (`retry in 8s`), changed (`retry in 4s`, twice from the
  poll), then a trailing `nil`-status clear footer — the helper returns that
  clear frame and `nil` when no footer follows the retry frames, so a
  clear-path regression (clear footer never emitted, some unrelated non-retry
  footer incidentally trailing) now fails `(is (some? clear-footer))` rather
  than passing green — the same production-vs-gating `meaningful_failures` gap
  Slices 8/12 closed for the activation/changed/background frames, now closed
  for the clear transition. Kept here (not forwarded to 243) since it hardens
  the existing assertions in-place.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions,
  +2 from the two `some? clear-footer` controls), `clj-kondo` clean,
  `clj-paren-repair` clean. Test-only; no product/behaviour change, no CHANGELOG
  entry.
- addressed 1 review step.

## task-test-review session (test-shaper pass, 7th) — outcome

- added 1 step (Slice 15): Slice-12's pre-gate production control claims to
  drive the "identical background config" but uses a blocking
  `retry-footer-sleep-fn` while the gated `empty?` run uses a no-op sleep — a
  `meaningful_failures` gap (the control vouches for a different config than the
  one under test) plus a doc↔code coherence gap (inaccurate "identical" wording).

## Slice 15 — test-shaper 7th pass follow-up (addressed 1 review step)

- **Config-divergence / inaccurate-"identical" residual:** the Slice-12 comment
  claimed the pre-gate production control drives the "identical background
  config", but the pre-gate control uses the *blocking* `retry-footer-sleep-fn`
  while the gated `empty?` run uses a *no-op* `(fn [_delay-ms] nil)` sleep.
  Empirically confirmed via `bb test` that the divergence is **necessary, not
  incidental**: driving the pre-gate control under the no-op sleep produces 4
  `footer/updated` frames all carrying `:status-line nil` — the retry
  activates and clears before the 10ms progress loop polls, so no `retry in Ns`
  text is ever delivered and the production assertion (`some … "retry in"`)
  fails. The blocking sleep is required precisely because the pre-gate control
  must positively assert live retry *text*; the gated run needs only that *any*
  `footer/updated` frame is dropped by `focus-allows?`, which the synchronous
  `stop-progress-loop!` drain credits without live retry text.
- **Resolution: option (b) — corrected the wording and recorded the deliberate
  divergence** (option (a) is not viable, as the no-op-sleep control cannot
  deterministically produce `retry in` text). Replaced the inaccurate
  "identical background config" comment with a precise DELIBERATE-divergence
  note explaining: the two runs drive the *same retry scenario and config*
  (identical `drive-provider-retry-through-progress-loop!` 429→429→recovery
  sequence, identical config, identical synchronous drain), differing only in
  the sleep-fn, and each run uses the sleep-fn appropriate to what it must prove
  — production (needs live text → blocking) vs suppression (needs only drained
  frames → no-op). Updated the gated run's comment to reference the divergence
  note rather than re-asserting "identical". This closes the
  `meaningful_failures` gap (the control now honestly credits the config under
  test for what it can prove) and the doc↔code coherence gap (no inaccurate
  "identical" wording standing). Kept here (not forwarded to 243) since it
  hardens the existing in-place documentation.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions —
  unchanged, doc-only), `bb test --focus psi.rpc-events-test` (20/20 pass,
  task-241 focus-gate invariants untouched), `clj-kondo` clean,
  `clj-paren-repair` clean. Comment/doc-only; no product/behaviour change, no
  test-logic change, no CHANGELOG entry.
- addressed 1 review step.

## task-test-review session (test-shaper pass, 8th) — outcome

- added 1 step (Slice 16): the deterministic-sync helper's `expected-retry-text`
  derives the awaited footer text with `(quot delay-ms 1000)` (floor), but
  production builds it with `Math/ceil` of a delivery-time `resume-at - now-ms`
  delta (`retry_display/format-relative-seconds`) — a `quot`-vs-`ceil` +
  snapshot-vs-live-delta coupling that coincides only for whole-second
  `Retry-After` values, and can surface as a false Slice-9 sync-timeout naming a
  text production never emits. Distinct from Slices 10/13 (which only dedup'd the
  derivation, never questioned its correctness).

## Slice 16 — test-shaper 8th pass follow-up (addressed 1 review step)

- **quot-vs-ceil sync-text formula alignment:** changed `expected-retry-text`
  (the single authority the deterministic-sync helper `await-retry-footer-text!`
  / `retry-footer-sleep-fn` uses) to derive the awaited `"retry in Ns"` seconds
  from the *same* production authority the footer uses —
  `psi.app-runtime.retry-display/format-relative-seconds` (`Math/ceil`) —
  instead of the prior `(quot (long delay-ms) 1000)` floor. Production builds
  the footer text via `format-relative-seconds (- resume-at now-ms)` re-read at
  async delivery time (`retry_display.clj`); with `resume-at = now₀ + delay-ms`
  (`session-state/model.clj retry-metadata`), the delivered text is
  `ceil((resume-at - now-delivery)/1000)`. The old `quot` floor coincided only
  for whole-second `Retry-After` values with sub-second delivery drift; for a
  non-whole-second delay it could await `"retry in Ns"` while production emits
  `"retry in (N+1)s"`, making the Slice-9 sync fail-fast fire on a text that is
  never produced (a false timeout masking a live-and-correct pipeline). Matching
  production's `ceil` closes that latent `deterministic`/`robust` desync. Added
  the `psi.app-runtime.retry-display` require (already on the rpc component's
  classpath via `psi/app-runtime`). Kept here (not forwarded to 243) since it
  hardens the existing in-place sync helper.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions —
  unchanged; `format-relative-seconds` of the whole-second 8000/4000 delays
  yields the same `8s`/`4s` text, so behaviour-preserving),
  `bb test --focus psi.rpc-events-test` (20/20 pass, task-241 focus-gate
  invariants untouched), `clj-kondo` clean, `clj-paren-repair` clean. Test-only;
  no product/behaviour change, no CHANGELOG entry.
- addressed 1 review step.

## task-test-review session (test-shaper pass, 9th) — outcome

- no new follow-up steps. Re-reviewed the retry-footer E2E tests against
  `simple ∧ consistent ∧ robust ∧ economical`: every vacuity branch has a
  positive control (attempts Slice 8; activation/changed/background production
  Slice 12; clear-frame Slice 14), timeouts are observable and symmetric across
  both harnesses (Slices 9/10), the retry-driving body / sleep-fn / expected-text
  are deduped to single authorities (Slices 11/13), and the sole `with-redefs`
  exception has a tracked exit (task 243). Checked candidates for genuinely-new
  issues and found each already covered: the sleep-fn awaits only `retry in 4s`
  while the changed-metadata assertion also needs `remaining 2/5000`, but
  `mark-active-retry!` writes `:retry` (delay + rate-limit) atomically before the
  `:retry-updated` emit, so no partial-frame race; the `expected-retry-text`
  delay-ms vs production live `resume-at - now-ms` delta coupling is already
  Slice 16's recorded bounded residual; slow-test metadata isolation is not a
  project convention (zero `^:slow`/`^:integration` usages), so not filed.
  Tests green (rpc-prompt 6/6, 62 assertions).

## review-task-docs session — outcome

- No actionable user-facing documentation issues; no steps added. Task 242 is
  test-only (single changed file `components/rpc/test/psi/rpc_prompt_test.clj`;
  "working as intended" diagnosis, no code/behaviour change). The focus-gated
  `footer/updated` delivery rule 242 confirmed is already accurately documented
  in `doc/architecture.md` (per-connection focus-gated delivery, background
  suppression) and in the CHANGELOG `### Changed` focus-gating entry; no new or
  stale references, examples, or missing docs. No new CHANGELOG entry warranted.

## code-shaper review session — outcome

- added 2 steps (Slice 17): assertion-side retry-frame matchers (`retry in
  8s`/`retry in 4s` + `remaining 2/5000`) are hand-duplicated across both
  harnesses and re-derived from the driving config as raw literals rather than
  the existing `expected-retry-text` authority (prior slices deduplicated only
  the sync-side sleep-fn/expected-text, never the assertion matchers); and the
  `(or (get-in frame [:data :status-line]) "")` status-line accessor idiom is
  repeated at ~8 sites without a shared accessor.

## Slice 17 — code-shaper follow-ups (addressed 2 review steps)

- **Assertion-matcher single-authority (item 1):** extracted shared
  `activation-retry-footer?` / `changed-retry-footer?` predicates that derive
  their awaited text from the existing `expected-retry-text` authority (aligned
  to production's `ceil` in Slice 16) via named `activation-retry-delay-ms`
  (8000) / `changed-retry-delay-ms` (4000) constants — the delivered-footer form
  of `drive-provider-retry-through-progress-loop!`'s first/second `Retry-After`
  headers — plus a `remaining-fragment` builder for the changed frame's
  `remaining 2/5000` rate-limit text. Both harnesses now match retry frames
  through these predicates instead of re-inlining raw `"retry in 8s"` /
  `"retry in 4s"` / `"remaining 2/5000"` literals at the ≥3 assertion sites
  (focused sub-test L~323/327, sibling `first-retry-footer`/`changed-retry-footer`
  L~473/477). A footer-format or driving-delay change now updates one authority
  rather than drifting silently across assertion copies.
- **Status-line accessor (item 2):** extracted `frame-status-line` (returns `""`
  on absence) as the single authority for the `[:data :status-line]` frame path
  and routed all retry-footer matchers/predicates/assertions through it
  (`await-retry-footer-text!`, `retry-status-line?`, `activation-retry-footer?`,
  `changed-retry-footer?`, both harnesses' clear-footer assertions). The
  `(or (get-in frame [:data :status-line]) "")` idiom is no longer hand-repeated
  at ~8 sites; a frame-shape change is edited once.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions —
  unchanged, behaviour-preserving), `bb test --focus psi.rpc-events-test`
  (20/20 pass, task-241 focus-gate invariants untouched), `clj-kondo` clean,
  `clj-paren-repair` clean. Test-only; no product/behaviour change, no CHANGELOG
  entry.
- addressed 2 review steps.

## code-shaper review session (2nd pass) — outcome

- added 2 steps (Slice 18): Slice 17's `activation-retry-delay-ms`/`changed-retry-delay-ms`
  constants do not actually track the driving config — the driver's
  `Retry-After "8"`/`"4"` (and `RateLimit-Remaining`/`-Limit`) header literals
  are independent copies, so driver-vs-matcher can silently drift (contradicting
  Slice 17's own "single authority" docstring); and the `{:auto-retry-base-delay-ms
  8000 :auto-retry-max-retries 2}` session config is duplicated verbatim at four
  `create-session-context` sites (a fourth copy of the same 8s delay).

## Slice 18 — code-shaper follow-ups (addressed 2 review steps)

- **Driver ↔ matcher single authority (item 1):** the driver's `error-turn`
  429 headers now derive from the same constants the assertion matchers use.
  Added `retry-after-seconds` (ms → whole-second `Retry-After` string) and
  `retry-rate-limit` (5000) / `changed-retry-remaining` (2) constants; the
  first/second 429 `Retry-After` headers are built via
  `(retry-after-seconds activation-retry-delay-ms)` /
  `(retry-after-seconds changed-retry-delay-ms)`, and the `RateLimit-*` headers
  from `retry-rate-limit`/`changed-retry-remaining`. `remaining-fragment` and
  `changed-retry-footer?` now match against the same rate-limit constants.
  Driver and matchers can no longer drift: changing a delay or rate-limit value
  updates one authority, not both the driver header literals and the matcher
  constants. Docstrings corrected to state the single-authority relationship
  accurately (driver derives *from* the constants).
- **Session-config single authority (item 2):** extracted
  `retry-footer-session-context!` — one builder returning the non-persisted
  `[ctx session-id]` with `:auto-retry-base-delay-ms activation-retry-delay-ms`
  (fourth copy of the 8s delay folded onto the same authority) and
  `:auto-retry-max-retries 2`. Replaced all four verbatim
  `create-session-context` call sites (focused, background pre-gate control,
  background gated, sibling) with the builder.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6, 62 assertions —
  unchanged, behaviour-preserving), `bb test --focus psi.rpc-events-test`
  (20/20, task-241 focus-gate invariants untouched), `clj-kondo` clean,
  `clj-paren-repair` clean. Test-only; no product/behaviour change, no CHANGELOG
  entry.
- addressed 2 review steps.

## code-shaper review session (3rd pass) — outcome

- added 1 step (Slice 19): the two clear-footer negatives re-spell
  `(not (str/includes? (frame-status-line clear-footer) "retry in"))` longhand
  instead of `(not (retry-status-line? clear-footer))`, so the active-retry
  `"retry in"` literal has ≥3 independent copies (predicate + two negations +
  `expected-retry-text` prefix) — Slice 17 routed only the positive matchers,
  never the negated clear assertions, onto their authority.

## Slice 19 (code-shaper 3rd) — addressed

- Routed both clear-footer negatives (focused sub-test + sibling) through
  `(not (retry-status-line? clear-footer))`, replacing the inlined
  `(not (str/includes? (frame-status-line clear-footer) "retry in"))` longhand.
- Extracted `active-retry-text-prefix` ("retry in ") as the single authority
  shared by `expected-retry-text` (positive builder) and `retry-status-line?`
  (substring predicate), collapsing the duplicated literal to one place.
- rpc-prompt-test green (62 assertions), clj-kondo clean.

## code-shaper review session (4th pass) — outcome

- added 2 steps (Slice 20): the focus-gated emitter construction sequence
  (`make-rpc-state` → `subscribe-topics!` → `set-focus-session-id!` →
  `make-request-emitter`) is duplicated verbatim across the two focus-gated
  sub-tests (differing only in focus session-id) — untouched by Slice 18's
  session-config builder; and the `(filterv #(= "footer/updated" (:event %)) …)`
  frame-filter idiom is repeated at four retry-footer sites with no shared
  selector — parallel to Slice 17's `frame-status-line` accessor but for frame
  *selection* rather than status-line access.

## Slice 20 — code-shaper 4th pass follow-ups (addressed 2 review steps)

- **Focus-gated emitter builder (item 1):** extracted `focus-gated-emitter!`
  (given a focus-session-id, returns `[emit! captured]`) as the single authority
  for the `make-rpc-state` → `subscribe-topics! … rpc.events/event-topics` →
  `set-focus-session-id!` → `make-request-emitter … "req-1"` sequence. Both
  focus-gated sub-tests of
  `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  (focused: focus = retrying session; background: focus = `other-session-id`)
  now call it, differing only in the focus session-id under test — so the
  focus-gated emit boundary has one construction authority and the
  focused-vs-background pair cannot drift in the topic set, request-id, emitter
  constructor, or rpc-state shape.
- **Footer-frame selector (item 2):** extracted `footer-updated-frames`
  (`(filterv #(= "footer/updated" (:event %)) frames)`) as the single authority
  for footer-frame selection, routing all four retry-footer sites (focused,
  background pre-gate control, background gated, sibling) through it. A change to
  the `"footer/updated"` topic string or the `:event` frame path is now edited
  once, and a topic typo can no longer silently filter to `[]` and pass a
  downstream `empty?`/`seq` assertion vacuously at a retry-footer site. Left the
  unrelated L116 `footer-updated` filter in
  `rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test` as-is (the
  Slice-20 item scopes the extraction to the retry-footer sites).
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions —
  unchanged, behaviour-preserving), `bb test --focus psi.rpc-events-test`
  (20/20 pass, task-241 focus-gate invariants untouched), `clj-kondo` clean,
  `clj-paren-repair` clean. Test-only; no product/behaviour change, no CHANGELOG
  entry.
- addressed 2 review steps.

## code-shaper review session (5th pass) — outcome

- added 1 step (Slice 21): the `remaining-fragment` matcher re-spells the
  `"remaining R/L"` fragment format independently of the production authority
  `retry-display/retry-status-text`/`remaining-text` — Slice 16 aligned only the
  delay text to production's `format-relative-seconds`, and Slice 18 unified only
  the rate-limit *values*, leaving the remaining-fragment *format string* as a
  hand-rolled second copy that can drift on a footer-format change.

## Slice 21 implementation (code-shaper 5th-pass follow-up)

- Routed `remaining-fragment` through the production authority
  `retry-display/retry-status-text`: it now builds `"retry in 0s · remaining R/L"`
  from constructed retry metadata (`:active? true :resume-at 0 :rate-limit
  {:remaining :limit}`) and extracts the fragment after the leading delay part
  via `(str/split status-line #" · " 2)` → `second`. The hand-rolled
  `(str "remaining " remaining "/" limit)` copy is removed, so the `"remaining "`
  prefix and `"/"` separator now have a single authority in `retry_display.clj`;
  a footer-format change there tracks the `changed-retry-footer?` matcher
  automatically (removes the `quot`-vs-`ceil`-class drift Slice 16 removed for the
  delay text, now also for the remaining fragment).
- Behaviour-preserving: derived fragment `"remaining 2/5000"` equals the prior
  literal (verified via REPL against `retry-display/retry-status-text`).
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions),
  `clj-kondo` clean, `clj-paren-repair` clean. Test-only; no product/behaviour
  change, no CHANGELOG entry.
- addressed 1 review step.

## code-shaper review session (6th pass) — outcome

- added 1 step (Slice 22): the active-retry `"retry in "` prefix literal remains
  a hand-copied second authority (test `active-retry-text-prefix` vs production
  `retry-display/retry-status-text`), un-routed to production — the last
  status-line format literal Slices 16/21 did not fold onto the
  `retry-display` authority, leaving a `retry_display.clj`↔matcher drift the
  seconds and remaining fragment no longer have.

## design-review session (first turn — architecture pass) — outcome

- no architectural review feedback. Independently re-confirmed against
  doc/architecture.md focus-gated delivery rule (session-scoped `footer/updated`
  delivered only on focus match, suppressed otherwise; §"Projection delivery
  rule"), the app-runtime/RPC footer ownership boundary, and the task-241
  no-cross-session-leakage invariant. Design conforms; the pre-existing
  push-emit vs recompute-at-delivery divergence stays out of frozen scope. No
  new design-steps.

## design-review session (second turn — ambiguity pass) — outcome

- no ambiguity review feedback. The sole ambiguity (AC1 failing-then-passing vs
  working-as-intended branch) is already filed as the design-step and resolved
  in design.md (AC1 made contingent on Approach step-1 diagnosis). Terms pinned:
  "retry backoff text" → `:status-line` containing `retry in …`; focused vs
  background/delegated sessions distinguished in Context. No new design-steps.

## design-review session (third turn — inconsistency pass) — outcome

- no inconsistency review feedback. design.md is internally consistent: AC1's
  contingent branch matches the Approach focused-vs-background diagnosis, and the
  "characterize + record determination" criterion aligns with the
  working-as-intended outcome. Referenced artifacts verified in prior passes
  (quoted `focus-allows?` ≡ actual events.clj; `footer/updated` carries
  `:status-line`; `required-event-payload-keys` is a required-keys set, not an
  allow-list). No new design-steps.

## design-review session (all three turns) — aggregate outcome

- No new design-steps from any turn (architecture, ambiguity, inconsistency). No
  design-step-addressing follow-up task is warranted from this session: the only
  design-step (AC1 contingency) is already resolved in design.md. If any
  AC1-adjacent work arises later, keep failing-then-passing contingent on the
  Approach step-1 focused-vs-background diagnosis, never widen frozen scope, and
  preserve the task-241 no-cross-session-leakage invariant. Authoritative
  references: doc/architecture.md §"Projection delivery rule" (focus-gated
  `footer/updated`); components/rpc/src/psi/rpc/events.clj (`focus-allows?`).

## code-shaper review session (7th pass) — outcome

- No new follow-up steps. Re-reviewed the retry-footer E2E harness against
  `simple ∧ consistent ∧ robust`. The prior code-shaper passes (Slices 17–22)
  have folded every status-line format literal (`"retry in "` prefix, seconds,
  `"remaining R/L"` fragment) onto the production authority
  `psi.app-runtime.retry-display`, and unified the matchers, `frame-status-line`
  accessor, `footer-updated-frames` selector, `focus-gated-emitter!`,
  `retry-footer-session-context!`, and the driver-header ↔ matcher constants onto
  single authorities. Every vacuity branch (attempts / activation / changed /
  background production / clear) carries a positive control, timeouts are
  observable and symmetric across both harnesses, and the sole `with-redefs`
  ¬mock exception has a tracked exit (task 243). Checked residual candidates and
  found each already deliberately scoped or load-bearing: the L116/L151
  `footer/updated` filter duplication is the unrelated
  `rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test`, explicitly
  excluded from Slice 20's retry-footer extraction with recorded rationale; the
  extensive `task 242 Slice N` provenance comments (incl. the Slice-15 divergence
  note) document load-bearing rationale, not incidental prose. No genuinely-new
  `consistency`/`simplicity`/`robustness` defect remains — filing further
  micro-dedup steps would manufacture findings against a converged harness.

## Slice 22 — code-shaper 6th pass follow-up (addressed 1 review step)

- **`"retry in "` prefix production-authority routing:** folded the last
  hand-copied status-line format literal onto `retry-display`. `expected-retry-text`
  now derives the *whole* active-retry text (prefix + seconds) from the
  production authority `retry-display/retry-status-text` — building the leading
  `" · "` fragment from `{:active? true :resume-at delay-ms}` at `now-ms 0`
  (no rate-limit, so the status-line is just the delay fragment `"retry in Ns"`)
  — instead of `(str active-retry-text-prefix (format-relative-seconds …))` with
  a hand-copied `"retry in "` literal. `active-retry-text-prefix` (used by the
  `retry-status-line?` substring predicate + both clear negations) is now itself
  *derived from production*: `retry-status-text {:active? true :resume-at 0}`
  minus the `format-relative-seconds 0` suffix = `"retry in "`. So a footer-format
  change to the prefix in `retry_display.clj` cannot desync the matcher/predicate
  — completing the Slice 16 (seconds via `format-relative-seconds`) + Slice 21
  (remaining fragment via `retry-status-text`) folding for the fixed prefix.
  Removes the `retry_display.clj`↔matcher prefix drift the seconds and remaining
  fragment no longer had. Kept here (not forwarded to 243) since it hardens the
  existing in-place matcher authorities.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions —
  unchanged, behaviour-preserving; `retry-status-text` of the whole-second
  8000/4000 delays yields the same `"retry in 8s"`/`"retry in 4s"` text),
  `bb test --focus psi.rpc-events-test` (20/20 pass, task-241 focus-gate
  invariants untouched), `clj-kondo` clean, `clj-paren-repair` clean. Test-only;
  no product/behaviour change, no CHANGELOG entry.
- addressed 1 review step.

## plan-review session (ambiguity turn — batch review) — outcome

- no ambiguity review feedback. plan.md/steps.md are unambiguous: AC1's
  failing-then-passing requirement is pinned as **contingent** on Approach
  step-1's focused-vs-background diagnosis, the test boundary is pinned to
  `emit-frame!`, the retry-text matcher to `retry in`, and the fix constraint to
  gate-preserving (no `focus-allows?` weakening). The only ambiguity (AC1
  contingency) was already filed and resolved in a prior pass; no new actionable
  ambiguity found. No new design-steps filed.

## plan-review session (inconsistency turn — batch review) — outcome

- no inconsistency review feedback. plan.md ↔ steps.md ↔ design.md ↔
  design-steps.md agree on the AC1 contingency, the diagnosis outcome
  (background-only / working as intended), the `emit-frame!` test boundary, and
  the `retry in` matcher. plan.md's `rpc_events_test.clj` mention is a *pattern*
  citation (focus-gate characterization style), not a location directive that
  contradicts steps.md's `rpc_prompt_test.clj` home — the divergence is normal
  plan→steps refinement, documented in implementation.md. No new design-steps
  filed.

## task-implementation-review session — outcome

- added 2 steps (Slice 23): aggregate test-helper over-abstraction relative to
  the frozen test-only scope (esp. `active-retry-text-prefix`'s brittle
  length-subtraction prefix derivation), and a lifecycle-ordering anomaly
  (design/plan review passes ran *after* the task was closed).
- Non-compliance note: the review was run against an already-*closed* task
  (`munera/closed/242-…`, not `munera/open/…` as the request path assumed); the
  task was closed at `58a16fd53` before this review. Steps added to closed
  task's steps.md accordingly.

## Slice 23 — implementation-review follow-ups (determinations, addressed 2 review steps)

Both Slice-23 items are assessment/determination items (not code changes). The
harness is frozen, test-only, green (`bb test --focus psi.rpc-prompt-test` →
6/6, 62 assertions), and is scheduled for a full rewrite by task 243.

- **Item 1 — aggregate test-helper over-abstraction: judgement = FORWARD-TO-243
  (keep in place for 242, do not revert).** The ~15 single-authority
  helpers/constants (Slices 17–22) are a real `unnecessary_abstraction`
  residual for a single test pair; the sharpest case, `active-retry-text-prefix`,
  derives the fixed `"retry in "` prefix by length-subtracting
  `(format-relative-seconds 0)` off `retry-status-text {:active? true :resume-at 0}`
  — indirect and brittle (silently breaks if production reorders/space-pads the
  fragment) versus the one-line literal it replaced. Reverting now was rejected:
  it would churn a green, frozen, soon-to-be-replaced harness for no lasting
  benefit and risk destabilizing the deterministic sync the E2E test depends on.
  Keeping-as-unassessed was rejected: the residual must be explicitly owned. The
  format-coupling / prefix-derivation concern is therefore forwarded to task
  243's harness rewrite (which co-migrates both call sites onto the
  provider-registry seam and will naturally reconstruct the matcher/format
  helpers) — see 243 design.md Notes. Task 243 should prefer the direct
  production authority (`retry-status-text` / a literal) over the
  length-subtraction derivation when it rebuilds the matchers.

- **Item 2 — post-close lifecycle ordering: determination = lifecycle-driver
  gap, NOT intentional catch-up bookkeeping; recurs; fix out-of-scope for 242.**
  Evidence: close commit `58a16fd53` (git-mv open/ → closed/, 13:06) preceded
  four design-review commits (`4f30a1a1e`/`1c9caf4d3`/`516cb062e`/`0a616983e`,
  13:36–13:38) and two plan-review commits (`2ee8ba795`/`d48c15c9b`, 13:40),
  then implementation-review (`0c39c2687`, 13:45). `.psi/workflows/task-lifecycle.edn`
  runs steps in a fixed linear order (review-task-design → create-task-plan →
  review-task-plan → implement-task → review-task-implementation) with **no
  already-closed guard** at entry: re-invoking the chained lifecycle on an
  already-closed+implemented task re-runs the earlier design/plan-review phases
  against the frozen artifacts. That is exactly the observed sequence; the
  passes were vacuous ("no new feedback") only because the artifacts were
  frozen. So this is a driver behaviour that would recur on any closed task the
  workflow is re-invoked on, not deliberate bookkeeping. Fixing it (adding an
  entry short-circuit that detects `munera/closed/…` and skips the pre-implement
  lifecycle phases) belongs to the `task-lifecycle` workflow owner, not to task
  242's frozen test-only scope (per the workflow-runtime / owning-workflow
  boundary). Recommendation recorded here for that owner; no change made under 242.

- addressed 2 review steps.

## task-implementation-review session (2nd) — outcome

- added 1 step (Slice 24): `await-retry-footer-text!`'s Slice-9 timeout guard
  calls `clojure.test/is` from inside the `:provider-retry-sleep-fn`, valid only
  because the retry loop runs on the test thread (`execute-prepared-request!`
  called directly) — an undocumented thread-affinity invariant whose violation
  would silently re-open the swallowed-timeout blind spot Slice 9 closed.
- Non-compliance note: review run against an already-*closed* task
  (`munera/closed/242-…`, not the requested `munera/open/…` path); task closed
  at `58a16fd53` before this review.

## Slice 24 — implementation-review 2nd-pass follow-up (addressed 1 review step)

- **Thread-affinity invariant encoded (option (a)):** documented the previously
  undocumented invariant that `await-retry-footer-text!`'s
  `(is (not= support/timeout-token …))` guard is only *counted* when the
  `:provider-retry-sleep-fn` runs on the **test thread**. Verified the invariant
  holds today: `sleep-for-retry!` (`turn_runtime/core.clj` L622) is on the
  synchronous retry-loop path driven by `execute-prepared-request!`, which
  `drive-provider-retry-through-progress-loop!` calls *directly* on the test
  thread; the daemon thread from `streams/start-progress-loop!` only drains the
  progress queue. Added (1) a THREAD-AFFINITY INVARIANT section to
  `await-retry-footer-text!`'s docstring explaining the `is`/`*report-counters*`
  thread-local dependency and mandating that any move of the retry loop
  off-thread must re-home the timeout failure to a thread-safe channel; and (2)
  a THREAD-AFFINITY marker comment at the `execute-prepared-request!` drive site
  in `drive-provider-retry-through-progress-loop!` cross-referencing the
  docstring, so an edit that moves the drive off-thread encounters the warning.
  Chose option (a) (documenting the invariant) over option (b) (restructuring to
  a promise/atom asserted post-drive) since the change is test-only, frozen, and
  comment-only preserves behaviour; task 243 keeps the `await-retry-footer-text!`
  sync pattern, so the invariant is documented in-place rather than forwarded.
- Verification: `bb test --focus psi.rpc-prompt-test` (6/6 pass, 62 assertions —
  unchanged, comment-only), `clj-kondo` clean, `clj-paren-repair` clean.
  Test-only; no product/behaviour change, no CHANGELOG entry.
- addressed 1 review step.

## task-implementation-review session (3rd) — outcome

- No new actionable steps. Independently verified: implementation matches the
  design (background-only / working-as-intended diagnosis; characterization test
  at the RPC `emit-frame!` boundary crossing the real `emit-event!`/`focus-allows?`
  gate), preserves the task-241 focus-gating invariant, and every vacuity branch
  (attempts, activation, changed, background production, clear) carries a
  positive control. Tests green (rpc-prompt 6/6, 62 assertions). The 3 unchecked
  steps.md items are the Slice-3 "focused-broken" conditional arm, correctly
  left unchecked for the working-as-intended outcome. The aggregate
  over-abstraction / brittle-derivation residual is already owned (Slice 23,
  forwarded to task 243). No genuinely-new defect found.
- Non-compliance note: review run against an already-*closed* task
  (`munera/closed/242-…`, not the requested `munera/open/…` path); task was
  closed at `58a16fd53` before this review.

## task-test-review session (4th pass, deep) — outcome

- added 1 step (Slice 25): the E2E focused regression lock only exercises the
  `focus-allows?` *explicit-focus* arm (`focus-gated-emitter!` always sets
  focus); the `default-session-id` fallback arm — the single-focused-session /
  no-explicit-focus path the design names as the prime suspect — is never driven
  with a retry footer end-to-end. The prior 4th-pass "not a gap here" dismissal
  conflated fallback *logic* (unit-tested) with retry-*footer*-under-fallback
  (uncovered E2E).
- Non-compliance note: review run against an already-*closed* task
  (`munera/closed/242-…`, not the requested `munera/open/…` path); task closed
  at `58a16fd53` before this review.

## Slice 25 resolution — default-session-id fallback E2E lock

- Addressed 1 review step (Slice 25). Added focused sub-test variant to
  `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
  driving retry `footer/updated` frames through `focus-allows?`'s
  `default-session-id` fallback arm (no explicit focus). New helper
  `default-focus-emitter!` seeds `:default-session-id` via `make-rpc-state` then
  clears explicit focus (`set-focus-session-id!` nil), forcing
  `(or (focus-session-id state) (default-session-id state))` to take the
  fallback branch — the prime-suspect single-session/no-explicit-focus path.
  Sub-test asserts activation + changed + clear footers reach `emit-frame!` for
  the retrying session and are session-id-stamped. Green: 24 assertions in the
  test, 71/6 across the namespace; clj-kondo clean.

## task-test-review session (5th pass) — outcome

- No new actionable steps. Re-verified the three skill criteria against the
  current test: (1) well-formed — rpc-prompt 5/5 green (55 assertions); (2)
  design-behaviour coverage — the full pipeline is locked end-to-end across two
  suites: RPC E2E covers stages 1–3 at the focus-gate `emit-frame!` boundary
  (explicit-focus + default-session-id fallback + background suppression, each
  with positive controls), and elisp ERT tests
  `psi-extension-ui-footer-updated-renders-retry-text-visibly` / `-replaces-` /
  `-clears-` cover stage 4 (Emacs `psi-emacs--projection-footer-text` rendering
  of activation/change/clear); (3) ¬mock/¬stub — the sole
  `with-redefs turn-runtime/execute-live-turn!` logic-boundary stub is a
  documented bounded exception with a confirmed provider-registry seam and a
  tracked migration follow-up (task 243). Candidate gap investigated (Emacs
  stage-4 retry rendering) found already covered. No genuinely-new defect.
- Non-compliance note: review run against an already-*closed* task
  (`munera/closed/242-…`, not the requested `munera/open/…` path).

## task-test-review session (9th pass, test-shaper) — outcome

- added 1 step (Slice 26): the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test` asserts
  retry-frame session-id *consistency* (three frames mutually equal) but never
  *correctness* against the driving `session-id`, so a stamping regression that
  mis-stamps all frames identically-but-wrong passes green — the exact
  design-stage-4 `emit.clj` session-id stamping the test characterizes.
- Non-compliance note: review run against an already-*closed* task
  (`munera/closed/242-…`, not the requested `munera/open/…` path); task closed
  at `58a16fd53` before this review.

## Slice 26 follow-up execution — outcome

- addressed 1 review step (Slice 26): strengthened the sibling
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test` session-id
  assertion to bind all three retry frames (activation/changed/clear) to the
  driving `session-id` (`(is (= session-id …))`), matching the focused
  sub-tests' `every? #(= session-id …)` correctness control. Consistency-only
  check (three frames mutually equal) replaced by correctness; a
  mis-stamped-but-consistent regression now fails.
- verified: `bb test --focus
  psi.rpc-prompt-test/rpc-prompt-provider-retry-state-publishes-footer-updated-test`
  green (8 assertions). clj-kondo clean, clj-paren-repair formatted.
- Slice 3 unchecked items (focused-session-broken branch) left as-is: they
  predate this review pass and were superseded by the background-only /
  working-as-intended diagnosis; owned by their branch, not this pass.
