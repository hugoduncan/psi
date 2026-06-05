# 211 — Plan

## Approach

Fix retry-backoff visibility by making active retry state drive the existing
app-runtime footer projection through the Emacs-visible event path. The primary
path is `footer/updated`; Emacs must render the app-runtime-projected footer text
and must not synthesize retry wording or maintain a local countdown model.

Key decisions:

1. **Prove the current event boundary before changing it.** Start by tracing the
   existing retry-state mutation/publication path and the RPC projection listener
   that emits footer events. Confirm whether retry activation/change/clear causes
   `footer/updated` today. The implementation slice should keep the discovered
   trigger proof in backend/RPC tests, not only in notes.

2. **Keep app-runtime as footer owner.** Retry wording stays in
   `psi.app-runtime.retry-display/retry-status-text` and
   `psi.app-runtime.footer/footer-model-from-data`. The fix should make the
   app-runtime footer projection publish/render at the right times, not add an
   Emacs-only formatter or retry display.

3. **Make retry changes invalidate/publish the footer.** If retry state changes
   are missing from the projection invalidation path, add the minimal backend/RPC
   trigger so active retry, materially changed retry, and clear/terminal retry
   states publish an Emacs-visible footer refresh. Prefer changing the
   backend/RPC/app-runtime projection boundary so all footer consumers stay
   coherent.

4. **Emacs handles projected footer text only.** Existing `footer/updated`
   handling in `components/emacs-ui/psi-events.el` should remain the normal
   rendering path: store `psi-emacs-state-projection-footer` from the event data
   and upsert the projection block. If investigation proves a `session/updated`
   fallback is necessary, it may only trigger or reuse delivery of the
   app-runtime-owned footer projection; it must not build retry text in Emacs.

5. **Event-driven freshness.** The visible footer updates on retry activation,
   backend-published retry changes, and clear. No Emacs-local per-second timer is
   added for a single unchanged backoff window.

6. **Tests distinguish state storage from visible rendering.** Add backend/RPC
   coverage proving retry state causes footer publication, and Emacs-visible
   coverage asserting the rendered projection/footer text after active, changed,
   and cleared `footer/updated` events. Existing tests that only prove nested
   retry data is preserved in session state are not sufficient acceptance proof.

## Risks

- **Root cause may be in projection invalidation rather than formatting.** The
  formatter and footer model already include retry state, so changing wording is
  likely a distraction. Mitigation: first add/inspect backend/RPC trigger proof
  and only modify the missing publication boundary.
- **Event-path coupling.** `footer/updated` is used by multiple adapters/tests;
  any payload or emission change must preserve existing footer fields and
  session-id matching.
- **False-positive Emacs tests.** Tests can pass by asserting stored retry data
  rather than visible footer text. Mitigation: assert rendered buffer/projection
  footer content after event handling.
- **Fallback temptation.** A `session/updated` workaround could create a second
  retry display model. Mitigation: use fallback only if `footer/updated` is truly
  unavailable, and test that it triggers/reuses app-runtime-owned footer
  projection text.
- **Time-sensitive text.** `retry in Ns` depends on projection time. Tests should
  use controlled/static payload text or controlled time in backend formatter
  tests; Emacs visibility tests should assert the provided projected text, not a
  ticking countdown.

## Slice order

Vertical slices, each independently verifiable:

1. **Trace and lock backend/RPC trigger gap** — identify the retry state update
   path and the footer projection listener/emission path; add a failing or
   regression-style backend/RPC test proving retry activation publishes an
   Emacs-visible footer update with app-runtime retry text.
2. **Publish footer on retry activation/change/clear** — implement the minimal
   invalidation/emission fix so retry activation, materially changed retry state,
   and retry clear produce the expected `footer/updated` projection.
3. **Emacs-visible `footer/updated` rendering coverage** — add focused Emacs UI
   tests for active retry text rendered in the buffer/footer, changed retry text
   replacing the previous text, and cleared retry removing stale text.
4. **Optional `session/updated` fallback only if required** — if investigation
   proves `footer/updated` cannot be used for some path, add the constrained
   fallback that triggers/reuses app-runtime footer projection delivery, plus
   separate Emacs-visible fallback coverage. Skip this slice if the primary
   `footer/updated` path covers activation/change/clear.
5. **Final verification and docs/changelog assessment** — run focused backend/RPC
   and Emacs UI tests, relevant full suites/lint, confirm existing app-runtime
   footer formatting tests remain valid, and update user-facing docs/changelog
   only if the shipped user-visible behaviour or documented event surface changes.
