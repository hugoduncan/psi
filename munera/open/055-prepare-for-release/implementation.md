# Implementation notes — 055 prepare-for-release

Append discoveries and decisions here as work proceeds.

## 2026-04-25 — changelog format decided

- Format: keep-a-changelog (`[Unreleased]` / `[MAJOR.MINOR.PATCH] - YYYY-MM-DD`)
- Categories: Added / Changed / Fixed / Removed
- Entry required for: user-facing commands, flags, behaviours, breaking changes, user-visible bug fixes, new extension capabilities
- Entry NOT required for: refactors, test additions, lint fixes, internal convergence
- `bb changelog:check` will enforce non-empty `[Unreleased]` section before a tag is cut
- Old freeform `CHANGELOG.md` scrapped; new structured file seeded with recent notable changes

## 2026-04-25 — version scheme decided

- Scheme: semver `MAJOR.MINOR.PATCH`
- PATCH = `git rev-list HEAD --count` via `(b/git-count-revs nil)` from `io.github.clojure/tools.build`
- `version.edn` (repo root) stores only `{:major 0 :minor 1}` — patch never committed
- `resources/psi/version.edn` written at tag time with full `{:version "0.1.NNNN"}` string
- First release will be `0.1.1985` (current HEAD count at decision time)
- MAJOR.MINOR bumped manually on breaking change or significant milestone only
