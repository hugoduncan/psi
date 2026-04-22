Goal: reduce the remaining `clj-kondo` warning backlog enough to restore a green `bb lint` run under the repo’s current warning-as-failure policy.

Context:
- task `042` removed the hard lint errors
- `bb fmt:check` passes
- `bb lint` now reports `errors: 0` but still exits non-zero because `clj-kondo` fails on warnings by default
- the remaining backlog is large (`~235` warnings) and spread across multiple areas:
  - unused requires/bindings/private vars
  - unresolved namespace references in split TUI files and some tests
  - a smaller set of redundant lets, duplicate requires, and inline defs
- this is now a separate cleanup slice from the hard-error repair

Problem:
- the repo still does not satisfy its canonical lint proof command
- warning volume obscures higher-signal findings and blocks commits under the current pre-commit configuration
- the remaining work is broad enough that it should be tracked explicitly rather than folded silently into `042`

Intent:
- burn down the warning backlog in coherent slices until `bb lint` exits zero
- prefer direct fixes over suppressions where the warning reflects real drift
- keep cleanup grouped by area so commits remain reviewable

Scope:
- remove unused requires/bindings/private vars where they are clearly dead
- fix unresolved namespace warnings in split TUI files and tests by making references explicit or correcting requires
- clean up nearby redundant lets / duplicate requires / referred-but-unused test vars when touched
- run `bb lint` repeatedly and track the warning count reduction

Non-goals:
- architectural redesign motivated only by lint cleanup
- changing the repo-wide warning-as-failure policy in this slice
- broad stylistic rewrites unrelated to warning removal

Acceptance:
- `bb lint` exits zero
- remaining lint cleanup is committed in coherent area-based slices
- no new suppressions are introduced where a direct code/config fix is practical
- task notes record the main warning categories removed and any intentionally deferred warnings
