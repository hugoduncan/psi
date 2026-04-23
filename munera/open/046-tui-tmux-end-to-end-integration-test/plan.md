Approach:
- implement the smallest useful black-box harness around `tmux`
- keep the first slice closely aligned to `spec/tui-tmux-integration-harness.allium`
- prefer reusable test helpers over embedding `tmux` shell details directly in one large test body
- make environment handling explicit: local missing-tmux skips with warning; CI missing-tmux fails clearly

Implementation shape:
1. Inspect current test layout and choose the execution home deliberately
- identify the current split between fast unit/component tests and slower integration-style paths
- choose a home where the new coverage is discoverable and intentionally runnable without burdening the default fast path
- prefer a test namespace plus a thin runner/task hook over one-off shell scripting if that keeps the behavior easier to maintain
- keep harness code colocated with the test boundary rather than spreading `tmux` details into unrelated TUI test namespaces
- placement decision after inspection:
  - keep the black-box tmux harness in `components/tui/test/psi/tui/test_harness/tmux.clj`
  - keep the baseline integration scenario in `components/tui/test/psi/tui/tmux_integration_harness_test.clj`
  - keep execution on the existing Kaocha `:integration` suite via `^:integration` metadata
  - keep intentional invocation through `bb clojure:test:integration`
- rationale:
  - the harness is TUI-specific and belongs with TUI test support rather than in global `test-support/`
  - the scenario is already discoverable alongside other TUI tests while still excluded from fast/unit runs by meta-based suite selection
  - no new bespoke runner is needed because the repository already has an integration suite and bb task for slow tests

2. Define a small harness API before filling in command details
- settle on a minimal helper surface that matches the spec’s responsibilities, such as:
  - `tmux-available?`
  - `assert-tmux-available!` or equivalent CI-aware preflight
  - `unique-session-name`
  - `start-session!`
  - `pane-id`
  - `send-line!` / `send-keys!`
  - `capture-pane`
  - `wait-until`
  - `kill-session!`
- keep the helpers black-box and string/process oriented rather than coupling them to internal TUI implementation details
- keep timeout values configurable but give the scenario sensible defaults from the spec

3. Implement preflight and environment policy first
- detect whether `tmux` is on PATH
- detect CI using the project’s existing CI environment conventions if available; prefer conventional env signals over bespoke flags
- encode the policy explicitly:
  - local + no `tmux` -> skip with a warning/explanatory message
  - CI + no `tmux` -> clear environment/setup failure
- make this behavior visible in test output so failures are diagnosable without reading the code

4. Implement core tmux session primitives
- start a detached session with a unique test-owned name
- discover the primary pane id deterministically
- launch `clojure -M:psi --tui` in that pane
- implement line/key sending helpers suitable for `/help` and `/quit`
- implement pane capture helpers with both raw and ANSI-normalized forms if helpful for diagnostics vs assertions

5. Implement polling, readiness, and output matching helpers
- add a small `wait-until` helper with timeout and polling interval controls
- use explicit marker-based matching rather than broad snapshot matching:
  - ready marker for boot success
  - stable help-output marker for `/help`
- normalize ANSI for matching so rendering escape sequences do not make assertions brittle
- keep polling diagnostics good enough that timeouts say what was being waited for

6. Implement cleanup and failure evidence together
- ensure tmux cleanup runs under success, assertion failure, and unexpected exception paths
- capture pane output on failure before cleanup so the evidence survives session teardown
- make failure messages distinguish at least: startup timeout, help marker timeout, quit timeout, and missing-tmux environment problems
- verify cleanup is idempotent enough that failed or partial startup does not cause secondary cleanup failures to obscure the primary problem

7. Implement the baseline end-to-end scenario
- preflight the environment
- start the tmux session and launch the TUI
- wait for the prompt-ready marker
- send `/help` and wait for the stable help-output marker
- send `/quit`
- assert the TUI process exits cleanly within timeout
- keep the scenario provider-independent and free of remote-service assumptions

8. Wire the scenario into intentional execution surfaces
- add or update the appropriate runner/task path so this test can be invoked deliberately as slow/integration coverage
- avoid silently placing it on the default fast path unless that is already the project convention
- if a dedicated bb task improves discoverability, add one with clear documentation that it depends on `tmux`
- make CI invocation straightforward so the environment expectation is obvious

9. Verify stability and record decisions
- run the focused test path locally in both cases when possible:
  - with `tmux` available
  - with `tmux` unavailable or simulated unavailable to confirm skip behavior
- verify CI-enforcement logic is as simple and explicit as possible
- confirm no stray tmux sessions remain after success/failure
- record final namespace placement, helper shape, execution wiring, markers, timeout choices, and missing-tmux policy in `implementation.md`

Key decisions to preserve:
- local missing-tmux is a skip with warning; CI missing-tmux is a failure
- baseline assertions are marker-based and ANSI-normalized
- the first slice proves only boot/help/quit viability
- harness helpers are reusable, black-box, and local to the integration boundary

Risks / watchpoints:
- flaky waits caused by startup timing or unstable output markers
- brittle assertions tied too tightly to terminal formatting or full-screen redraw details
- leaving stray tmux sessions behind on exceptions/failures
- accidentally coupling the scenario to provider/network behavior instead of local TUI behavior
- choosing an execution path that makes the test either too hidden or too expensive for normal feedback loops
