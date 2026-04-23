Implementation notes:
- Planning slice completed for harness/test placement.
- Existing repository structure already provides the intended execution surface and initial placement:
  - harness: `components/tui/test/psi/tui/test_harness/tmux.clj`
  - baseline scenario: `components/tui/test/psi/tui/tmux_integration_harness_test.clj`
  - suite wiring: Kaocha `:integration` via `^:integration` metadata in `tests.edn`
  - task wiring: `bb clojure:test:integration` in `bb.edn`
- This placement is preferred over adding a separate bespoke runner because it keeps the harness TUI-local, keeps the scenario discoverable with other TUI tests, and uses the project’s existing slow/integration execution path.

Implemented follow-on refinements:
- changed the harness default launch command to the canonical TUI entrypoint: `clojure -M:psi --tui`
- added explicit CI detection in the harness via standard CI environment variables
- added pure proof coverage for the tmux preflight/environment policy and pane-targeting behavior in `components/tui/test/psi/tui/test_harness/tmux_test.clj`
- replaced the previous hard-coded `:0.0` targeting assumption with explicit primary-pane discovery via `#{pane_id}`, while preserving `session:0.0` as a fallback if pane-id lookup fails
- added explicit tmux preflight result shaping:
  - `{:status :ok}` when tmux is available
  - `{:status :skipped ... :warning ...}` for local missing-tmux runs
  - `{:status :failed ... :error-message ...}` for CI missing-tmux runs
- changed the integration test wrapper to consume the scenario result directly instead of using a false-green `(is true ...)` branch for missing tmux
- preserved result diagnostics with `:reason`, `:error-message`, and `:pane-snapshot` on failure
- updated CI workflow to install `tmux` and run `bb clojure:test:integration`
- updated `TESTING.md` to document the integration test path and local skip behavior
- fixed a harness regression introduced during pane-targeting refactoring: the single-arg `capture-pane` overload was recursively self-calling for map inputs, which caused a stack overflow in the integration test path; this is now corrected

Observed validation outcome:
- local environment without `tmux` on PATH exercises the local preflight skip path as intended
- targeted formatting/lint for the new/updated TUI harness files is clean
- full unit suite is green after the changes (`1311 tests, 10180 assertions, 0 failures`)
- live tmux validation was run in this session using `mise exec tmux -- ...`
  - `tmux -V` succeeded
  - a manual black-box tmux smoke check passed end-to-end:
    - TUI boot reached the ready marker
    - `/help` rendered the expected stable help marker
    - `/quit` exited cleanly
  - the shell trap then reported `can't find session` during cleanup because the session had already ended, which is consistent with successful quit and teardown rather than a lingering-session failure
- a full `mise exec tmux -- clojure -M:test --focus integration` run remains blocked by unrelated pre-existing failures in `workflow-loader-reload-runtime-test`
- after fixing the harness recursion bug, the tmux integration test is no longer the source of the integration-suite failure; the remaining failures are still the unrelated workflow-loader reload failures

Decisions recorded:
- missing-tmux policy is implemented as local skip + warning, CI hard failure
- pane targeting prefers an explicitly discovered tmux `pane_id`; `session:0.0` remains a pragmatic fallback when pane-id lookup is unavailable
- existing harness/test code was refined in place rather than replaced wholesale

Follow-on notes:
- the manual live tmux smoke result provides strong evidence that the baseline scenario is viable in a real terminal boundary
- once the unrelated workflow-loader integration failures are fixed, rerunning the full integration suite should provide final suite-level confirmation for this task
