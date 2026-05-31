# Implementation

## Release decisions

- Jar-owned release dependency metadata path: `psi/release-deps.edn`
- Canonical authority for shipped runtime source/resource inclusion: top-level `deps.edn` `:psi` alias `:extra-paths`
- Canonical authority for release external deps: top-level root `:deps` + top-level `:psi` alias `:extra-deps` + nested external deps reachable from shipped runtime owners
- Extension overlap/precedence rule: preserve current effective merge semantics where manifest deps layer additively over the psi base and later manifest entries can override the same library key; make that behavior explicit in tests rather than broadening this task into conflict-policy redesign
- Isolated-install smoke proof shape: build + local install + bbin install into a temp HOME/XDG root, then exercise the installed launcher path from that isolated environment, including a TUI tmux startup path

## Notes

- Release startup must read `psi/release-deps.edn` from the packaged artifact and fail clearly if it is missing or malformed.
- Release startup must not reconstruct psi’s shipped runtime closure from repo `deps.edn` when running under `:jar` policy.

## Closure (2026-05-31)

Closed by 刀 decision. The task `steps.md` (0/8) was never executed in-task, but the substantive work landed via PRs outside the task workflow:

- `build-manifest/release-deps-resource-path` + `release-deps-edn` produce jar-owned release deps metadata; `build.clj` packages it; `launcher.clj` reads it and throws on missing/malformed (no silent repo fallback).
- Motivating `psi --tui` failure fixed by `c1ee394c` (#110 "Fix released --tui by using jar-owned deps startup metadata").
- Related: #109 (library jar packaging drift), #112 (local/root extension startup), `90cc6ef8` (jar defaults for psi-owned extensions).
- Smoke surfaces (`bbin_install_smoke_test`, `build_jar_smoke_test`, `release_packaging_smoke_test`, `launcher_test`) reference `--tui` / release-deps.

Core acceptance criteria are met in the codebase. Any residual AC/doc gaps should be picked up as a fresh, narrowly-scoped task if they surface.
