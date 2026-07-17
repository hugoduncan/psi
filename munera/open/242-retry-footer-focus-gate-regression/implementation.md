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
