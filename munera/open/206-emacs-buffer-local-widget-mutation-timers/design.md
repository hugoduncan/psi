# 206 — Buffer-local widget mutation timers

## Intent

Move the Emacs widget-projection mutation watchdog timers from a module-global
hash table into buffer-local `psi-emacs-state`, and cancel them during buffer
teardown, so that killing a psi buffer leaves no orphaned emacs-side timers and
no cross-buffer timer state.

## Why

`psi-emacs--teardown-buffer` (`psi-lifecycle.el`, on `kill-buffer-hook`) is the
single place that releases emacs-side resources when a psi buffer is closed. It
already cancels every other timer the frontend owns (stream watchdog, projection
notification timers) and clears all markers, render state, and the
`psi-emacs--state-by-buffer` entry.

The one frontend timer it does **not** reach is the widget-projection mutation
watchdog. Those timers live in a module-global hash table:

- `psi-widget-projection--mutation-timers` (`psi-widget-projection.el`) — a
  single `defvar` hash keyed by the string `"ext-id/widget-id:node-key"`.
- Armed by `psi-widget-projection--arm-mutation-timer` when a widget mutation is
  dispatched; cancelled by `psi-widget-projection--cancel-mutation-timer` only on
  RPC response (`--dispatch-mutation` response callback) or when the watchdog
  fires. Both the response callback and the timeout callback read the dynamic
  `psi-emacs--state` to locate the store and lstate they mutate, so both inherit
  the same buffer-targeting hazard once the store moves buffer-local.

This produces three problems when a buffer is killed mid-mutation:

1. **Orphaned timer.** Teardown never cancels the in-flight watchdog, so it keeps
   running after the buffer is gone.
2. **Non-deterministic firing.** `psi-widget-projection--on-mutation-timeout`
   guards on the buffer-local `psi-emacs--state`, but when the timer fires there
   is no guarantee the originating buffer is current — `psi-emacs--state` is
   whatever the then-current buffer holds (or nil). Behaviour depends on
   incidental buffer focus.
3. **Cross-buffer collision / persistence.** Because the hash is global and the
   key carries no buffer identity, entries from a killed buffer persist and can
   collide with another live psi buffer using the same
   `ext-id/widget-id:node-key`.

The root cause is that per-buffer mutable state lives outside `psi-emacs-state`.
Every other per-buffer concern in this frontend is keyed per buffer; this one is
the outlier. The fix is to make the timer state buffer-local, consistent with the
existing `projection-notification-timers` precedent, so per-buffer cleanup
becomes structurally possible and `unreachable > forbidden` for orphaned timers.

## Scope

In scope:

- Add a buffer-local timer store to `psi-emacs-state` (mirroring the existing
  `projection-notification-timers` field: a hash table created in
  `psi-emacs--initialize-state`).
- Rework `psi-widget-projection.el` timer arming/cancelling/timeout to read and
  write that buffer-local store instead of the module-global `defvar`. The
  cancel and arm helpers take the target `state` explicitly (mirroring
  `psi-emacs--cancel-notification-timer (state notification-id)`):
  - `psi-widget-projection--cancel-mutation-timer (state tkey)` — resolves the
    timer store from the *passed* `state`, never from the dynamic
    `psi-emacs--state`. This single signature serves all three call sites; only
    the `state` argument differs per site.
  - `psi-widget-projection--arm-mutation-timer (state ext-id widget-id node-key
    timeout-ms)` — arms against the passed `state`'s store and performs its
    inline pre-cancel via `--cancel-mutation-timer` with that same `state`.
    Arm is the sole scheduler of `--on-mutation-timeout`; mirroring
    `psi-emacs--schedule-notification-dismiss`, it captures the originating
    `buffer` (`(current-buffer)`) and `state` at arm time and threads **both**
    into the scheduled `run-at-time` callback args, so the deferred timeout
    callback can guard `buffer-live-p` and operate against the originating
    buffer rather than the incidental current one.
  - `psi-widget-projection--on-mutation-timeout (buffer state ext-id widget-id
    node-key timeout-ms)` — gains leading `buffer`/`state` params (threaded in
    by arm). It is a no-op unless `(buffer-live-p buffer)`, then runs inside
    `with-current-buffer buffer`, cancels/clears against the passed `state`'s
    store (via `--cancel-mutation-timer state tkey`), and invokes the
    error-handler / `--upsert-projection-block` against that buffer's state.
- Cancel and clear all widget mutation timers in `psi-emacs--teardown-buffer`,
  alongside the existing timer-cancellation calls.
- Decide and apply explicit buffer-targeting for **both** callbacks that touch
  the buffer-local timer store, rather than relying on the incidental current
  buffer:
  - The timeout watchdog callback
    (`psi-widget-projection--on-mutation-timeout`).
  - The `--dispatch-mutation` RPC **response** callback
    (`psi-widget-projection.el:354`), which currently reads the dynamic
    `psi-emacs--state` and calls `--cancel-mutation-timer` against the global
    hash. Post-change it must cancel/clear against the *originating* buffer's
    buffer-local timer store and lstate.
  Both callbacks must capture the originating `buffer`/`state` at arm/dispatch
  time and guard with `buffer-live-p` before mutating that buffer's store
  (mirroring `psi-emacs--schedule-notification-dismiss`), so a callback that
  arrives while another buffer is current cannot mutate the wrong buffer's
  store, and is a no-op when the originating buffer is dead.
- Tests proving: (a) a killed buffer cancels its in-flight widget timers; (b) two
  buffers no longer share timer state for the same key; (c) the existing
  arm/cancel-on-response and timeout behaviours still hold; (d) a response (and a
  timeout) arriving while a *different* buffer is current cancels/clears the
  originating buffer's store, not the current buffer's; (e) a response/timeout
  for a dead buffer is a no-op.

Out of scope:

- Any change to widget rendering, the EQL mutation dispatch contract, or the
  RPC layer.
- The unrelated observation that `kill-buffer-query-functions` /
  `psi-emacs--confirm-kill-buffer-p` is not explicitly removed in teardown
  (harmless; buffer-local hook dies with the buffer). May be noted but is not
  required by this task.
- Backend / Clojure changes.

## Constraints

- Follow the existing `projection-notification-timers` shape and lifecycle as the
  consistency reference (creation in `initialize-state`, cancel-all in a
  `clear-*` helper, invoked from teardown and transcript reset).
- Single store-resolution rule for all three cancel/arm call sites: the store is
  always resolved from an **explicitly passed `state`** argument, never read from
  the dynamic `psi-emacs--state` inside the helper. The three sites differ only
  in *which* `state` they pass:
  - The inline pre-cancel inside `--arm-mutation-timer` and the arm itself run
    synchronously while the originating buffer is current, so they pass the
    then-current dynamic `psi-emacs--state` as the `state` argument (captured at
    the call boundary). The helpers themselves remain dynamic-state-free.
  - The `--dispatch-mutation` RPC **response** callback and the timeout watchdog
    callback both follow the `psi-emacs--schedule-notification-dismiss`
    precedent: capture the originating `buffer`/`state` at arm/dispatch time,
    guard with `buffer-live-p`, and pass that captured `state` to
    `--cancel-mutation-timer`/the clear path — operating inside
    `with-current-buffer buffer` (or against the captured `state` directly).
    Concretely, the originating `buffer`/`state` reach the *timeout* callback
    by `--arm-mutation-timer` capturing `(current-buffer)` + `state` and
    threading both into the scheduled `run-at-time` callback args (the
    precedent threads `(current-buffer)`, `state`, `notification-id`); the
    timeout callback's signature therefore leads with `buffer`/`state`
    (`--on-mutation-timeout (buffer state ext-id widget-id node-key
    timeout-ms)`). The response callback is a synchronous closure over the
    dispatch site, so it closes over the dispatch-time `buffer`/`state`
    directly.
  Because the cancel/arm helpers resolve the store solely from their `state`
  argument, no call site (arm, inline pre-cancel, response callback, or timeout
  callback) ever dereferences the dynamic `psi-emacs--state` to locate the store
  it touches.
- Per `psi-emacs--reset-transcript-state` semantics, also clear widget mutation
  timers on transcript reset (`/new`, reconnect) where notification timers are
  already cleared, since the widgets they watch are discarded there too.
- The timer key may keep its `"ext-id/widget-id:node-key"` form *within* a
  buffer-local store; buffer identity comes from the store living in
  `psi-emacs-state`, not from the key.
- No module-global mutable timer state remains after the change
  (`unreachable > forbidden`).
- Emacs Lisp coding conventions of the existing `emacs-ui` component; reload `.el`
  edits per the post-commit reload guideline.

## Acceptance criteria

- `psi-widget-projection--mutation-timers` is no longer a module-global mutable
  store; widget mutation timers are held in buffer-local `psi-emacs-state`.
- The cancel/arm helpers resolve the timer store solely from an explicitly
  passed `state` argument; no helper or call site (arm, inline pre-cancel,
  response callback, timeout callback) reads the dynamic `psi-emacs--state` to
  locate the store it cancels/arms/clears.
- `psi-emacs--teardown-buffer` cancels and clears all widget mutation timers for
  the buffer being killed; after kill, none of that buffer's widget watchdogs
  remain scheduled.
- `psi-widget-projection--arm-mutation-timer` captures the originating `buffer`
  (`(current-buffer)`) and `state` at arm time and threads both into the
  scheduled `run-at-time` callback args, mirroring
  `psi-emacs--schedule-notification-dismiss`.
- The watchdog timeout callback
  (`psi-widget-projection--on-mutation-timeout (buffer state ext-id widget-id
  node-key timeout-ms)`) receives its target `buffer`/`state` as leading
  params, is a no-op unless `(buffer-live-p buffer)`, otherwise runs inside
  `with-current-buffer buffer` and cancels/clears against the passed `state`'s
  store — with no dependence on the incidental current buffer.
- The `--dispatch-mutation` RPC response callback likewise cancels/clears
  against the *originating* buffer's buffer-local timer store and lstate
  (captured `buffer`/`state` + `buffer-live-p` guard), with no dependence on the
  incidental current buffer; a response arriving while another buffer is current
  does not mutate that other buffer's store, and a response for a dead buffer is
  a no-op.
- Two live psi buffers with mutations sharing the same
  `ext-id/widget-id:node-key` do not interfere with each other's timers.
- Transcript reset (`/new`, reconnect) clears the buffer's widget mutation
  timers.
- Existing widget mutation behaviour (arm on dispatch, cancel on response, fire
  on timeout with error-handler invocation) is preserved.
- Tests cover the above; existing `emacs-ui` tests still pass.
