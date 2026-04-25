# Implementation notes — 055 prepare-for-release

Append discoveries and decisions here as work proceeds.

## 2026-04-25 — version scheme decided

- Scheme: semver `MAJOR.MINOR.PATCH`
- PATCH = `git rev-list HEAD --count` via `(b/git-count-revs nil)` from `io.github.clojure/tools.build`
- `version.edn` (repo root) stores only `{:major 0 :minor 1}` — patch never committed
- `resources/psi/version.edn` written at tag time with full `{:version "0.1.NNNN"}` string
- First release will be `0.1.1985` (current HEAD count at decision time)
- MAJOR.MINOR bumped manually on breaking change or significant milestone only
