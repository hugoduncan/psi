# 055 — Prepare for Release

## Goal

Make psi releasable as a versioned artifact. Establish the discipline,
tooling, and automation needed to cut and ship a release confidently.

## Context

Psi is distributed via the bb launcher (bbin install from git). The CI
pipeline (`.github/workflows/ci.yml`) runs fmt/lint and unit/integration/emacs
tests. Tracks A–C are now complete; D and E remain.

## Version scheme (decided, implemented)

`MAJOR.MINOR.PATCH` semver:
- `version.edn` (repo root) stores `{:major 0 :minor 1}` — only thing committed
- `PATCH = (git rev-list HEAD --count) + 1` — pre-compensates for the release commit
- `bases/main/resources/psi/version.edn` written at tag time: `{:version "0.1.NNNN"}`
- Reset to `{:version "unreleased"}` in a follow-up commit immediately after tagging
- First release will be `0.1.NNNN` where NNNN is the commit count at release time

## Java requirement (decided, documented)

Java 22+ required at runtime. Driver: `jline-terminal-ffm` (used by charm.clj/TUI)
compiles to class-file version 66 (Java 22). `--enable-native-access=ALL-UNNAMED`
is passed unconditionally in the jar wrapper — valid on all Java 22+.
Documented in `README.md` and `doc/cli.md`.

## Track A — Changelog discipline (complete)

- Format: keep-a-changelog (`[Unreleased]` → `[MAJOR.MINOR.PATCH] - YYYY-MM-DD`)
- Categories: Added / Changed / Fixed / Removed
- Entry required for: user-facing commands, flags, behaviours, breaking changes,
  user-visible bug fixes, new extension capabilities
- Entry NOT required for: refactor, test additions, lint fixes, internal convergence
- `bb changelog:check` enforces non-empty `[Unreleased]` section; wired into CI `check` job
- Rules encoded in `AGENTS.md` as `λ changelog(δ)` for future ψ sessions

## Track B — bb launcher release management (complete)

- `version.edn` at repo root: `{:major 0 :minor 1}`
- `bases/main/resources/psi/version.edn` placeholder: `{:version "unreleased"}`
- `psi.version/version-string` reads the resource; works in both bb and JVM
- `--version` flag handled in both `launcher-main` (bb path) and `psi.main` (jar path)
- `bb release:tag` in `bb/release.clj`:
  1. Asserts clean tree + on master
  2. Reads `{:major :minor}` from `version.edn`
  3. Computes `PATCH = (git rev-list HEAD --count) + 1`
  4. Asserts `[Unreleased]` section is non-empty
  5. Stamps `CHANGELOG.md`: `[Unreleased]` → `[V] - YYYY-MM-DD`, prepends fresh `[Unreleased]`
  6. Writes `{:version "V"}` to `bases/main/resources/psi/version.edn`
  7. `git add` + `git commit "release: vV"`
  8. `git tag vV`
  9. Resets resource to `{:version "unreleased"}`, commits `"release: post-vV reset version to unreleased"`
  10. Prints push instructions
  - Partial-failure recovery: if tag exists but resource not yet reset, completes reset and exits
- README documents: latest install, pinned `--git/tag vX.Y.Z` install, `--version`, upgrade path

## Track C — Jar build (complete)

- `build.clj` with `uber` task using `tools.build 0.10.12`
- `:build` alias in `deps.edn`
- `bb build:jar` → `clojure -T:build uber`
- Output: `target/psi.jar` (30MB) + `target/psi` wrapper script
- AOT: only `psi.main` + `psi.app-runtime` (both have `:gen-class`)
- Source paths mirror `:run` alias exactly — all 11 components + 10 bundled extensions
- Wrapper: `java --enable-native-access=ALL-UNNAMED -jar psi.jar "$@"`
- `psi.main/-main` handles `--version` directly (jar bypasses launcher)
- Fix landed: `runtime-root` in `extension_installs.clj` now guards against `jar:` URLs —
  returns `nil` when running from uberjar so `local/root` paths are not resolved
  (extensions are already on the classpath inside the jar)

## Track D — Smoke test (pending)

Define a minimal suite exercising the artifact end-to-end without a real LLM key:
- `psi --version` exits 0 and prints a version string (both bb launcher and jar)
- `psi --launcher-debug --version` emits debug output without crashing (bb launcher)
- jar `--version` exits 0
- (stretch) nREPL port discovery in a temp worktree

Provide `bb smoke:test`. Wire into CI after `clojure-test`.

## Track E — GitHub release workflow (pending)

`.github/workflows/release.yml` triggered on `push: tags: ['v*']`:
1. Reuse `check` + `clojure-test` + `emacs-test` jobs via `needs`
2. Build uberjar (`bb build:jar`)
3. Run smoke test (`bb smoke:test`)
4. Extract changelog section for the tag version from `CHANGELOG.md`
5. Create GitHub Release with changelog body + `psi.jar` + `psi` wrapper attached
- Clojars publish: library jar (`io.github.hugoduncan/psi`) deployed on every release
  - thin jar: sources + resources, no AOT, no bundled deps
  - launcher `:jar` policy uses `{:mvn/version "X.Y.Z"}` for psi self-dep and all bundled extensions
  - auto-detected: when `psi/version.edn` is a release semver (not `"unreleased"`), launcher defaults to `:jar` policy
  - override: `PSI_LAUNCHER_POLICY=installed` forces git/local path mode; `=development` forces dev mode
  - third-party extensions: resolved via `clojure -M` as usual; `:jar` policy resolves their `:psi/release-version` placeholder to the actual version

## Acceptance criteria

- `bb changelog:check` passes with entries, fails without. ✅
- `bb build:jar` produces a runnable `psi.jar`. ✅
- `psi --version` (bb) and `java -jar psi.jar --version` both print the version. ✅
- `bb smoke:test` passes against the built jar. ⬜
- Pushing a `vX.Y.Z` tag triggers the release workflow, deploys to Clojars,
  creates a GitHub Release with correct changelog body and jar attached. ⬜
- Released version auto-selects `:jar` policy; launcher resolves `io.github.hugoduncan/psi`
  from Maven cache instead of re-fetching from git. ⬜
- End-user `bbin install --git/tag vX.Y.Z` works and `psi --version` prints correctly. ⬜
- `doc/cli.md` documents `:jar` policy and auto-detection. ⬜

## Constraints

- Do not break the existing CI pipeline.
- Launcher-first distribution remains primary; jar is additive.
- Version scheme consistent across launcher, jar manifest, and GitHub Release tag.
- `version.edn` stores only `{:major 0 :minor 1}`; patch never committed.
- `bb release:tag` partial-failure recovery: re-run completes safely.
