# 157 — released jar startup from jar-owned deps.edn

## Intent

Reshape psi’s released packaging so the released `psi` command starts from the published jar’s own `deps.edn` metadata rather than reconstructing runtime dependencies from the repo/worktree layout at invocation time.

## Context

The previous direction for this task targeted a `projects/main` uberjar release shape. That would remove launcher-time dependency resolution entirely, but it collides with an important requirement: psi supports extensions, and the release shape still needs a flexible classpath story for extension installation and startup.

So the correct release shape is narrower:

- keep psi as a released jar
- keep launcher-time dependency realization where needed for the jar + extensions model
- stop deriving the released runtime basis from the repo/worktree component graph
- instead derive the authoritative psi runtime dependency closure from the published jar’s own `deps.edn`

This keeps extension flexibility while removing the current mismatch between repo-local dependency resolution and released startup.

The recent `psi --tui` release failure exposed the problem clearly:

- `psi.tui.app` depends on `charm.clj`
- that dependency is available in the repo/development shape through component-local deps resolution
- the released launcher path did not reliably reflect the full runtime dependency closure of the released artifact
- installed `psi --tui` therefore failed at startup

This is not fundamentally a TUI bug. It is a release metadata / runtime-basis bug.

## Problem

Psi currently lets the released launcher infer psi’s own runtime dependency closure indirectly from repo-oriented startup logic.

That is the wrong source of truth for a released jar.

For the released application, psi’s own runtime closure should come from the released artifact itself:

- the jar should carry the authoritative `deps.edn` data needed for psi’s core runtime
- the jar should also contain the psi-owned local dependency source/resource trees that make up the shipped application code
- the launcher should use that jar-owned dependency metadata when constructing the runtime basis
- extension manifests can then layer on top of that released psi basis

Without that shift, released behavior can drift from the artifact actually shipped and regressions like missing `charm.clj` can escape.

## Goal

Make the published psi jar the authoritative owner of psi’s runtime dependency metadata, via jar-owned `deps.edn`, and make the released launcher build its basis from that artifact-owned metadata rather than from repo/worktree dependency inference.

For this task, `jar-owned deps.edn` means a stable, named dependency metadata payload packaged inside the published psi jar and read by the released launcher as artifact-owned release metadata. It may be the root `deps.edn` copied into the jar or a generated release-focused `deps.edn`, but the final shape must be explicit, stable, and testable rather than inferred indirectly from the repo layout.

Implementation must choose one exact metadata path/name inside the jar and make launcher code, tests, and docs refer to that exact path consistently.

## In scope

- define the released jar as the authoritative source of psi core runtime dependency metadata
- ensure the published jar contains the `deps.edn` information needed for released startup
- ensure the published jar also contains the source and resource trees for psi-owned local dependencies that the released startup classpath expects to load from the psi artifact itself
- define the boundary of shipped psi-owned local code/resources precisely: runtime source/resource paths that belong to the shipped psi application closure, excluding test-only paths and excluding non-shipped repo-local scaffolding
- choose and name one canonical authority for that boundary, preferably the authoritative released runtime path set already used to describe psi startup, so inclusion is derived mechanically rather than by ad hoc copying rules
- change launcher release startup to read psi’s own dependency closure from the jar-owned metadata rather than from repo/worktree dependency inference
- preserve extension layering on top of the released psi jar basis
- preserve a clear development path for repo-local iteration, even if it continues to use repo-local startup behavior
- update smoke tests so they exercise the released jar startup shape, including `--tui`
- update documentation so the release/startup model matches the new authoritative packaging story

## Likely implementation surfaces

This task should refine the release shape through the smallest set of authoritative surfaces rather than spreading packaging logic across unrelated areas.

### 1. Build packaging surface

Primary owner candidates:

- `build.clj`
- `bases/main/src/psi/build_manifest.clj`

Likely responsibilities here:

- derive the authoritative runtime source/resource closure for psi-owned local deps
- derive the authoritative external dependency closure to embed as jar-owned `deps.edn`
- copy psi-owned local dependency source/resource trees into the library jar during build
- add proofs that the built jar contains both the expected runtime metadata and the expected packaged local code/resources

Preferred rule:

- build-time derivation should be mechanical from authoritative runtime inputs, not a hand-maintained second list that can drift

### 2. Launcher release startup surface

Primary owner candidates:

- `bases/main/src/psi/launcher.clj`
- related launcher packaging helpers under `bases/main/src/psi/launcher/`

Likely responsibilities here:

- distinguish release startup from repo/dev startup explicitly
- for released startup, construct psi’s own basis from jar-owned metadata rather than repo-root `deps.edn`
- preserve additive layering of user/project extension manifests on top of the jar-owned psi basis
- stop treating repo/worktree local-root discovery as the authoritative source of shipped psi runtime closure

Preferred rule:

- release launcher code should consume artifact-owned metadata, not rebuild release truth from repository structure

### 3. Smoke and packaging proof surfaces

Primary owner candidates:

- `bases/main/test/psi/build_jar_smoke_test.clj`
- `bases/main/test/psi/release_packaging_smoke_test.clj`
- `bases/main/test/psi/bbin_install_smoke_test.clj`
- launcher-focused tests under `bases/main/test/psi/launcher*_test.clj`

Likely responsibilities here:

- prove the built jar contains the required psi-owned packaged namespaces/resources
- prove the built jar exposes authoritative jar-owned `deps.edn` metadata for release startup
- prove installed/released startup uses the jar-shaped path rather than only repo-shaped test execution
- include `--tui` as a release smoke check so missing runtime deps or missing packaged local code fail before release

Preferred rule:

- at least one smoke path must exercise the artifact users actually run, not only repo-local source startup

### 4. Documentation surfaces

Primary owner candidates:

- `README.md`
- `doc/cli.md`
- `doc/develop.md`
- `doc/tui.md`

Likely responsibilities here:

- explain the released jar startup model clearly
- distinguish development startup from released startup where both remain
- document release smoke expectations, especially around `--tui`

Preferred rule:

- docs should name the release-authoritative startup shape directly, so future changes do not quietly drift back to repo-derived release assumptions

## Out of scope

- moving to an uberjar-only release shape
- removing extension support or redesigning extension manifests broadly
- redesigning extension conflict/override policy beyond the minimum additive layering rules needed for release startup clarity
- redesigning the internal component/base architecture beyond what is needed to make the jar’s dependency metadata authoritative
- removing all development-time launcher conveniences if a separate dev-only path still proves useful

## Required invariants

- released startup must not require the psi repo layout at runtime
- released startup must derive psi’s own runtime dependency closure from artifact-owned metadata rather than from repo-root or worktree-local dependency discovery
- shipped psi-owned runtime code/resources must be loaded from the published artifact rather than from repo-local `:local/root` paths
- released startup must not silently fall back to repo-derived dependency resolution when artifact-owned metadata or packaged code is missing or malformed
- extension manifests remain additive over the released psi basis rather than replacing psi’s own shipped dependency closure
- the release shape must be explicit enough that missing runtime deps or missing shipped local code/resources fail at build/smoke time rather than first surfacing at end-user startup

## Required target shape

### Release shape

The authoritative released application shape must satisfy all of the following:

- the published psi jar includes authoritative dependency metadata for psi’s own runtime closure at an explicit stable path or name that the launcher can read deterministically
- the published psi jar includes the shipped psi-owned runtime source/resource trees for local dependencies that are part of the released application closure
- the released launcher uses that jar-owned metadata when constructing the psi runtime basis
- the released launcher does not depend on repo/worktree component `deps.edn` discovery to determine psi’s own shipped runtime closure
- extension manifests remain additive on top of the released psi jar basis, with psi’s own shipped basis remaining the base layer rather than becoming replaceable repo-derived state
- implementation must make the extension overlap/precedence rule explicit when both jar-owned psi deps and extension manifests mention the same library; if that rule is not changed here, the task must preserve and document the current effective rule or explicitly defer conflict policy beyond additive non-conflicting layering
- released startup succeeds without requiring the psi repo layout to be present at runtime
- released `psi --tui` works from the released startup shape because required runtime deps are present in the artifact-owned basis data and the psi-owned code they support is packaged in the jar

### Development shape

Development may retain a separate startup path if needed, but it must be explicit that it is a development path rather than the released application shape.

Acceptable examples:

- repo-local `clojure -M:...` startup
- repo-local `bb bb/psi.clj -- ...`
- a dev launcher that resolves repo deps for iteration

If a dev-only repo-aware launcher remains, it must not be the source of truth for the released psi runtime closure, and released startup must not silently drop into that repo-aware path as a fallback.

## Design constraints

- keep the release model jar-based rather than switching to an uberjar-only shape
- preserve extension support
- make the released jar’s metadata, not the repo layout, the authoritative description of psi’s own runtime closure
- make the released jar contents, not the repo layout, the authoritative packaged source/resource set for psi-owned local dependencies
- ensure the build fails or smoke tests fail when required runtime deps are missing from the released jar metadata path
- ensure the build fails or smoke tests fail when required psi-owned local dependency code/resources are missing from the released jar contents
- avoid preserving ambiguous dual authority between repo alias composition and released artifact metadata; one source must be authoritative for release
- preserve existing user-visible capabilities unless a packaging change requires a narrow, explicit adjustment

## Key design questions

1. What exact `deps.edn` content must be embedded in the published jar so it fully describes psi’s shipped runtime closure?
2. Should the jar carry the full authoritative root deps data directly, or a release-focused subset derived mechanically during build?
3. How should `build.clj` and/or `psi.build-manifest` collect and package all psi-owned local dependency source/resource trees that belong to the shipped application, similar to the local-source collection pattern used by `projects/server-jar/build.clj` in Chat-Rama?
4. How should `launcher.clj` locate and read jar-owned `deps.edn` metadata when constructing the psi basis?
5. What exact additive layering model should apply between jar-owned psi deps and user/project extension manifest deps, including the intended precedence rule when both mention the same library?
6. Which current packaging and launcher tests still prove repo-shaped startup rather than jar-shaped startup, and how should they change?
7. How should the smoke suite prove released `--tui` startup from the installed or packaged artifact-shaped path rather than only from repo-local startup?
8. Which smoke or packaging proof should explicitly demonstrate that released startup succeeds in an isolated install environment without the psi source repo present on disk?
9. What dev-only repo-aware startup paths remain acceptable, and how are they kept clearly non-authoritative for release?

## Explicit decisions required during implementation

Implementation must make explicit and test:

- the exact resource path/name for jar-owned release dependency metadata
- the canonical authority used to derive shipped runtime source/resource inclusion
- the precedence rule between jar-owned psi deps and extension manifest deps when both mention the same library
- the concrete isolated-install smoke path that proves release startup works without the psi repo layout present

## Acceptance

1. The published psi jar contains authoritative dependency metadata for psi’s own runtime closure at a stable, named location that the released launcher reads deterministically.
2. The published psi jar contains all shipped psi-owned runtime source/resource trees required by the released application, with the boundary excluding test-only and non-shipped repo-local paths.
3. Released startup constructs psi’s own basis from jar-owned metadata rather than from repo/worktree dependency inference.
4. Released startup succeeds in an isolated install/smoke environment without requiring the psi source repo layout to be present on disk.
5. Extension manifests still layer correctly and additively on top of the released psi jar basis, with the overlap/precedence rule made explicit.
6. Smoke tests execute the installed or packaged artifact-shaped startup path and include `--tui` startup coverage.
7. The packaging/startup model is documented clearly enough that future work does not reintroduce repo-derived dependency inference as the release source of truth.
8. Any remaining repo-aware startup path is explicitly documented and scoped as development-only rather than release-authoritative.
