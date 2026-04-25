# Steps — 055 prepare-for-release

## Track B — launcher version scheme
- [x] Decide version scheme — semver `MAJOR.MINOR.PATCH`, PATCH = git-count-revs, starts at `0.1`
- [x] Create `version.edn` at repo root: `{:major 0 :minor 1}`
- [x] Create `bases/main/resources/psi/version.edn` placeholder: `{:version "unreleased"}`
- [x] Add `psi.version` namespace that reads the resource
- [x] Expose `--version` flag (parsed in `launcher.clj`, printed in `launcher-main.clj`)
- [x] Write `bb release:tag` task (reads changelog, stamps version, commits, tags, resets)
- [x] Document bbin install-from-tag command in README

## Track A — changelog discipline
- [x] Decide changelog format — keep-a-changelog, categories: Added/Changed/Fixed/Removed
- [x] Replace `CHANGELOG.md` with structured `[Unreleased]` section seeded from recent work
- [x] Write `bb changelog:check` task (fails if `[Unreleased]` section is empty)
- [x] Add `changelog:check` step to CI `check` job

## Track C — jar build
- [x] Add `:build` alias to `deps.edn` with `io.github.clojure/tools.build`
- [x] Write `build.clj` with `uber` task (produces `target/psi.jar` + `target/psi` wrapper)
- [x] Add `bb build:jar` task delegating to `clojure -T:build uber`
- [x] Verify `java -jar psi.jar --version` works (fixed `runtime-root` jar-URL guard + `--version` in `psi.main`)
- [x] Document jar distribution decision — bundled extensions, Java 22+ wrapper, launcher-first

## Track D — smoke test
- [x] Define smoke test scope — RPC handshake subprocess + TUI tmux startup (no LLM key required)
- [x] Write `bases/main/test/psi/rpc_smoke_test.clj` — launches `psi --rpc-edn`, sends handshake, asserts server-info response, clean exit
- [x] Add `bb smoke:test` task — runs `psi.rpc-smoke-test` + `psi.tui.tmux-integration-harness-test` via `:integration` kaocha suite
- [x] Wire `smoke-test` CI job — runs after `check`, parallel to `clojure-test`/`emacs-test`, with launcher shim + tmux

## Track E — GitHub release workflow + Clojars
- [x] Decide Clojars publish — yes: `io.github.hugoduncan/psi`, thin library jar, sources only
- [x] Add `:deploy` alias to `deps.edn` (`slipset/deps-deploy`)
- [x] Add `lib` task to `build.clj` — thin jar + `write-pom`
- [x] Add `deploy` task to `build.clj` — deploys via `deps-deploy`
- [x] Add `bb build:lib` and `bb deploy` tasks to `bb.edn`
- [x] Add `:jar` source policy to all psi-owned extension catalog entries
- [x] Add `psi-jar-basis` to `launcher.clj` — single mvn coord replaces all local/root self-deps
- [x] Add `:jar` policy to `materialize-manifest-dep` — resolves `:psi/release-version` placeholder
- [x] Auto-detect `:jar` policy in `launcher_main.clj` when version is a release semver
- [x] Create `.github/workflows/release.yml` triggered on `v*` tags
- [x] Wire `needs: [check, clojure-test, smoke-test, emacs-test]`
- [x] Add build-lib + deploy-to-Clojars step
- [x] Add build-jar step (uberjar for GH Release asset)
- [x] Add smoke-test step (dev-shim path)
- [x] Add changelog-extract step (parse section for tag version)
- [x] Add GitHub Release creation step with body + jar + wrapper assets
- [ ] Add `CLOJARS_USERNAME` + `CLOJARS_PASSWORD` secrets to GitHub repo

## Track F — release polish (gaps identified post-E)

### Documentation
- [x] Document `:jar` launcher policy in `doc/cli.md` — auto-detection behaviour, `PSI_LAUNCHER_POLICY=jar`, override with `=installed`
- [x] Document Clojars artifact in README — note that released versions resolve via Maven automatically; bbin git-tag install still works

### Operator experience
- [x] Add `bb release` convenience task — `bb release:tag` + `git push origin master --tags` in one step; `bb release:tag` retained for tag-only use
- [x] `bb deploy` auto-invokes `bb build:lib` if lib jar is absent

### Release workflow robustness
- [x] `release.yml` post-deploy smoke: step exercises `PSI_LAUNCHER_POLICY=jar` against the freshly deployed Clojars artifact — validates mvn coord is fetchable and `psi --version` matches the tag before GH Release is created
- [x] Keep-a-changelog comparison links — `[Unreleased]:` / `[vX.Y.Z]:` footer added to `CHANGELOG.md`; `bb release:tag` (and `bb release`) now maintain them on every stamp

### Validation
- [ ] Add `CLOJARS_USERNAME` + `CLOJARS_PASSWORD` secrets to GitHub repo
- [ ] End-to-end test: push a `v0.0.1-test` tag, verify Clojars deploy + GH Release created + `:jar` policy smoke passes

## Track G — gaps identified post-F

### Bugs
- [x] **`:jar` policy post-deploy smoke fixed** — replaced dev launcher shim with direct
  `clojure -Sdeps '{...mvn coord...}' -M -m psi.main --version`; bypasses launcher version
  resource entirely, correctly validates the Clojars artifact.
- [x] **`bb release` push recovery** — `release-and-push!` detects "local tag exists but not
  on origin" via `post-tag-push-needed?` and goes straight to push without re-tagging.
- [x] **`bb release:tag` changelog partial-failure** — `release!` detects already-stamped
  changelog (version section present) and resumes from pre-commit state.

### Robustness
- [x] **`:jar` smoke retry loop** — replaced `sleep 10` with up to 8 attempts x 30s backoff;
  exits immediately on success, clear error message after exhausting attempts.

### Documentation
- [x] **`doc/develop.md` release runbook** — prerequisites, `bb release` procedure, CI pipeline
  description, partial-failure recovery table, post-publish verification commands.
- [x] **`AGENTS.md` `λ changelog(δ)`** — extended with footer ownership rule:
  `bb release:tag` owns `[Unreleased]:` / `[vX.Y.Z]:` footer; do not edit manually.

### Validation
- [ ] Add `CLOJARS_USERNAME` + `CLOJARS_PASSWORD` secrets to GitHub repo
- [ ] End-to-end test: push a `v0.0.1-test` tag, verify Clojars deploy + GH Release created + `:jar` policy smoke passes

## Track H — gaps identified post-G

### Bugs
- [x] **`release-and-push!` patch computation fixed** — replaced `(inc (git-count-revs))`
  with `(latest-local-release-tag)` using `git describe --tags --exact-match HEAD`;
  push-recovery now correctly identifies the already-created tag regardless of commit count.

### Missing test coverage
- [x] **`:jar` policy tests added** — `psi-self-basis-test`: `:jar` emits single mvn coord,
  throws on unreleased; `startup-basis-jar-policy-resolves-release-version-placeholder`:
  placeholder resolves to version string end-to-end, throws on unreleased;
  `expand-entry-test`: `:jar` policy expands to placeholder; `manifest-expansion-report-test`:
  `:jar` policy expansion report; `psi-owned-catalog-test`: catalog has `:jar` entries.
  `bases/main/test` added to `:unit` suite in `tests.edn` (was on classpath but not discovered).
  Pre-existing test shape mismatches fixed (`:version?` field, `:psi/init` strip behaviour).
  Suite: 1366 tests, 10426 assertions, 0 failures.

### Minor
- [x] **`doc/develop.md` runbook: standalone deploy debugging** — added "Debugging Clojars
  deploy without a full release" section with step-by-step instructions for `bb build:lib`
  + `bb deploy` standalone use.
