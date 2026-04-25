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
- [ ] **`:jar` policy post-deploy smoke is broken** — the smoke step uses the dev launcher shim
  (`bb bb/psi.clj`), but after `bb release:tag` the version resource is reset to `"unreleased"`.
  `release-version` returns `nil` so `:jar` policy throws immediately. Fix: the smoke must either
  (a) use a bbin-installed psi at the tagged version, or (b) temporarily stamp the version resource
  to the release version before running `psi --version`, then reset it, or (c) invoke the launcher
  directly with an explicit `--version` flag against a temp deps basis pointing at the Clojars coord.
  Simplest correct approach: use `clojure -Sdeps '{...mvn coord...}' -M -m psi.main --version`.

- [ ] **`bb release` partial-failure recovery doesn't cover failed push** — if `release-and-push!`
  fails during `git push` (network error), re-running hits the "tag already exists + version reset"
  recovery path and exits without retrying the push. Fix: recovery path should detect
  "tag exists + version reset" → attempt push.

- [ ] **`bb release:tag` changelog partial-failure gap** — if the process dies after
  `stamp-changelog!` but before `git commit`, the `[Unreleased]` section is already stamped.
  Re-running fails because `[Unreleased]` no longer matches. Fix: detect already-stamped changelog
  in the recovery path.

### Robustness
- [ ] **`:jar` policy smoke — replace fixed `sleep 10` with retry loop** — Clojars propagation
  is not instantaneous and 10s is arbitrary. Replace with a retry loop (e.g. up to 5 attempts,
  30s apart) so transient propagation delay doesn't fail the release.

### Documentation
- [ ] **`doc/develop.md` release runbook** — document the full operator procedure:
  prerequisites (Clojars account, GH secrets), `bb release` command, what to watch in CI,
  how to handle partial failures, and how to verify a release post-publish.

- [ ] **`AGENTS.md` `λ changelog(δ)` rule** — extend to mention that `bb release:tag`
  also maintains the `[Unreleased]:` / `[vX.Y.Z]:` comparison link footer.
