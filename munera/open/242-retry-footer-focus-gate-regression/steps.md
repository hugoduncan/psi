# Steps — 242 Retry backoff footer no longer visible in Emacs

## Slice 1 — Diagnose

- [ ] Read `components/rpc/src/psi/rpc/events.clj` (`focus-allows?`,
      `emit-event!`) and `components/rpc/src/psi/rpc/session/emit.clj`
      (`emit-footer-updated!`) to confirm the current gate + session-id
      stamping behaviour.
- [ ] Build a minimal retry reproduction: force a retryable provider-boundary
      failure (429 / bad key via nullable provider) so `mark-active-retry!`
      fires and a `:retry-updated` progress event flows.
- [ ] Probe the focused-session case: with the retrying session as effective
      focus, check whether the retry `footer/updated` frame reaches
      `emit-frame!`.
- [ ] Probe the background case: with focus on another session, confirm the
      retry `footer/updated` frame is suppressed (expected by task-241 gate).
- [ ] Record the diagnosis (focused-broken vs background-only) in
      `implementation.md`.

## Slice 2 — End-to-end regression-lock test

- [ ] Add an RPC-level test (in `components/rpc/test/psi/rpc_events_test.clj`
      or a sibling namespace, following the existing focus-gate test pattern)
      that drives a provider-boundary retry in the focused session through the
      progress path and asserts a `footer/updated` frame with a retry-backoff
      `:status-line` (e.g. matching `retry in`) reaches `emit-frame!`.
- [ ] Make the test deterministic: zero/minimal backoff, drain the
      progress-queue synchronously, no wall-clock sleeps in assertions.
- [ ] Run the new test and record its initial result (failing vs passing) in
      `implementation.md` — this is the AC1 branch evidence.

## Slice 3 — Fix or determination (branch on diagnosis)

If focused session is broken:
- [ ] Identify the minimal repair so the retry `footer/updated` `:session-id`
      matches effective focus at retry time (do not weaken `focus-allows?`).
- [ ] Implement the fix; new E2E test transitions failing → passing.
- [ ] Record the root cause and fix in `implementation.md`.

If background-only (working as intended):
- [ ] Record the "working as intended" determination in `implementation.md`,
      with the evidence from Slice 1.
- [ ] Note (without implementing) whether/where background retry state could
      surface (e.g. session-activity line) as a possible follow-up task.

## Slice 4 — Verify and record

- [ ] Run existing task-241 focus-gate tests; confirm green (no cross-session
      leakage regression).
- [ ] Run the full test suite (`bb test`); confirm green.
- [ ] Lint changed files (`clj-kondo`) and repair formatting
      (`clj-paren-repair`) as needed.
- [ ] Update `implementation.md` with final diagnosis, outcome, and any scope
      notes; check doc coherence (no user-facing doc change expected unless
      the focused footer behaviour changed — if it did, add a CHANGELOG entry
      under Fixed).
- [ ] Commit with a symbol-tagged message (⊘/⚒) referencing task 242.
