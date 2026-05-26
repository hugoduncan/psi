# Implementation notes

## 2026-05-21 takeover
- Took over task 156 for the remaining live TUI `/resume` discovery work.
- The task previously had only `design.md`; added `plan.md`, `steps.md`, and this `implementation.md`.
- Important separation established at takeover:
  - fixed already: `/resume` crash caused by mixed `java.util.Date` / `java.time.Instant` session timestamps during context-session sorting
  - still open here: live TUI `/resume` selector sometimes reports `(no sessions found)` in the tmux harness despite a persisted session fixture being present for the current worktree

## Relevant completed fix outside this task’s remaining scope
- Commit `609aa51d` — `⚒ Canonicalize resumed session timestamps to Instant`
- That change:
  - decodes persisted `#inst` values to `java.time.Instant`
  - initializes resumed session `:created-at` / `:updated-at`
  - removes remaining `Date` compatibility from touched production paths
  - makes `/resume` footer/session-list sorting stable under the canonical timestamp invariant

## Diagnosis and first fix
- Traced the active TUI `/resume` path and confirmed the selector itself is canonical query-driven in the live frontend-action flow:
  - `app-runtime/tui_frontend_actions.clj` issues a query for `:psi.persisted-session/list`
  - `agent-session/resolvers/discovery.clj` resolves that list from `session-root + session worktree-path`
- Inspected the quarantined tmux harness and found a separate harness-specific boundary failure:
  - tmux scenarios that exercise current-worktree code use `worktree-launch-command`
  - that function returned relative `exec bb bb/psi.clj -- --tui`
  - tmux sessions often launch from temp fixture directories, not the repo root
  - direct proof: `bb bb/psi.clj -- --help` fails outside the repo root, while the absolute launcher succeeds
- This can produce a harness-only failure mode where the intended runtime is not launched from the current worktree fixture context.

## Implemented fix
- Updated `components/tui/test/psi/tui/test_harness/tmux.clj`
  - `worktree-launch-command` now uses `repo-local-launch-command-abs`
- Added focused regression coverage in `components/tui/test/psi/tui/test_harness/tmux_test.clj`
  - proves the worktree launcher must be absolute when `bb` is available

## Further live diagnosis
- Added supported TUI startup overrides for harnesses:
  - `PSI_CWD`
  - `PSI_SESSION_ROOT`
- Verified that `PSI_CWD` alone was insufficient because the babashka launcher still anchored execution from the repo-root process cwd.
- Switched the live harness to use the launcher-native `--cwd <worktree>` seam together with `PSI_SESSION_ROOT`.
- With that change, the live tmux scenario now reproduces a **discovered session row** in `/resume` instead of `(no sessions found)`.

## Final root cause and fix
- After discovery was repaired, the live TUI still failed to show resumed transcript content after selection.
- Root cause: `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj` handled `:select-resume-session` by calling `session/resume-session-in!` directly and then constructing a fake `:restored` payload from session data.
- That session data does not carry canonical rehydration messages, so the live UI switched focus but restored an empty transcript.
- Fixed by routing the live TUI resume action through `psi.app-runtime.navigation/resume-session-result` and returning `:nav/rehydration`, matching the canonical navigation rehydration contract.

## Outcome
- Live tmux `/resume` selector discovery works for a temp-worktree fixture via launcher-native `--cwd` plus isolated `PSI_SESSION_ROOT`.
- Selecting the discovered session visibly restores the thinking/text transcript in the pane.
- Task 156 acceptance is satisfied by the focused live tmux scenario plus supporting harness/main/runtime fixes.
