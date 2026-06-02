# 206 — Plan

## Approach

Move widget-projection mutation watchdog timers from the module-global
`psi-widget-projection--mutation-timers` hash into buffer-local
`psi-emacs-state`, mirroring the existing `projection-notification-timers`
precedent end-to-end. The notification timer feature is the consistency
reference for every decision here.

Key decisions (all grounded in the current code):

1. **New struct field + initialization.** Add a struct field
   `projection-mutation-timers` to `cl-defstruct psi-emacs-state`
   (`psi-globals.el:49`), directly after `projection-notification-timers`.
   Initialize it as `(make-hash-table :test #'equal)` in
   `psi-emacs--initialize-state` (`psi-lifecycle.el:57`), alongside the
   notification-timers init. Key form stays `"ext-id/widget-id:node-key"`
   (`--timer-key`); buffer identity comes from the store living in
   `psi-emacs-state`, not from the key.

2. **Single store-resolution rule: explicit `state` argument.** Following
   `psi-emacs--cancel-notification-timer (state notification-id)`
   (`psi-projection.el:368`), the cancel/arm helpers resolve the store *solely*
   from a passed `state`; they never read dynamic `psi-emacs--state`.
   - `psi-widget-projection--cancel-mutation-timer (state tkey)` — resolve store
     via `(psi-emacs-state-projection-mutation-timers state)`, cancel + remhash.
   - `psi-widget-projection--arm-mutation-timer (state ext-id widget-id
     node-key timeout-ms)` — inline pre-cancel via `--cancel-mutation-timer
     state tkey`; capture `(current-buffer)` locally and thread `buffer` +
     `state` into the `run-at-time` callback args (mirroring
     `psi-emacs--schedule-notification-dismiss`, `psi-projection.el:410`);
     puthash into the passed `state`'s store.

3. **Timeout callback gains leading buffer/state params.**
   `psi-widget-projection--on-mutation-timeout (buffer state ext-id widget-id
   node-key timeout-ms)`. No-op unless `(buffer-live-p buffer)`; otherwise run
   inside `with-current-buffer buffer`, cancel/clear via `--cancel-mutation-timer
   state tkey`, then clear in-flight + call error-handler + upsert against that
   state. Because `psi-emacs--state` is `defvar-local` (`psi-globals.el:111`),
   `with-current-buffer buffer` rebinds it so the downstream `--get-lstate` /
   `--set-lstate` / `--call-error-handler` / `--upsert-projection-block` reads
   naturally target the originating buffer.

4. **Response callback targets the originating buffer.** In
   `psi-widget-projection--dispatch-mutation` (response callback at
   `psi-widget-projection.el:354`), capture `buffer`/`state` at dispatch time
   (synchronous closure over the dispatch site), guard with `buffer-live-p`,
   cancel via `--cancel-mutation-timer state tkey`, and clear in-flight + upsert
   inside `with-current-buffer buffer`. No read of dynamic `psi-emacs--state` for
   store resolution. Dead-buffer response is a no-op.

5. **Teardown + transcript reset cancel-all.** Add
   `psi-widget-projection--clear-mutation-timers (state)` — maphash cancel +
   clrhash — mirroring `psi-emacs--clear-notification-lifecycle`'s timer loop.
   Invoke it from `psi-emacs--teardown-buffer` (`psi-lifecycle.el:269`),
   alongside `psi-emacs--clear-notification-lifecycle`, and from
   `psi-emacs--reset-transcript-state` (`psi-lifecycle.el:392`) where
   notification timers are already cleared. Use `declare-function` in
   `psi-lifecycle.el` (mirrors the existing `psi-projection` declares).

6. **Remove the module-global defvar.** Delete
   `psi-widget-projection--mutation-timers` (`psi-widget-projection.el:73`) once
   all references move to the buffer-local store. No module-global mutable timer
   state remains (`unreachable > forbidden`).

7. **Tests.** Update existing arm/cancel/timeout/dispatch tests to the new
   explicit-`state` signatures, and add new tests for: killed-buffer cancels
   in-flight timers; two buffers don't share timer state for the same key;
   response/timeout while a *different* buffer is current target the originating
   buffer; response/timeout for a dead buffer is a no-op.

## Risks

- **Caller fan-out for signature change.** `--arm-mutation-timer`,
  `--cancel-mutation-timer`, `--on-mutation-timeout` change arity. Existing
  callers are confined to `psi-widget-projection.el` (arm at :349, cancels at
  :303/:356, timeout scheduled at :306) plus tests. Risk is mechanical; mitigated
  by grepping all references before/after and reloading.
- **Test harness coupling to the global defvar.** Several tests `let`-bind
  `psi-widget-projection--mutation-timers`. Removing the defvar breaks those
  binds; they must be rewritten to drive the buffer-local store via state.
- **`with-current-buffer` re-entrancy in callbacks.** The notification precedent
  already proves this pattern; low risk, but verify the timeout/response bodies
  use the *rebound* buffer-local `psi-emacs--state` (or the captured `state`)
  consistently to avoid touching the wrong buffer.
- **Transcript-reset ordering.** Ensure mutation-timer clear runs while
  `psi-emacs--state` is still valid in reset (place beside the existing
  notification clear at :392).

## Slice order

Vertical slices, each independently testable and committable:

1. **State plumbing** — add struct field + init + a no-op-safe
   `--clear-mutation-timers` helper; no behaviour change yet (global still used
   by arm/cancel). Verify init creates the hash; tests still green.
2. **Helper signatures → explicit state** — rework arm/cancel/timeout to resolve
   the store from a passed `state` (buffer-local), thread `buffer`/`state` into
   the scheduled callback, give the timeout its leading `buffer`/`state` params
   with `buffer-live-p` + `with-current-buffer`. Update affected tests.
3. **Response callback targeting** — capture `buffer`/`state` at dispatch,
   guard `buffer-live-p`, cancel/clear against the originating store. Update
   tests.
4. **Teardown + reset cancel-all** — wire `--clear-mutation-timers` into
   teardown and transcript reset; add killed-buffer + reset tests.
5. **Remove module-global defvar** — delete
   `psi-widget-projection--mutation-timers`; confirm no remaining references;
   final test sweep + lint + reload.
