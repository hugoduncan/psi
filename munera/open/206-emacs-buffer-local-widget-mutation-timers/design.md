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
  RPC response or when the watchdog fires.

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
  write that buffer-local store instead of the module-global `defvar`.
- Cancel and clear all widget mutation timers in `psi-emacs--teardown-buffer`,
  alongside the existing timer-cancellation calls.
- Decide and apply the timeout-callback's buffer-targeting so the watchdog
  resolves the correct buffer/state explicitly (as notification timers do via a
  captured `buffer`/`state`) rather than relying on incidental current buffer.
- Tests proving: (a) a killed buffer cancels its in-flight widget timers; (b) two
  buffers no longer share timer state for the same key; (c) the existing
  arm/cancel-on-response and timeout behaviours still hold.

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
- `psi-emacs--teardown-buffer` cancels and clears all widget mutation timers for
  the buffer being killed; after kill, none of that buffer's widget watchdogs
  remain scheduled.
- The watchdog timeout callback resolves its target buffer/state explicitly and
  is a no-op when that buffer is dead, with no dependence on the incidental
  current buffer.
- Two live psi buffers with mutations sharing the same
  `ext-id/widget-id:node-key` do not interfere with each other's timers.
- Transcript reset (`/new`, reconnect) clears the buffer's widget mutation
  timers.
- Existing widget mutation behaviour (arm on dispatch, cancel on response, fire
  on timeout with error-handler invocation) is preserved.
- Tests cover the above; existing `emacs-ui` tests still pass.
