Goal: raise the TUI to parity with Emacs for live operator-awareness during day-to-day use by improving visibility of background activity, projected statuses, and shared action-result feedback.

Intent:
- make the TUI as operationally legible as Emacs for normal interactive work
- build on the TUI's already-strong footer/status baseline rather than redoing it
- close the gap in live feedback: what is happening now, what just succeeded/failed/cancelled, and what asynchronous work is active

Context:
- TUI already renders a useful shared footer with:
  - worktree/branch
  - session display name
  - usage/cost/context stats
  - provider/model/thinking summary
  - extension statuses (via `status-line` from footer model)
- TUI already renders visible notifications from canonical projection data
- TUI already renders background-jobs widget content from `ui-snapshot` below the editor
- TUI already calls `dismiss-expired!` and `dismiss-overflow!` via `ui-dispatch-fn` on every tick
- TUI frontend-action cancel/fail messages already flow through `handle-dispatch-result → {:type :text}` which appends an assistant message

Problem:
The TUI has the wiring for most live-awareness surfaces but has concrete gaps in what it renders and what is proven:

1. **Session activity line missing**: the shared footer model produces `:footer/lines :session-activity-line` (a compact "sessions: running X · waiting Y" summary for multi-session contexts) but the TUI `build-footer-lines` does not render it. Emacs does. This means a TUI operator has no visibility into child/sibling session activity.

   The root cause is twofold:
   - The TUI calls `footer-model-from-data d {:cwd (:cwd state)}` which does not pass `:context-sessions`. Without `:context-sessions`, `footer-session-activity-line` always returns `nil`.
   - Even if the data were available, `build-footer-lines` does not extract or render `:session-activity-line`.

   The RPC/Emacs path gets this data by calling `footer/footer-model ctx session-id`, which internally calls `ss/list-context-sessions-in` and enriches each session with `:runtime-state` from the statechart phase.

2. **Background-job widget refresh unproven**: the TUI renders background-job widgets from `ui-snapshot` snapshots, but there is no proof that the live refresh cycle works — i.e. that when the widget content changes in `ui-state`, the TUI's next tick picks up the new snapshot via `ui-read-fn` and the view reflects the change. The snapshot-based test (`background_jobs_test.clj`) proves static rendering; the live update path is unproven.

3. **Notification lifecycle unproven**: the TUI renders `visible-notifications` from `ui-snapshot` and dispatches dismiss events on every tick, but there is no proof that a notification appears in the rendered view, persists, and then disappears after expiry. The `extension_ui_test` proves the data-layer lifecycle (notify → visible → dismiss-expired); the rendering-layer lifecycle is unproven.

4. **Frontend-action feedback unproven**: cancel/fail messages from shared frontend actions flow through `apply-frontend-action-result → handler → handle-dispatch-result → {:type :text :message ...}` and appear as assistant messages. This works, but the feedback path is not explicitly proven — there is no test that shows a cancelled `/model` or `/thinking` action produces a visible "Cancelled select-model." message in the transcript.

Concepts:
- **session activity line** — compact multi-session status summary already produced by the shared footer model; requires `:context-sessions` with `:runtime-state` enrichment
- **footer-model-fn** — a closure provided by `app-runtime` that calls `footer/footer-model ctx session-id` directly, giving the TUI the same enriched footer model that the RPC path uses; replaces the current `footer-data` → `footer-model-from-data` path entirely
- **widget refresh cycle** — `ui-state mutation → ui-read-fn snapshot → render` loop that makes widget changes visible across ticks
- **notification lifecycle** — appear → visible → expired → dismissed rendering cycle
- **action-result feedback** — cancel/fail messages from shared frontend-action flows appearing as visible transcript entries

Scope:
In scope:
- render the session-activity-line in the TUI footer when present
- supply the TUI with context-sessions data (including runtime-state enrichment) so the session-activity-line is populated
- prove the background-job widget refresh cycle end-to-end (widget content change → next tick → visible change in rendered view)
- prove the notification rendering lifecycle (appear in rendered view → disappear from rendered view after expiry)
- prove frontend-action cancel feedback visibility in the transcript
- preserve existing footer/status/widget rendering strengths

Out of scope:
- redesigning background-job semantics in the backend
- inventing TUI-only diagnostic semantics where shared backend projection data already exists
- notification animation, stacking, or priority logic beyond what `ui.state` already provides
- broad UX experimentation unrelated to practical operator awareness
- failed frontend-action feedback (`:failed` status only occurs when model resolution fails for an unknown provider+id; this is an edge case not worth a dedicated test in this slice)

Design constraints:
- preserve canonical backend ownership of statuses, widgets, notifications, and action-result semantics
- prefer consuming shared projection/update surfaces over building TUI-local status reconstruction
- the session-activity-line data must come from the same enriched path that the RPC adapter uses, not from a TUI-local reconstruction
- **single code path for footer rendering** — `build-footer-lines` must use `footer-model-fn` as its only source of footer model data; no fallback to the current `footer-data` → `footer-model-from-data` path; the current local query + local model-from-data path is removed entirely

Approach:

**Rejected alternatives for session-activity-line data supply:**

- **Expand the EQL footer query with a context-sessions join.** The resolver provides context-sessions but without `:runtime-state`. That enrichment (statechart phase → "running"/"waiting") is done imperatively in `footer/footer-model`, not through EQL. Without runtime-state the activity line can list sessions but cannot label them by activity, which defeats the purpose. Adding a `:runtime-state` resolver would push app-runtime-level logic (statechart phase mapping) into the resolver layer where it doesn't belong.

- **Have the TUI call `footer/footer-model` directly.** This requires `ctx` and `session-id` in the render path, which the TUI render layer does not and should not have. The render layer operates on pure state maps.

The chosen approach — `footer-model-fn` closure injection — keeps the render layer pure, reuses the existing enriched `footer/footer-model` function, and follows the established closure-injection pattern.

**1. Session activity line (code change)**

The TUI currently calls `footer-model-from-data d {:cwd (:cwd state)}` in `build-footer-lines`, using `footer-data` which queries EQL via `query-fn`. This path does not supply `:context-sessions` and cannot provide `:runtime-state` enrichment.

Solution: replace this with a single `footer-model-fn` code path.

Concrete changes:
- In `app-runtime`, add a `:footer-model-fn` to the TUI opts map: `(fn [] (footer/footer-model ctx @tui-focus*))`.
- In `psi.tui.app.support/build-init`, thread `:footer-model-fn` from opts into the TUI state. When not supplied, default to `(constantly {})` — this produces a minimal empty footer. The default lives in `build-init`, not in the render path. `build-footer-lines` always calls `footer-model-fn` unconditionally.
- In `psi.tui.app.render`, remove `footer-data` and the local `footer-model-from-data` call. `build-footer-lines` calls `(:footer-model-fn state)` as its sole source of footer model data. No nil-guard in the render path — `footer-model-fn` is always present.
- In `build-footer-lines`, extract `:session-activity-line` from the footer model and append it to the footer lines when present (same dim style as the status line).
- Remove the `footer-query` re-export from `psi.tui.app.render` since the TUI no longer queries footer data directly.
- Update existing footer tests (`app_view_runtime_test.clj`) to supply `footer-model-fn` instead of `query-fn` for footer data. Tests construct footer models via `footer-model-from-data` with test data — this is cleaner because the test controls exactly what the footer model contains without mocking EQL queries.

**2. Background-job widget refresh (proof)**

Write a test in `background_jobs_test.clj` that:
- creates a mutable atom backing `ui-read-fn` (returns different snapshots on successive calls)
- initializes TUI state with `make-init` using a `ui-read-fn` that derefs the atom
- renders the initial view and asserts widget content A is visible
- swaps the atom to a snapshot with widget content B
- runs a tick cycle by calling `(make-update ...)` with a benign message (e.g. a window-size message, which triggers `update-tick-state`)
- renders again and asserts widget content B is visible and content A is gone

This proves the live refresh cycle through the same path the real TUI uses.

**3. Notification lifecycle (proof)**

Write a test in a new `notification_render_test.clj` that:
- creates a real `ui-state` atom via `ui/create-ui-state`
- creates a `ui-read-fn` that returns `(ui/snapshot ui-state-atom)`
- initializes TUI state with `make-init` using this `ui-read-fn` and a `ui-dispatch-fn` that handles `:session/ui-dismiss-expired` by calling `(ui/dismiss-expired! ui-state-atom)` with default max-age (5000ms) and `:session/ui-dismiss-overflow` by calling `(ui/dismiss-overflow! ui-state-atom)`
- calls `ui/notify!` to add a notification
- triggers a tick cycle via a window-size message (which refreshes `ui-snapshot` and calls dismiss — the fresh notification survives because it was created < 5s ago)
- renders the view and asserts the notification message text appears
- backdates the notification's `:created-at` to 0 in the atom to simulate time passage (same technique as `extension_ui_test.clj` line 110 — directly manipulate the notification map in the atom)
- triggers another tick cycle (dismiss-expired now finds the notification > 5s old and dismisses it)
- renders the view and asserts the notification message text is gone

This avoids real time delays by directly manipulating `:created-at`, following the established pattern in `extension_ui_test.clj`. The default 5000ms max-age means fresh notifications survive dismiss on the first tick, and backdated ones are dismissed on the second tick.

**4. Frontend-action cancel feedback (proof)**

Write a test in `app_update_runtime_test.clj` that:
- initializes TUI state with a `frontend-action-handler-fn!` that returns `{:type :text :message (:ui.result/message action-result)}` for cancelled actions (mimicking the real `tui-frontend-actions/handle-action-result` cancel path)
- opens a frontend-action dialog via a `:frontend-action` dispatch result (e.g. model picker)
- sends an Escape key to cancel
- asserts the resulting state's `:messages` vector contains an assistant message with "Cancelled select-model."

This proves the end-to-end feedback path from cancel → action-result → handler → dispatch-result → visible message.

Architecture:
- No new architecture. All four items consume existing shared surfaces.
- The `footer-model-fn` follows the established closure-injection pattern used by `query-fn`, `ui-read-fn`, `ui-dispatch-fn`, `session-selector-fn`, etc.
- The `footer-model-fn` replaces the current `footer-data` + `footer-model-from-data` local path entirely — one code path, no fallback.
- The session-activity-line follows the existing `build-footer-lines` pattern of extracting lines from the footer model.
- The proofs follow existing TUI test patterns (init state → simulate events/ticks → assert rendered view or state).

Acceptance:
- TUI footer renders the session-activity-line when multiple sessions are active, showing the same "sessions: running X · waiting Y" format as Emacs
- Focused proof covers:
  - session-activity-line appears in footer when footer model contains it
  - background-job widget content updates are visible in the rendered view after a tick cycle
  - notification appears in rendered view, then disappears from rendered view after simulated expiry
  - cancelled frontend-action (model picker) produces a visible "Cancelled select-model." assistant message
- Existing footer/status/widget rendering remains intact (no regressions)
- Full TUI test suite stays green

Why this task is small and clear:
- one data-supply change (footer-model-fn closure, replacing the local query path)
- one rendering addition (session-activity-line in build-footer-lines)
- three proof additions (widget refresh, notification lifecycle, action cancel feedback)
- all consume existing shared surfaces with no backend changes
