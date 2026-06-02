# 206 — Steps

## Slice 1 — State plumbing

- [x] Add `projection-mutation-timers` field to `cl-defstruct psi-emacs-state`
      in `psi-globals.el` (immediately after `projection-notification-timers`).
- [x] Initialize `:projection-mutation-timers (make-hash-table :test #'equal)`
      in `psi-emacs--initialize-state` (`psi-lifecycle.el:57`), beside the
      notification-timers init.
- [x] Add `psi-widget-projection--clear-mutation-timers (state)` in
      `psi-widget-projection.el`: wrap the whole body in an outer `(when state
      ...)`, then maphash `cancel-timer` over timer values guarded on
      `(timerp timer)` then `clrhash`, inner-guarded on `(hash-table-p timers)`
      — mirroring `psi-emacs--clear-notification-lifecycle`
      (`psi-projection.el:379`) exactly: outer `(when state ...)` so a nil
      `state` is a harmless no-op (P2). The helper owns the null guard; call
      sites pass bare `psi-emacs--state` (Slice 4).
- [x] Run `emacs-ui` tests; confirm still green (no behaviour change yet).
- [x] `clj-paren-repair`/lint the edited `.el` files; reload per post-commit
      reload guideline.
- [x] Commit: `⚒ 206: add buffer-local projection-mutation-timers field + clear helper`.

## Slice 2 — Helper signatures → explicit state

- [x] Change `psi-widget-projection--cancel-mutation-timer` signature to
      `(state tkey)`; resolve store via
      `(psi-emacs-state-projection-mutation-timers state)`; cancel + remhash;
      no read of dynamic `psi-emacs--state`.
- [x] Change `psi-widget-projection--arm-mutation-timer` signature to
      `(state ext-id widget-id node-key timeout-ms)`: inline pre-cancel via
      `(psi-widget-projection--cancel-mutation-timer state tkey)`; capture
      `(current-buffer)` and `state` locally; thread both into the `run-at-time`
      callback args ahead of `ext-id widget-id node-key timeout-ms`; puthash
      into the passed `state`'s store.
- [x] Change `psi-widget-projection--on-mutation-timeout` signature to
      `(buffer state ext-id widget-id node-key timeout-ms)`: no-op unless
      `(buffer-live-p buffer)`; otherwise wrap body in
      `(with-current-buffer buffer ...)`; cancel/clear via
      `(psi-widget-projection--cancel-mutation-timer state tkey)`; then clear
      in-flight lstate, call error-handler, and `--upsert-projection-block`.
- [x] Update arm call site in `--dispatch-mutation` (`psi-widget-projection.el:349`)
      to pass `psi-emacs--state` as the leading `state` arg.
- [x] Update existing tests `pwpt-arm-cancel-mutation-timer-roundtrip`,
      `pwpt-on-mutation-timeout-clears-in-flight`,
      `pwpt-on-mutation-timeout-calls-error-handler`,
      `pwpt-dispatch-mutation-arms-timer`
      to the new signatures: drive the buffer-local store via state instead of
      `let`-binding the global defvar; pass `buffer`/`state` to timeout calls
      (live buffer + valid `state`, asserting the post-change behaviour holds).
- [x] Repurpose existing test `pwpt-on-mutation-timeout-noop-when-no-state`
      (`psi-widget-projection-test.el:542`) into the dead-buffer no-op case
      (P3): rename to reflect the new pivot (e.g.
      `pwpt-on-mutation-timeout-noop-when-buffer-dead`), drive it by
      `kill-buffer` then invoking `--on-mutation-timeout` with that dead
      `buffer` + a valid `state`, and assert a harmless no-op (store untouched,
      no error) — replacing the old `psi-emacs--state nil` pivot since the
      post-change no-op turns on `(buffer-live-p buffer)`, not dynamic state.
      This is the single dead-buffer-no-op test for the timeout path; do NOT
      additionally add a separate dead-buffer test (the repurposed one is it).
- [x] Run tests; lint; reload.
- [x] Commit: `⚒ 206: resolve mutation-timer store from explicit state + thread buffer into timeout`.

## Slice 3 — Response callback targeting

- [x] In `--dispatch-mutation` response lambda (`psi-widget-projection.el:354`),
      capture `buffer` (`(current-buffer)`) and `state` (`psi-emacs--state`) at
      dispatch time in the enclosing `let*`.
- [x] Rewrite the response callback to: guard `(buffer-live-p buffer)`; cancel via
      `(psi-widget-projection--cancel-mutation-timer state tkey)`; clear in-flight
      lstate + `--upsert-projection-block` inside `(with-current-buffer buffer ...)`;
      no read of dynamic `psi-emacs--state` for store resolution.
- [x] Update existing test `pwpt-dispatch-mutation-cancels-timer-on-response`
      (`psi-widget-projection-test.el:565`) to the response-targeting rework
      (P1): drive the buffer-local store via `state` instead of `let`-binding
      the global defvar, so it exercises cancel against the originating
      buffer's store. Slice 3 owns this migration (it reworks the response
      callback), so Slice 5's defvar deletion + "remove leftover let-binds"
      finds no remaining `let`-bind here.
- [x] Add test: response arriving while a *different* buffer is current
      cancels/clears the originating buffer's store, not the current buffer's.
- [x] Add test: response for a dead originating buffer is a no-op (store of a
      live buffer untouched, no error).
- [x] Run tests; lint; reload.
- [x] Commit: `⚒ 206: target originating buffer in mutation dispatch response callback`.

## Slice 4 — Teardown + transcript reset cancel-all

- [x] Add `(declare-function psi-widget-projection--clear-mutation-timers "psi-widget-projection" (state))`
      in `psi-lifecycle.el` (beside existing `psi-projection` declares).
- [x] Call `(psi-widget-projection--clear-mutation-timers psi-emacs--state)` in
      `psi-emacs--teardown-buffer` (`psi-lifecycle.el:269`), alongside
      `psi-emacs--clear-notification-lifecycle`.
- [x] Call `(psi-widget-projection--clear-mutation-timers psi-emacs--state)` in
      `psi-emacs--reset-transcript-state` (`psi-lifecycle.el:392`), beside the
      existing notification clear.
- [x] Add test: killing a psi buffer with an in-flight widget mutation timer
      cancels and clears that timer (no scheduled watchdog remains).
- [x] Add test: transcript reset (`/new`) clears the buffer's widget mutation
      timers.
- [x] Add test: two live psi buffers with the same `ext-id/widget-id:node-key`
      do not share/interfere with each other's timers (each store independent).
- [x] Run tests; lint; reload.
- [x] Commit: `⚒ 206: cancel widget mutation timers on teardown and transcript reset`.

## Slice 5 — Remove module-global defvar

- [x] `git grep psi-widget-projection--mutation-timers` — confirm only the defvar
      (and any remaining test binds) reference it.
- [x] Delete `defvar psi-widget-projection--mutation-timers` (`psi-widget-projection.el:73`).
- [x] Remove any leftover `let`-binds of the global from tests.
- [x] Final `git grep` confirms zero references to the global.
- [x] Full `emacs-ui` test sweep green; `.el` lint clean
      (`clj-paren-repair`/byte-compile, matching Slice 1 — this task edits only
      `.el` files, so the Clojure `clj-kondo` linter does not apply); reload `.el`.
- [x] Commit: `⚒ 206: remove module-global widget mutation-timers store`.

## Plan ambiguity review follow-ups (ψ)

- [x] P1 — Assign the sixth existing test. `pwpt-dispatch-mutation-cancels-timer-on-response`
      (`psi-widget-projection-test.el:565`) `let`-binds the global defvar and
      exercises the response-cancel path, but no slice lists it for update. Decide
      which slice migrates it to drive the buffer-local store via `state` (Slice 3
      is the natural home, since it reworks the response callback) and add it to
      that slice's "update existing tests" set, so Slice 5's defvar deletion +
      "remove leftover let-binds" leaves no broken/orphaned test.
- [x] P2 — Specify the `--clear-mutation-timers` null-`state` guard. Steps Slice 1
      pins only `(hash-table-p timers)`; the mirrored
      `psi-emacs--clear-notification-lifecycle` wraps its whole body in
      `(when state ...)`, and Slice 4 calls the helper with bare `psi-emacs--state`
      (no `(when psi-emacs--state)` wrapper, unlike sibling teardown calls). Decide
      and pin whether the helper must internally guard `(when state ...)` (matching
      the precedent) or whether each call site must wrap with `(when
      psi-emacs--state ...)`, so a nil-state teardown cannot error on
      `(psi-emacs-state-projection-mutation-timers nil)`. Update Slice 1/Slice 4.
- [x] P3 — Resolve the `pwpt-on-mutation-timeout-noop-when-no-state` remapping.
      Slice 2 lists this test (`:542`, asserts a harmless no-op when
      `psi-emacs--state` is nil, relying on the old `(when psi-emacs--state)`
      guard) for "update to new signatures," but the post-change no-op pivots on
      `(buffer-live-p buffer)` not dynamic state, and Slice 2 separately ADDS a
      dead-buffer no-op test. Specify the disposition: repurpose this one into the
      dead-buffer case (and drop the separately-added duplicate), retain it as a
      distinct nil/`state` guard with explicit `buffer`/`state` args + assertion,
      or delete it. Update Slice 2's test list to one unambiguous outcome.

## Plan/steps inconsistency review follow-ups (ψ)

- [x] N1 — Reconcile the final-sweep lint tool with the per-`.el` lint guidance.
      Slice 1 (`steps.md` lint step) lints the edited `.el` files with
      `clj-paren-repair`/lint, but Slice 5 says "`clj-kondo`/lint clean".
      `clj-kondo` is the Clojure linter and does not lint Emacs Lisp `.el`
      files; this task edits only `.el` files (`psi-globals.el`,
      `psi-lifecycle.el`, `psi-widget-projection.el`,
      `psi-widget-projection-test.el`). Replace Slice 5's `clj-kondo` with the
      `.el`-appropriate lint (`clj-paren-repair`/byte-compile/`elisp` lint),
      matching Slice 1, so both slices name the same linter for the same files.

## Acceptance verification (final)

- [x] Confirm against design.md acceptance criteria: buffer-local store; single
      explicit-`state` store-resolution rule (no site reads dynamic
      `psi-emacs--state` for the store); teardown cancels all; arm threads
      `buffer`/`state`; timeout callback leading params + `buffer-live-p` no-op +
      `with-current-buffer`; response callback originating-buffer targeting +
      dead-buffer no-op; two-buffer independence; transcript-reset clear;
      existing behaviour preserved; tests cover all + existing tests pass.

## Implementation review follow-ups (ψ)

- [x] R1 — Reconcile `--on-mutation-timeout`'s live-guard with the cited
      precedent. The callback guards only `(buffer-live-p buffer)`
      (`psi-widget-projection.el:341`), but the
      `psi-emacs--schedule-notification-dismiss` lambda it mirrors guards
      `(and (buffer-live-p buffer) st)` (`psi-projection.el:415`). Either add
      the `state`-non-nil conjunct so the guard matches the precedent the
      design repeatedly invokes ("mirror `schedule-notification-dismiss`"), or
      record the intentional divergence in design.md/implementation.md so the
      "mirror the precedent" claim is accurate. (Benign in practice — the
      captured `state` is non-nil when the buffer was live at arm and the inner
      paths null-guard — so this is a consistency item, not a correctness fix.)
