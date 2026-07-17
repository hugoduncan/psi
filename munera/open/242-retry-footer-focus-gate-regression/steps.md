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
- [ ] Commit with a symbol-tagged message (⊘/⚒) referencing task 242.
