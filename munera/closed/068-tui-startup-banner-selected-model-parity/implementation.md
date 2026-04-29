Implementation notes:
- Confirmed root cause in code: `render-banner` was fed from TUI-local `:model-name`, while footer/session model truth already lived behind `:footer-model-fn`.
- Changed banner rendering to accept full TUI state and derive the model line exactly from `[:footer/model :text]` returned by `(:footer-model-fn state)`.
- Removed `:model-name` from TUI init state; repo-wide TUI search now shows no remaining `:model-name` usage in `components/tui`.
- Initial task cut kept `make-init`/`start!` signatures stable to avoid widening the slice; follow-up cleanup has now removed the ignored `model-name` parameter from `make-init` / `start!` / `build-init`.
- Added focused view tests proving:
  - canonical footer model text wins over conflicting launch/init model name
  - banner output updates when the footer model source changes without rebuilding init state
  - default/effective model text still renders normally
- Follow-up cleanup updated app-runtime/demo/test call sites and added API cleanup tests proving the old launch-model argument shape is no longer accepted.
- Additional shaping follow-up added explicit argument validation at `make-init` / `start!` so old-shape misuse now fails fast with deliberate contract errors instead of indirect downstream exceptions.
- Verification:
  - initial task cut: `bb test:tui` → `214 tests, 719 assertions, 0 failures`
  - after follow-up cleanup: `bb test:tui` → `217 tests, 722 assertions, 0 failures`
  - after explicit contract validation: `bb test:tui` → `218 tests, 731 assertions, 0 failures`
- Scope remains intentionally limited: only the banner model line is canonical/live in this slice; prompt/skill/extension summaries remain startup snapshots.

Review note:
- Approved. Design and architecture fit are good.
- Follow-up completed: removed the misleading dead parameter from `make-init` / `start!` / `build-init` and added tests for the cleanup behavior.
- Minor shaping follow-up identified by review is now addressed: API misuse is rejected explicitly at the boundary with deliberate contract errors.
