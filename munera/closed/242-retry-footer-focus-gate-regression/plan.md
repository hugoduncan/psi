# Plan — 242 Retry backoff footer no longer visible in Emacs

## Approach

Diagnosis-first, then a branch-dependent fix, locked by one end-to-end test.

1. **Diagnose (Slice 1).** Force a retryable provider-boundary failure
   (e.g. 429 / bad key stub) and trace the retry `footer/updated` event through
   `emit-event!`/`focus-allows?` in `components/rpc/src/psi/rpc/events.clj`.
   Determine whether suppression occurs for the **focused** session or only
   for background/delegated sessions. This can be done at the REPL or via a
   draft of the E2E test itself (preferred — the test doubles as the probe).
2. **Lock with an E2E test (Slice 2).** Add an RPC-level test (pattern:
   `components/rpc/test/psi/rpc_events_test.clj` focus-gate characterization
   tests) that drives a provider-boundary retry in the focused session through
   the prompt/progress path (`mark-active-retry!` → progress-queue
   `:retry-updated` → `footer-refresh-progress-event?` →
   `emit-footer-updated!`) and asserts a `footer/updated` frame whose
   `:status-line` contains retry backoff text reaches `emit-frame!`.
3. **Branch on diagnosis (Slice 3).**
   - **Focused session broken:** the Slice-2 test must fail first. Repair the
     focus-gate interaction so the retry `footer/updated` `:session-id`
     matches effective focus at retry time (fix likely in the session-id
     stamped by `emit.clj` or the effective-focus resolution in
     `focus-allows?` — not by weakening the gate). Test goes green.
   - **Background-only (working as intended):** the Slice-2 test passes
     immediately and stands as a green regression-lock characterization test.
     Record the determination in `implementation.md`; optionally note (not
     implement) where background retry state could surface.
4. **Verify invariants (Slice 4).** Run existing focus-gate tests (task-241
   invariants) and the full suite; record diagnosis + outcome in
   `implementation.md`.

Key decisions:
- Test at the `emit-frame!` boundary — that is where the regression manifests
  and where the design's acceptance criterion is stated.
- Do not weaken/remove `focus-allows?`; any fix must preserve
  no-cross-session-leakage (task-241).
- Failing-then-passing is **contingent** on the focused-session branch, per
  resolved AC1.

## Risks

- **Reproduction ambiguity:** if the forced-failure setup doesn't match the
  originally observed conditions, we may diagnose the wrong branch. Mitigate
  by testing both focused and background configurations in the probe.
- **Test flakiness:** retry path involves sleeps/backoff and a progress-queue
  loop; use minimal/zero backoff and deterministic queue draining in the test.
- **Boundary reach:** driving `run-prompt-async!` end-to-end at RPC level may
  require nullable provider infrastructure; if full E2E is impractical, keep
  the test as close to end-to-end as feasible while still crossing the
  focus-gate (`emit-event!`) — record any narrowing in implementation.md.
- **Fix location uncertainty (focused-broken branch):** session-id vs
  focus-resolution timing; keep the fix minimal and gate-preserving.

## Slice order

1. Diagnose focused vs background suppression.
2. E2E retry→footer emission test at the emit boundary (focused session).
3. Branch-dependent fix (repair) or determination (working as intended).
4. Invariant verification + implementation.md record + docs coherence check.
