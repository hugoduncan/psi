# Plan — 055 prepare-for-release

## Status

- ✅ A — changelog discipline
- ✅ B — version scheme + files + flag + release task + README
- ✅ C — jar build
- ⬜ D — smoke test
- ⬜ E — GitHub release workflow

## Dependency order

```
A ✅ → feeds E (changelog extraction)
B ✅ → feeds E (version tag)
C ✅ → feeds D (smoke tests the jar)
D ⬜ → feeds E (release workflow runs smoke)
E ⬜ — assembles all prior tracks
```

## Track D — smoke test

Scope (no real LLM key required):
- bb launcher: `psi --version` exits 0, output matches `psi \d+\.\d+\.\d+` or `psi unreleased`
- bb launcher: `psi --launcher-debug --version` emits debug summary + version, exits 0
- jar: `java --enable-native-access=ALL-UNNAMED -jar target/psi.jar --version` exits 0

Implementation options:
- Clojure test namespace under `bases/main/test/psi/smoke_test.clj` in the `:integration` suite
- Or a standalone `bb smoke:test` task that shells out

Recommendation: bb task shelling out — smoke tests the *artifact* not the source,
so running inside the JVM test suite is the wrong level. The task runs the built
jar and the installed/dev launcher as subprocesses.

## Track E — release workflow

File: `.github/workflows/release.yml`
Trigger: `push: tags: ['v*']`

Jobs:
1. Reuse `check` + `clojure-test` + `emacs-test` via `needs` (or re-run inline)
2. `build` job: `bb build:jar` → upload `target/psi.jar` + `target/psi` as artifacts
3. `smoke` job: download artifacts, run `bb smoke:test` against the jar
4. `release` job: extract changelog section for tag, create GitHub Release with assets

Changelog extraction: parse `CHANGELOG.md` for `## [VERSION]` section — can be a
small Python or bb script, or reuse logic from `bb/release.clj`.

## Risks (resolved)

- ~~tools.build uberjar conflicts with dynamic classpath resolution~~ — resolved:
  `runtime-root` now returns nil inside jar, extensions are already on classpath
- ~~Smoke test requires mock provider~~ — scoped to launcher/jar startup only, no LLM needed
- ~~CI job time~~ — release workflow only runs on tags, not every push
