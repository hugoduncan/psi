# Implementation Notes

## Architecture-fit review (ψ)

Reviewed design.md for fit with AGENTS.md, doc/architecture.md (`emacs-ui` owns
"local widget/view state"), VSM adapter boundary.

Strong fit overall:
- Moving mutation timers into buffer-local `psi-emacs-state` keeps frontend-local
  state in the adapter's per-buffer state — matches `emacs-ui owns local
  widget/view state`.
- Adopting the `projection-notification-timers` precedent satisfies
  `one_way` ∧ `consistent(code)`.
- "No module-global mutable timer state remains" realizes
  `shape: unreachable > forbidden` for orphaned timers.
- Removing the global hash increases cross-buffer `orthogonality` (robust).
- Scope correctly stays inside the adapter; excludes dispatch/RPC/backend (VSM
  layering respected).

Actionable misfit (1):
- The design's "resolve target buffer/state explicitly" requirement names only
  the *timeout* callback. The `--dispatch-mutation` RPC **response** callback
  (psi-widget-projection.el:354) also reads the dynamic `psi-emacs--state` and
  calls `--cancel-mutation-timer tkey`. Post-change the response path must
  target the originating buffer's buffer-local store too, mirroring the
  notification precedent's captured `buffer`/`state` + `buffer-live-p` guard
  (psi-projection.el:415). Otherwise consistency/orthogonality goals are only
  partially met and a stale-buffer response could touch the wrong store.

## Architecture-fit follow-up — resolved (ψ)

Applied the response-callback buffer-targeting requirement into design.md:
- Scope: explicit buffer-targeting now required for **both** callbacks (timeout
  watchdog + `--dispatch-mutation` response), each capturing originating
  `buffer`/`state` + `buffer-live-p` guard, mirroring
  `psi-emacs--schedule-notification-dismiss`.
- Constraints: neither callback may dereference dynamic `psi-emacs--state` to
  locate the store; both operate against the captured buffer/state.
- Acceptance: added a criterion for the response callback targeting the
  originating buffer (no cross-buffer mutation, dead-buffer no-op).
- Scope/Tests: added (d) cross-buffer-current and (e) dead-buffer no-op cases for
  both response and timeout paths.
- Why: noted both callbacks share the same buffer-targeting hazard.

Verified against precedent at psi-projection.el:410
(`psi-emacs--schedule-notification-dismiss`): captures `(current-buffer)` +
`state`, guards `(buffer-live-p buffer)`, runs in `with-current-buffer`.
No code changes (design-only task).

## Ambiguity review (ψ)

Grounded design against real code: `psi-widget-projection.el` arm
(:300), cancel (:310), timeout (:317), dispatch-mutation response (:336/:354);
struct `psi-globals.el:49`; init `psi-lifecycle.el:32`; teardown `:269`;
transcript reset `:371`; notification precedent `psi-projection.el:368-423`.

Confirmed (resolves a potential concern, NOT actionable): `psi-emacs--state`
is `defvar-local` (psi-globals.el:111), so a callback's `with-current-buffer
buffer` rebinds it and the downstream `--get-lstate` / `--call-error-handler`
/ `--upsert-projection-block` reads then naturally target the originating
buffer's state — the design's captured-buffer approach is self-consistent for
the *post-cancel* body.

One NEW actionable ambiguity (B1, design-steps.md): the design mandates
captured `buffer`/`state` + `buffer-live-p` for the two **callbacks**, but is
silent on the helper signatures and the **arm path**. Today
`--cancel-mutation-timer` takes only `tkey` and reads the global hash, and it
is called from THREE contexts: (1) inside `--arm-mutation-timer` as
pre-cancel-before-arm (psi-widget-projection.el:303), (2) the response callback
(:356), (3) the timeout callback (:321 via `remhash`). Post-change the cancel
helper must locate a buffer-local store, so its new signature (e.g.
`(state tkey)` or `(buffer tkey)`) is unspecified. The arm path runs while the
originating buffer is current (dynamic `psi-emacs--state` is valid), whereas the
callbacks must use captured state — the design's "neither callback may
dereference `psi-emacs--state`" rule does NOT say whether `--arm` and its
inline pre-cancel may use dynamic state or must also thread captured state.
A shared cancel helper called from both dynamic-current and captured-buffer
contexts with an unstated store-resolution rule is an actionable mechanism
ambiguity (`one_way → singular(solution)`).

Not raised (plan-level / non-actionable): exact new struct field name + clear
helper name (shape fixed by the `projection-notification-timers` mirror;
naming is a plan concern); whether the widget clear helper also resets widget
lstates/data (design clearly scopes it to "cancel and clear timers" only).

## Ambiguity follow-up — B1 resolved (ψ)

Resolved the helper-signature / arm-path store-resolution ambiguity by adopting
the notification precedent's explicit-`state` pattern. Grounded in
`psi-emacs--cancel-notification-timer (state notification-id)`
(psi-projection.el:368), which resolves the store from the *passed* `state`,
never from dynamic `psi-emacs--state`.

Single store-resolution rule applied across all three cancel/arm call sites:
the store is always resolved from an explicitly passed `state` argument; the
helpers never dereference dynamic `psi-emacs--state`. Sites differ only in which
`state` they pass:
- arm + inline pre-cancel (psi-widget-projection.el:300/303): pass the
  then-current dynamic `psi-emacs--state` (captured at the synchronous call
  boundary while the originating buffer is current).
- response callback (:354) and timeout callback (:317): pass the captured
  originating `state` after a `buffer-live-p` guard, per
  `psi-emacs--schedule-notification-dismiss`.

design.md updates:
- Scope: pinned helper signatures `--cancel-mutation-timer (state tkey)` and
  `--arm-mutation-timer (state ext-id widget-id node-key timeout-ms)`.
- Constraints: replaced the callback-only "neither callback may dereference
  `psi-emacs--state`" rule with a single store-resolution rule covering all
  three sites (arm, inline pre-cancel, both callbacks).
- Acceptance: added a criterion that the helpers resolve the store solely from
  the passed `state` and no site reads dynamic `psi-emacs--state` for store
  resolution.

This makes the cancel helper safely shared between dynamic-current and
captured-buffer contexts (`one_way → singular(solution)`). No code changes
(design-only task).
