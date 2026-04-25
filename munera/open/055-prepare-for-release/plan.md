# Plan — 055 prepare-for-release

## Approach

Five tracks are largely independent and can be sequenced by dependency:

```
A (changelog discipline)
  └─ feeds ─► E (release workflow — needs changelog extraction)

C (jar build)
  └─ feeds ─► D (smoke test — tests the jar)
               └─ feeds ─► E (release workflow — runs smoke)

B (launcher version) ─► feeds ─► E (release workflow — tags + embeds version)
```

Recommended execution order:

1. **B** — version scheme decision first; everything else references it.
2. **A** — changelog format migration; unblocks E.
3. **C** — jar build; unblocks D.
4. **D** — smoke test; unblocks E.
5. **E** — release workflow; assembles all prior tracks.

## Decisions to make before execution

- Version scheme: calver (`YYYY.0M.0D[.N]`) vs semver (`0.1.0`)?
  - Recommendation: calver for a tool with frequent cadence; semver if API
    stability guarantees are needed.
- Clojars publish: yes/no? (GitHub Releases + bbin is sufficient for now.)
- Changelog format: keep-a-changelog (`## [Unreleased]`) vs current freeform?
  - Recommendation: keep-a-changelog; tooling exists.

## Risks

- `tools.build` uberjar may conflict with dynamic classpath resolution the
  launcher does at runtime; needs verification.
- Smoke test scope: a real end-to-end prompt round-trip requires a mock
  provider; scope to launcher + dispatch pipeline only for the first slice.
- CI job time: release workflow runs full suite; keep it fast by reusing
  cached deps.
