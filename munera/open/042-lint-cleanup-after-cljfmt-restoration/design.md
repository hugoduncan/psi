Goal: restore a green `bb lint` run after re-enabling formatting enforcement and CI formatting checks.

Context:
- task `041` restored `cljfmt` enforcement locally and in CI
- `bb fmt:check` now passes, but `bb lint` still fails
- the current failures are a mix of real lint errors and lint-surface drift:
  - nonexistent lint path `specs`
  - unresolved symbols in the TUI extracted-file split
  - unresolved symbol reports in a couple of tests using local temp-dir helpers
  - many additional warnings from unused requires/bindings and unresolved aliases in split files
- the CI `check` job now runs formatting and linting again, so a failing lint pass is once more blocking repo health

Problem:
- the repository no longer passes its canonical lint proof command
- some lint failures are structural and should be fixed in code/config rather than suppressed
- the current warning volume also obscures genuinely important problems

Intent:
- make `bb lint` pass again with the smallest coherent set of code/config changes
- fix root-cause lint drift where practical instead of muting it blindly
- keep the slice narrow: restore repo proof health rather than redesign subsystems

Scope:
- fix lint configuration drift in `deps.edn` / `bb.edn` path surfaces if needed
- fix unresolved-symbol / unresolved-namespace lint errors in the TUI extracted-file split
- fix the currently failing test-file lint errors
- remove or correct nearby unused requires/bindings encountered while fixing the blocking lint failures
- run `bb lint` to completion and leave the repo green

Non-goals:
- broad style-only cleanup of every existing warning if not needed for a green lint run
- subsystem redesign beyond what is needed to make linting correct
- introducing repo-wide lint suppressions for problems that can be fixed directly

Acceptance:
- `bb lint` exits zero
- nonexistent lint input paths are removed or made valid
- current unresolved-symbol / unresolved-namespace lint errors are resolved at the source
- any warning cleanup done in this slice is directly adjacent to the blocking fixes and keeps the code clearer rather than noisier
