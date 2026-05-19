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
