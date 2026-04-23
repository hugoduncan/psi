Goal: add a tmux-backed end-to-end integration test for the psi TUI that proves the real terminal-boundary boot/help/quit path.

Context:
- The repository already contains a behavioral spec for this work in `spec/tui-tmux-integration-harness.allium`.
- That spec defines both the harness responsibilities and the first intended scenario:
  - launch psi TUI in tmux
  - wait for the prompt-ready marker
  - send `/help`
  - assert a stable help-output marker
  - send `/quit`
  - assert clean exit
- Existing TUI tests are primarily unit/component proof. They validate internal behavior, but they do not prove that the full TUI boots, renders, accepts terminal input, and exits correctly through a real terminal boundary.
- The project already acknowledges a slower integration-test tier that may depend on external tools such as tmux.

Problem:
- There is currently no black-box proof that `clojure -M:psi --tui` works correctly when driven as an interactive terminal application.
- Unit/component coverage cannot catch some important failure modes, such as:
  - boot failures caused by real terminal startup conditions
  - prompt-readiness regressions
  - command-input delivery regressions across the terminal boundary
  - shutdown/quit regressions that leave the process hanging
  - ANSI/rendering noise that obscures assertion strategy
- Without a reusable terminal-boundary harness, future TUI integration scenarios will either be missing or reinvent ad hoc shell logic.

Intent:
- prove that the psi TUI can boot and accept basic command input in a real tmux-backed terminal session
- keep the first slice deliberately small, deterministic, and provider-independent
- establish a reusable tmux harness that future TUI integration scenarios can build on

Decision:
- implement a thin tmux-backed harness rather than mocking the terminal boundary
- align the first implementation closely to `spec/tui-tmux-integration-harness.allium`
- keep v1 scope to one baseline scenario: boot -> ready -> `/help` -> help marker -> `/quit` -> clean exit
- treat richer interactive scenarios as follow-on work once the baseline harness exists and is stable

Scope:
- implement tmux-backed test helpers for:
  - checking tmux availability
  - allocating a unique tmux session name per test run
  - starting a detached tmux session
  - discovering the primary pane id
  - launching psi TUI in the pane
  - sending line/key input to the pane
  - capturing pane output
  - normalizing output for assertions, including ANSI-stripped matching where appropriate
  - waiting/polling for readiness and output markers with timeout control
  - reliably cleaning up the tmux session on success and failure
- add one baseline end-to-end scenario covering:
  - launch psi TUI
  - wait for the ready marker
  - send `/help`
  - assert a stable help-output marker
  - send `/quit`
  - assert that the TUI process exits cleanly within timeout
- wire the scenario into an intentional slow/integration execution path
- make missing-tmux behavior explicit and intentional
- capture useful pane evidence on failure

Non-goals:
- broad TUI interaction coverage for editing semantics, autocomplete, tree navigation, resume flows, or agent streaming
- replacing or broadening the existing unit/component TUI test suites
- introducing a second non-tmux harness technology in this slice
- provider-dependent or network-dependent assertions
- broad restructuring of TUI runtime code unless a very small and clearly justified testability seam is required
- solving all future TUI integration-test ergonomics in this first slice

Constraints:
- the test should behave as a real black-box terminal interaction, not an adapter-local simulation
- assertions should be stable against ANSI formatting noise
- the scenario must avoid depending on provider credentials, model responses, or remote services
- cleanup must run even when the scenario fails partway through
- tmux session naming must avoid cross-test interference
- the chosen execution path should make the test intentionally runnable without burdening the fast unit path unnecessarily

Behavior expectations:
- when tmux is available, the harness launches the TUI and the baseline scenario passes end-to-end
- readiness is proven by observing the expected prompt marker after boot
- help behavior is proven by observing a stable help-output marker after sending `/help`
- quit behavior is proven by observing clean process exit after sending `/quit`
- when tmux is unavailable locally, the test result is an explicit skip with a clear warning rather than a downstream shell failure
- when tmux is unavailable in CI, the test fails clearly as an environment/setup problem
- on failure, captured pane output provides enough evidence to diagnose whether boot, readiness, help, or quit failed

Key design choices to preserve:
- prefer reusable harness primitives over one monolithic shell-script-style test body
- keep the first assertions tied to explicit markers rather than broad snapshot matching
- keep the baseline scenario minimal so that failures are easy to localize
- make missing-tmux semantics explicit in both code and test output

Decision:
- missing `tmux` is treated differently by environment:
  - locally: skip the test with a clear warning/explanatory message
  - in CI: treat missing `tmux` as a setup failure so the test environment must provide it
- this preserves local ergonomics while keeping CI honest about the intended integration-test contract

Acceptance:
- there is a runnable tmux-backed integration test for psi TUI
- the harness exposes start/send/capture/wait/cleanup primitives sufficient for the baseline scenario
- the baseline scenario proves boot -> ready -> `/help` -> help marker visible -> `/quit` -> clean exit
- output assertions are stable against ANSI formatting noise
- missing-tmux behavior is explicit in test results, with local skip+warning and CI enforcement
- failure diagnostics include pane evidence
- cleanup is reliable on both success and failure
- the test is wired into an appropriate slow/integration execution path
