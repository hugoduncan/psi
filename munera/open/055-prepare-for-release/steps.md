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
- [ ] Document `:jar` launcher policy in `doc/cli.md` — auto-detection behaviour, `PSI_LAUNCHER_POLICY=jar`, override with `=installed`
- [ ] Document Clojars artifact in README — note that released versions resolve via Maven automatically; bbin git-tag install still works

### Operator experience
- [ ] Add `bb release` convenience task — runs `bb release:tag` then prints/executes `git push origin master --tags`
- [ ] `bb deploy` should auto-invoke `bb build:lib` if lib jar is absent (or clearly error with the exact command to run first)

### Release workflow robustness
- [ ] `release.yml` post-deploy smoke: add a step that exercises `PSI_LAUNCHER_POLICY=jar` against the freshly deployed Clojars artifact — validates the mvn coord is actually fetchable and the launcher `:jar` policy resolves correctly before creating the GH Release
- [ ] Keep-a-changelog comparison links — add `[Unreleased]:` / `[vX.Y.Z]:` footer links to `CHANGELOG.md` and update `bb release:tag` to maintain them

### Validation
- [ ] End-to-end test: push a `v0.0.1-test` tag, verify Clojars deploy + GH Release created + `:jar` policy smoke passes
