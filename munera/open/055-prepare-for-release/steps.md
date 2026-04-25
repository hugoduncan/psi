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
- [ ] Write `bb changelog:check` task (fails if `[Unreleased]` section is empty)
- [ ] Add `changelog:check` step to CI `check` job

## Track C — jar build
- [ ] Add `:build` alias to `deps.edn` with `io.github.clojure/tools.build`
- [ ] Write `build.clj` with `uber` task
- [ ] Add `bb build:jar` task delegating to `clojure -T:build uber`
- [ ] Verify `java -jar psi.jar --help` exits 0
- [ ] Document jar distribution decision (primary vs secondary)

## Track D — smoke test
- [ ] Define smoke test scope (launcher help, launcher-debug, nREPL discovery)
- [ ] Write smoke test namespace(s) under `test/` or `smoke/`
- [ ] Add `bb smoke:test` task
- [ ] Wire `smoke:test` into CI as a job after `clojure-test`

## Track E — GitHub release workflow
- [ ] Create `.github/workflows/release.yml` triggered on `v*` tags
- [ ] Wire `needs: [check, clojure-test, emacs-test]`
- [ ] Add build-jar step
- [ ] Add smoke-test step
- [ ] Add changelog-extract step (parse section for tag version)
- [ ] Add GitHub Release creation step with body + jar asset
- [ ] Decide Clojars publish (yes/no)
- [ ] End-to-end test: push a `v0.0.1-test` tag, verify release created
