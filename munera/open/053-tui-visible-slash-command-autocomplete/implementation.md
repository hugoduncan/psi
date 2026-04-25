Implementation notes:
- Created for GitHub issue #24: `tui: Add autocomplete for / commands`
- Issue URL: https://github.com/hugoduncan/psi/issues/24
- Initial implementation expectation:
  - existing slash-command autocomplete state and acceptance behavior already exist
  - the missing user-visible piece is TUI rendering of the open autocomplete menu

2026-04-25 — visible TUI autocomplete menu slice
- Implemented prompt-adjacent autocomplete rendering in `components/tui/src/psi/tui/app/render.clj`.
- Added a small render-local helper to cap visible suggestions at 5 and keep the selected row within the visible window.
- Rendered a simple `Suggestions` block beneath the prompt input when autocomplete candidates are open.
- Chose a textual selected-row marker (`▸ `) so the selected suggestion remains assertable in ANSI-stripped output.
- Kept autocomplete behavior generic over the existing shared TUI autocomplete state, while proving slash-command behavior specifically for issue #24.
- No update-path or candidate-generation changes were required; existing slash autocomplete behavior remained intact.

Focused verification:
- `clojure -M:test --focus psi.tui.app-projection-test --focus psi.tui.app-input-selector-test`
- result: `17 tests, 48 assertions, 0 failures`

Broader relevant verification:
- `clojure -M:test --focus psi.tui.app-projection-test --focus psi.tui.app-input-selector-test --focus psi.tui.app-view-runtime-test`
- result: `31 tests, 85 assertions, 0 failures`

2026-04-25 — tmux integration proof slice
- Added `send-text!` to tmux harness: sends literal characters without pressing Enter (uses `-l` flag, no trailing Enter key).
- Added `send-key!` to tmux harness: sends a named tmux key string (e.g. "Down", "Escape") without literal text or Enter.
- Added `default-autocomplete-suggestions-marker` ("Suggestions") and `default-autocomplete-selected-marker` ("▸ ") constants matching the render markers from `render-prompt-autocomplete`.
- Implemented `run-slash-autocomplete-scenario!`:
  - boot → ready marker
  - `send-text! "/"` → wait for `"Suggestions"` marker with step-timeout
  - assert `"▸ "` selected-row marker present immediately after Suggestions appears
  - `send-key! "Down"` → wait for `"▸ "` still present (selection moved, marker stable)
  - `send-key! "Escape"` + 200ms pause → `send-line! "/quit"` → wait for java exit
  - failure modes: `:autocomplete-suggestions-timeout`, `:autocomplete-selected-marker-missing`, `:autocomplete-post-down-marker-missing`, `:quit-timeout`
- Refactored existing basic-scenario test into a shared `assert-scenario-result` helper to avoid duplication.
- Added `^:integration tui-tmux-slash-autocomplete-scenario-test` wired through the shared helper.
Live run outcome — `mise exec tmux -- bb clojure:test:integration`:
- First run failed: `:autocomplete-suggestions-timeout` — root cause: `launcher-command` resolved to the installed `psi` binary (canonical) which is an older release without the autocomplete render feature. The basic boot/help/quit scenario passed with the installed binary but autocomplete was absent.
- Fix: added `worktree-launch-command` to the harness; it prefers `bb` (repo-local current-worktree code) over the installed `psi` binary. The autocomplete scenario uses `worktree-launch-command` as its default. `run-basic-help-quit-scenario!` continues to use `launcher-command` (prefers installed binary) since it tests a stable surface.
- Second run: `6 tests, 17 assertions, 0 failures` — all integration scenarios green.

Manual probe confirmed intermediate behaviour: 2 seconds after sending `/`, the pane showed:
  ```
  刀: /
  Suggestions
  ▸ /cancel-job
    /delegate
    /delegate-reload
    /exit
    /gh-bug-triage
  ```
