# 055 — Prepare for Release

## Goal

Make psi releasable as a versioned artifact. Establish the discipline,
tooling, and automation needed to cut and ship a release confidently.

## Context

Psi is distributed via the bb launcher (bbin install from git). There is no
versioned jar build, no changelog discipline, no smoke test, and no GitHub
release workflow. The CI pipeline (`.github/workflows/ci.yml`) runs fmt/lint
and unit/integration/emacs tests but stops there.

The launcher entry point is `bb/psi.clj` → `psi.launcher-main/-main`, which
resolves the runtime classpath at startup from a `PSI_LAUNCHER_POLICY`
(`:development` | `:installed`). The installed policy resolves deps from the
bbin-installed repo root.

## Scope

Five parallel tracks, each a focused sub-task:

### Track A — Changelog discipline
- Establish a machine-readable changelog format (keep-a-changelog style).
- Define the update discipline: every PR that changes user-visible behaviour
  must include a changelog entry.
- Provide a `bb changelog:check` task that fails CI if no entry is present for
  the current HEAD (on non-merge commits).
- Current `CHANGELOG.md` uses a freeform date-header format; migrate to
  versioned sections (`[Unreleased]`, `[x.y.z]`).

### Track B — bb launcher release management
- Decide and document the version scheme (calver `YYYY.MM.DD` or semver).
- Establish how a release version is embedded: a `version.edn` or
  `resources/psi/version.edn` file read at runtime.
- Provide a `bb release:tag` task that:
  1. Reads the current `[Unreleased]` changelog section.
  2. Stamps it with the chosen version + date.
  3. Writes `resources/psi/version.edn`.
  4. Commits + tags `vX.Y.Z`.
- Document the bbin install command for end-users pointing at a tag.

### Track C — Jar build
- Add a `:build` alias to `deps.edn` using `io.github.clojure/tools.build`.
- Write `build.clj` with `uber` task producing a self-contained uberjar.
- Provide a `bb build:jar` task that delegates to `clojure -T:build uber`.
- Verify the jar starts correctly (`java -jar psi.jar --help`).
- Decide whether the jar is a primary distribution artifact or a secondary one
  (launcher-first, jar as fallback/server mode).

### Track D — Smoke test
- Define a minimal smoke test suite that exercises the installed artifact
  end-to-end without requiring a real LLM key:
  - launcher resolves and prints help (`psi --help` exits 0).
  - launcher emits `--launcher-debug` output without crashing.
  - nREPL port discovery works in a temp worktree.
  - (stretch) a stubbed one-turn prompt round-trip through the dispatch
    pipeline using a mock provider.
- Provide a `bb smoke:test` task.
- Wire smoke test into CI as a separate job that runs after `clojure-test`.

### Track E — GitHub release workflow
- Add `.github/workflows/release.yml` triggered on `push: tags: ['v*']`.
- Steps:
  1. Run full CI suite (reuse existing jobs via `needs`).
  2. Build uberjar (Track C).
  3. Run smoke test against the jar (Track D).
  4. Extract changelog section for the tag version.
  5. Create GitHub Release with changelog body + jar asset.
- Decide whether to publish to Clojars in addition to GitHub Releases.

## Acceptance criteria

- `bb changelog:check` passes on a branch with a changelog entry, fails
  without one.
- `bb build:jar` produces a runnable `psi.jar`.
- `bb smoke:test` passes against the built jar.
- Pushing a `vX.Y.Z` tag triggers the release workflow, creates a GitHub
  Release with the correct changelog body and jar attached.
- End-user `bbin install` from a tag works and `psi --version` prints the
  correct version string.

## Constraints

- Do not break the existing CI pipeline.
- Launcher-first distribution remains the primary install path; jar is
  additive.
- Keep the version scheme consistent across launcher, jar manifest, and
  GitHub Release tag.
- `bb release:tag` must be idempotent (re-running on an already-tagged commit
  is a no-op or a clear error).
