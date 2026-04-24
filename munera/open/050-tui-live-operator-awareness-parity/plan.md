# Plan

## Approach

Four vertical slices, each independently committable. The footer-model-fn slice is the only one with code changes; the other three are proof-only. Order the code change first because it touches the most files and existing tests — get it green before adding new tests.

## Decisions

- Footer rendering uses a single code path: `footer-model-fn` is the sole source of footer model data. The current `footer-data` / `footer-model-from-data` local path in `psi.tui.app.render` is removed entirely.
- Existing footer tests migrate from `query-fn`-based footer data to `footer-model-fn` that returns `footer-model-from-data` results directly. This is cleaner — tests control the model shape without mocking EQL.
- Notification lifecycle test uses backdated `:created-at` (not real time delays), following `extension_ui_test.clj` precedent.
- Widget refresh test uses a mutable atom behind `ui-read-fn` to simulate snapshot changes across ticks.
- Frontend-action cancel feedback test uses a handler that returns `{:type :text :message ...}` to prove the message reaches `:messages`.

## Risks

- The footer-model-fn migration touches `app-runtime`, `support/build-init`, `render`, and existing footer tests. If any test depends on the `query-fn` → `footer-data` path for footer-specific assertions, it will break. Mitigation: run the full TUI test suite after the migration step before proceeding.
- The `update-tick-state` path is private in `psi.tui.app`. The widget refresh and notification tests need to trigger it indirectly through `make-update` with a benign message. If the tick doesn't fire for the chosen message type, the test won't prove the refresh. Mitigation: use a window-size message, which always triggers `update-tick-state`.

## Steps

### Step 1: footer-model-fn — wire the closure and migrate build-footer-lines

1. In `components/app-runtime/src/psi/app_runtime.clj`, add `:footer-model-fn` to the TUI opts map:
   `(fn [] (footer/footer-model ctx @tui-focus*))`.

2. In `components/tui/src/psi/tui/app/support.clj`, thread `:footer-model-fn` from opts into the TUI state map in `build-init`. Default to `(constantly {})` when not supplied — this produces a minimal empty footer for tests that don't care about footer content. The default lives here, not in the render path.

3. In `components/tui/src/psi/tui/app/render.clj`:
   - Remove `footer-data` function.
   - Remove `footer-query` re-export.
   - Rewrite `build-footer-lines` to call `(:footer-model-fn state)` as its sole source of the footer model. No nil-guard — `footer-model-fn` is always present (defaulted in `build-init`).
   - Extract `:session-activity-line` from the footer model and append it to the footer lines when present (dim style, same as status-line).

4. Update `components/tui/test/psi/tui/app_view_runtime_test.clj`:
   - Migrate `view-renders-default-footer-from-query-test` and `view-renders-footer-using-session-display-name-test` to supply `:footer-model-fn` instead of `query-fn` for footer data.
   - Each test constructs a footer model via `footer/footer-model-from-data` with its test data map and passes it as the return value of `footer-model-fn`.
   - Add a new test that supplies a footer model containing `:session-activity-line` and asserts it appears in the rendered footer.

5. Run full TUI test suite. Fix any breakage before proceeding.

Commit: `⚒ 050: wire footer-model-fn and render session-activity-line`

### Step 2: background-job widget refresh proof

1. In `components/tui/test/psi/tui/background_jobs_test.clj`, add a test:
   - Create a mutable atom holding ui-snapshot A (with a background-job widget containing "job-alpha [running]").
   - Init TUI state with `make-init` using `ui-read-fn` that derefs the atom.
   - Render the view and assert "job-alpha [running]" is visible.
   - Swap the atom to ui-snapshot B (widget containing "job-beta [pending-cancel]" instead).
   - Create `update-fn` via `make-update`, send a window-size message to trigger `update-tick-state`.
   - Render the resulting state and assert "job-beta [pending-cancel]" is visible and "job-alpha [running]" is gone.

Commit: `⚒ 050: prove background-job widget refresh cycle`

### Step 3: notification rendering lifecycle proof

1. Create `components/tui/test/psi/tui/notification_render_test.clj`:
   - Create a real `ui-state` atom via `ui/create-ui-state`.
   - Create `ui-read-fn` returning `(ui/snapshot ui-state-atom)`.
   - Create `ui-dispatch-fn` that handles `:session/ui-dismiss-expired` by calling `(ui/dismiss-expired! ui-state-atom)` with default max-age (5000ms), and `:session/ui-dismiss-overflow` by calling `(ui/dismiss-overflow! ui-state-atom)`.
   - Init TUI state with `make-init` using these fns.
   - Call `(ui/notify! ui-state-atom "test-ext" "Alert: disk full" :warning)`.
   - Send a window-size message through `update-fn` to trigger a tick. The fresh notification survives dismiss (created < 5s ago). The tick refreshes `ui-snapshot`.
   - Render and assert "Alert: disk full" appears in the view.
   - Backdate the notification's `:created-at` to 0 in the atom (e.g. `swap!` over `:notifications` to set `:created-at` to 0).
   - Send another window-size message to trigger another tick. dismiss-expired now finds the notification > 5s old and dismisses it.
   - Render and assert "Alert: disk full" is gone.

Commit: `⚒ 050: prove notification rendering lifecycle`

### Step 4: frontend-action cancel feedback proof

1. In `components/tui/test/psi/tui/app_update_runtime_test.clj`, add a test:
   - Init TUI state with `frontend-action-handler-fn!` that returns `{:type :text :message (:ui.result/message action-result)}` for any action-result (mimics the real cancel path in `tui-frontend-actions/handle-action-result`).
   - Create a model-picker action via `ui-actions/model-picker-action`.
   - Open the frontend-action dialog via `app-update/handle-dispatch-result` with `{:type :frontend-action :request-id "req-cancel" :ui/action action}`.
   - Send Escape via `update-fn` to cancel.
   - Assert the resulting state's `:messages` contains an assistant message with text "Cancelled select-model."

Commit: `⚒ 050: prove frontend-action cancel feedback visibility`
