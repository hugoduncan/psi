# Plan

## Approach

Implement this task as a release-shape tightening slice with four ordered tracks:

1. **Decide and model the release-owned metadata path**
   - Choose the exact jar resource path/name for release dependency metadata.
   - Choose the canonical authority for shipped runtime source/resource inclusion.
   - Preserve a clear split between release startup and repo/dev startup.

2. **Make build packaging artifact-authoritative**
   - Update build packaging to derive the shipped psi runtime source/resource closure mechanically from the chosen authority.
   - Package the chosen release dependency metadata into the jar at the exact stable path.
   - Prove the built jar contains both metadata and shipped psi-owned runtime code/resources.

3. **Make launcher release startup consume artifact-owned truth**
   - Change release startup to read psi’s own basis from the jar-owned metadata path.
   - Preserve extension manifest layering additively on top of that base.
   - Make overlap/precedence behavior explicit and test it.
   - Ensure release startup does not silently fall back to repo-derived dependency resolution.

4. **Prove the shipped path and document it**
   - Add or refine smoke tests so at least one installed/packaged path exercises the artifact users run.
   - Include `--tui` in the release-shaped smoke path.
   - Prove startup succeeds in an isolated install environment without the psi source repo present on disk.
   - Update docs to distinguish release-authoritative startup from dev-only startup.

## Key design decisions to lock during implementation

1. **Jar metadata location**
   - Pick one exact stable path/name inside the jar.
   - Keep launcher, tests, and docs aligned to that single path.

2. **Runtime inclusion authority**
   - Prefer the authoritative released runtime path set already used to describe psi startup.
   - Exclude test-only and non-shipped repo-local paths.

3. **Extension overlap rule**
   - Either preserve and document the current effective precedence rule or explicitly narrow this task to additive non-conflicting layering plus explicit behavior on conflict.

4. **Release proof shape**
   - At least one smoke proof must use the installed or packaged launcher path backed by the built jar, not repo-local `clojure -M` or `bb bb/psi.clj` startup.

## Risks

- Packaging logic may accidentally derive from multiple competing authorities and recreate drift.
- Release startup may retain an implicit repo fallback, hiding broken artifact metadata.
- Extension overlap behavior may remain ambiguous unless forced into a named rule.
- TUI smoke may still pass in repo-local mode while failing in installed mode unless the artifact-shaped path is the one being exercised.

## Sequencing rationale

Do build authority first, then launcher consumption, then artifact-shaped proof/docs.
That order prevents tests from proving a transitional repo-shaped implementation that the release launcher does not actually use.
