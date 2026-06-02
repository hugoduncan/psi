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
