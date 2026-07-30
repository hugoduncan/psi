# 242 — Retry backoff footer no longer visible in Emacs

## Goal

Restore (or explicitly confirm as correct) the Emacs UI footer notification
shown while an LLM request is waiting to be retried after a provider-boundary
backoff. Add end-to-end coverage that locks the behaviour so it cannot silently
regress again.

## Context

Observed regression: while an LLM request is waiting to be retried, the Emacs
footer used to show retry backoff text (e.g. `retry in 8s · remaining N/…`). It
no longer appears.

The retry-footer pipeline (traced, currently intact and unit-tested on each
half):

1. `turn-runtime/core.clj` `mark-active-retry!` writes `:retry {:active? true …}`
   into session data and pushes a `:retry-updated` event onto the turn
   `progress-queue`, then sleeps for the backoff.
2. `rpc/session/streams.clj` `footer-refresh-progress-event?` includes
   `:retry-updated` → calls `emit/emit-footer-updated!`.
3. `app-runtime/footer.clj` + `retry_display.clj` build `:status-line` from
   `:psi.agent-session/retry` only when `(:active? retry)`.
4. `emit.clj` stamps `:session-id` onto the `footer/updated` payload; Emacs
   `psi-events.el` `"footer/updated"` handler renders it via
   `psi-emacs--projection-footer-text`.

Prime suspect: commit `c4e8b86b8` ("241: emit RPC events only for the focused
session") added a structural focus gate in `rpc/events.clj`:

```clojure
(defn- focus-allows? [state data]
  (if (contains? data :session-id)
    (= (:session-id data)
       (or (rpc.state/focus-session-id state)
           (rpc.state/default-session-id state)))
    true))
```

`footer/updated` carries `:session-id`, so it is now session-scoped and dropped
unless it matches the connection's effective focus.

Reasoning boundary:
- For the **focused** single session, streaming deltas and start/end footer
  snapshots pass the same gate, so if the gate dropped the focused session all
  streaming would break too — not just retry. So the plain focused case should
  still work.
- For a **background / delegated child** session that retries while the user
  views another session, the retry footer is now suppressed. Previously it may
  have leaked into the visible footer. This may be the actual observed case and
  may be *intended* behaviour.

The distinguishing fact is unknown without reproduction: is the retrying request
in the focused session or a background/delegated one?

## Approach (to be refined in plan.md)

1. Reproduce with a forced retryable failure (e.g. 429 / bad key) and determine
   whether the retry footer disappears for the **focused** session or only for
   background/delegated sessions.
2. Add an end-to-end RPC-level test that drives a retry through the prompt path
   (`run-prompt-async!` / progress loop) and asserts a `footer/updated` frame
   carrying a `retry in …` `status-line` reaches `emit-frame!` for the focused
   session.
3. Based on (1):
   - If broken for the focused session → repair the focus-gate interaction so
     the retry `footer/updated` `:session-id` matches effective focus at retry
     time.
   - If only background sessions are affected → confirm as intended, document
     it, and (optionally) decide whether background retry state should surface
     anywhere (e.g. session-activity line) rather than the focused footer.

## Constraints

- Behaviour-preserving for the focus-gating design of task 241 (do not
  reintroduce cross-session event leakage).
- Follow the change chain: meta/spec/tests/code/doc coherence.
- Backend halves already have unit tests; the gap is end-to-end retry→footer
  emission coverage.

## Acceptance criteria

- An end-to-end test locks the focused-session retry→footer behaviour at the RPC
  emit boundary: it drives a provider-boundary retry in the focused session and
  asserts a `footer/updated` event whose `status-line` contains retry backoff
  text reaches the emit boundary. The required outcome is contingent on the
  diagnosis in Approach step 1:
  - If the focused session is the actual regression (footer suppressed), this
    test MUST be demonstrably failing before the fix and passing after
    (failing-then-passing), proving the repair.
  - If the focused case is working as intended (only background/delegated
    sessions are affected), no failing-then-passing sequence is required —
    instead the same test stands as a green regression-lock characterization
    test asserting the focused retry footer already emits correctly, paired with
    the recorded "working as intended" determination (see next criterion).
- The observed behaviour (focused vs background) is characterized and the fix
  or the "working as intended" determination is recorded in
  `implementation.md`.
- No regression to task 241 focus-gating invariants (existing focus-gate tests
  stay green).
- Emacs footer shows retry backoff text again for the focused session (if that
  was the broken case).
```