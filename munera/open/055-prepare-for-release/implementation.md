# Implementation notes — 055 prepare-for-release

## 2026-04-25 — changelog format decided

- Format: keep-a-changelog (`[Unreleased]` / `[MAJOR.MINOR.PATCH] - YYYY-MM-DD`)
- Categories: Added / Changed / Fixed / Removed
- Entry required for: user-facing commands, flags, behaviours, breaking changes, user-visible bug fixes, new extension capabilities
- Entry NOT required for: refactors, test additions, lint fixes, internal convergence
- `bb changelog:check` enforces non-empty `[Unreleased]` section before a tag is cut
- Old freeform `CHANGELOG.md` scrapped; new structured file seeded with recent notable changes
- Rules encoded in `AGENTS.md` as `λ changelog(δ)` (lambda-compiled form)

## 2026-04-25 — version scheme decided

- Scheme: semver `MAJOR.MINOR.PATCH`
- PATCH = `(git rev-list HEAD --count) + 1` — pre-compensates for the release commit
  so the tagged commit's count exactly equals the version number
- `version.edn` (repo root) stores only `{:major 0 :minor 1}` — patch never committed
- `bases/main/resources/psi/version.edn` written at tag time: `{:version "0.1.NNNN"}`
- Reset to `{:version "unreleased"}` in a follow-up commit after tagging — prevents
  dev checkouts from claiming to be a release version
- First release will be `0.1.NNNN` where NNNN is the commit count at release time
  (was ~1985 at decision time, will be higher by release)

## 2026-04-25 — Java version requirement

- Minimum Java: **22** — driven by `jline-terminal-ffm` (charm.clj TUI dependency)
  which compiles to class-file version 66 = Java 22
- `--enable-native-access=ALL-UNNAMED` is unconditionally valid on Java 22+
  (on Java 22+ without the flag: warning only, not an error; with flag: silent)
- No version gating needed in wrapper script
- Documented in `README.md` and `doc/cli.md`

## 2026-04-25 — bb release:tag implementation

- Lives in `bb/release.clj`, loaded via `bb.edn` `:init`
- Function named `release!` (avoids shadowing `clojure.core/run!`)
- Sequence: assert-clean → assert-master → compute-version → assert-changelog →
  stamp-changelog → write-resource → commit → tag → reset-resource → commit → print
- Partial-failure recovery: if tag exists but resource still shows release version,
  completes the reset commit and exits 0 cleanly
- `bb changelog:check` reuses `unreleased-section` from same namespace

## 2026-04-25 — jar build

- `build.clj` with `tools.build 0.10.12`
- AOT: `psi.main` + `psi.app-runtime` only (both have `:gen-class`)
- All other namespaces load dynamically
- Conflict handlers: `META-INF/services/*` → `:append`; sig files → `:ignore`
- Output: `target/psi.jar` (~30MB) + `target/psi` executable wrapper script
- `--version` flag added to `psi.main/-main` (jar bypasses launcher)

## 2026-04-25 — runtime-root jar-URL fix

- `extension_installs.clj` `runtime-root` previously called `io/file` on the
  resource URL unconditionally — throws `IllegalArgumentException` inside a jar
  because `jar:` URLs cannot be converted to `File`
- Fix: guard on `(= "file" (.getProtocol url))` — returns nil when running from jar
- When `root` is nil, `installed-default-entry` returns the catalog entry without
  absolutizing `local/root` paths — correct because extensions are already on the
  classpath inside the uberjar
- 1341 tests, 0 failures after fix
