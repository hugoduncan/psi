# Steps

- [ ] Lock the explicit release decisions in implementation notes before broad code movement
  - exact stable jar path/name for release dependency metadata
  - canonical authority for shipped runtime source/resource inclusion
  - explicit extension overlap/precedence rule, or explicit narrow defer if conflict policy is not changing here
  - concrete isolated-install smoke path that will prove release startup without repo layout

- [ ] Update build packaging authority surfaces
  - refine `build.clj` and/or `bases/main/src/psi/build_manifest.clj`
  - derive shipped psi-owned runtime source/resource closure mechanically from the chosen authority
  - package release dependency metadata into the jar at the chosen stable path
  - package shipped psi-owned runtime source/resource trees into the jar
  - ensure test-only and non-shipped repo-local paths are excluded

- [ ] Add focused packaging proofs
  - prove the built jar contains the chosen release metadata path
  - prove the built jar contains representative shipped psi-owned runtime namespaces/resources
  - prove the packaged inclusion set matches the chosen canonical runtime authority, not just a handful of representative entries
  - prove the build-path authority matches the intended runtime inclusion boundary

- [ ] Update launcher release startup
  - make released startup read psi’s basis from the jar-owned metadata path
  - preserve additive extension layering on top of the jar-owned base
  - make the overlap/precedence behavior explicit and test it, either by implementing the chosen rule here or by preserving/documenting the current effective behavior with an explicit defer of broader conflict policy
  - fail clearly rather than silently falling back to repo-derived release startup when artifact metadata is missing or malformed

- [ ] Add focused launcher proofs
  - prove release startup consumes artifact-owned metadata
  - prove release startup is not deriving psi’s own shipped closure from repo/worktree discovery
  - prove missing/malformed artifact metadata fails clearly

- [ ] Strengthen release-shaped smoke coverage
  - make at least one smoke path run the installed or packaged launcher path backed by the built jar
  - include `--tui` startup in that artifact-shaped smoke path
  - prove startup succeeds in an isolated install/smoke environment without the psi source repo present on disk

- [ ] Update docs
  - `README.md`
  - `doc/cli.md`
  - `doc/develop.md`
  - `doc/tui.md`
  - clearly distinguish release-authoritative startup from dev-only startup

- [ ] Final coherence pass
  - re-read design/plan/steps for consistency with the chosen metadata path, runtime inclusion authority, overlap rule, and smoke proof shape
  - ensure tests, docs, and launcher/build language all use the same release model
