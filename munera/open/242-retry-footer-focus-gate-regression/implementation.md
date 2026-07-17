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
