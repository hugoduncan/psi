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
