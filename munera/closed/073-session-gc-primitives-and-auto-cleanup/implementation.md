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

# Code-shaper review note

Three findings:

**1. `descendants-of-in` — `into … [id]` should be `conj`**
`(into (vec (mapcat post-order children)) [id])` wraps a scalar in `[id]` solely to
satisfy `into`. `(conj (into [] (mapcat post-order children)) id)` is direct.

**2. `cancel-owned-schedules!` — double-read fix was partial**
`sd` is used for `owned-schedule-ids` but `cancel-owned-schedules!` still calls
`get-session-data-in` internally. Accept `sd` as a parameter to complete the fix
and close the stale-read window.

**3. `close-session-tree-in!` — `root-id` → `session-id`**
`root-id` borrows tree-algorithm framing. All other public `-in!` functions use
`session-id`; the tree root is a session-id and callers name it as such.

# Follow-on steps

- [x] Fix `workflows.clj:delete-working-memory!` — no-op: grep truncated the multiline
  call; the 3-arg form was already present (`store, (:env reg), session-id`).

- [x] Tighten `close-session-in!` — unified double `get-session-data-in` read:
  `(if-not ...)` guard + separate `owned-schedule-ids` read replaced with a single
  `(if-let [sd (ss/get-session-data-in ctx session-id)]` binding.

- [ ] `descendants-of-in` — replace `(into (vec (mapcat post-order children)) [id])`
  with `(conj (into [] (mapcat post-order children)) id)`.

- [ ] `cancel-owned-schedules!` — add `sd` parameter, use it instead of calling
  `get-session-data-in` internally; update `close-session-in!` to pass `sd`.

- [ ] `close-session-tree-in!` — rename parameter `root-id` → `session-id`;
  update `descendants-of-in` call site and docstring accordingly.
