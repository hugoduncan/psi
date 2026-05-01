# Review note

Implementation matches design and architecture. No new patterns introduced.

Three issues found:

**1. `workflows.clj` — `delete-working-memory!` uses wrong 2-arg form (latent bug)**
`workflows.clj:128` calls `(sp/delete-working-memory! store session-id)` — 2 args.
The Java interface requires 2 explicit params (3 Clojure args: store, sc-env, session-id).
Works only because `workflows.clj` is never hot-reloaded from source in the REPL.
Our implementation correctly uses the 3-arg form.

**2. `close-session-in!` — session data read twice**
Guard `(if-not (ss/get-session-data-in ...))` and `owned-schedule-ids` binding both call
`get-session-data-in`. Pre-existing pattern. Could be unified with a single `if-let`.

**3. `infer-session-title` — exception-path leak**
If code throws between `remember-helper-session!` and the close call, the child session
leaks. Low risk: `run-agent-loop-in-session` catches all `Throwable` internally.

# Follow-on steps

- [ ] Fix `workflows.clj:delete-working-memory!` — change 2-arg to 3-arg form:
  `(sp/delete-working-memory! (::sc/working-memory-store (:env reg)) (:env reg) session-id)`
  Verify workflows tests pass after change.

- [ ] Tighten `close-session-in!` — unify double `get-session-data-in` read:
  Replace `(if-not ...)` guard + separate `owned-schedule-ids` read with a single
  `(if-let [sd (ss/get-session-data-in ctx session-id)]` binding.
