# Design Follow-up Steps — 151 edit-clj structural edit extension

- [x] A1: Clarify `ok` result `old`/`new` fields — specify whether they are the argument strings or the actual matched node text from the file (these differ when whitespace differs). Recommend: matched node text for `old`, argument string for `new`.
- [x] A2: Define the `hint` field content for `no-match` and `ambiguous-match` — specify what guidance the hint should provide (e.g. for no-match: suggest adding/widening `start-line`/`end-line`; for ambiguous-match: suggest narrowing the line range).
- [x] A3: State validation order as an explicit contract — specify that old-string is validated first, then new-string, then file open, and that the first error encountered is returned.
- [x] A4: Add `cheshire/cheshire {:mvn/version "5.13.0"}` to the `deps.edn` scope in the design.
- [x] A5: Expand wiring scope — add `bases/main/src/psi/launcher/extensions.clj` (psi-owned-extension-catalog entry + `:psi/init` symbol), top-level `deps.edn` source paths, and top-level `tests.edn` test/source paths to the list of files that must be updated. Clarify the init symbol (`psi.edit-clj.extension/init`).
- [x] A6: Specify that `location` in the `ok` result reports the old node's position (before replacement), as that is the file position the agent used to identify the target.
- [x] A7: Specify that `:execute` must support both `([args])` and `([args opts])` arities, consistent with the `work-on` pattern, to ensure compatibility with callers that may omit opts.
- [ ] I1: Resolve wiring scope conflict — design says wire into `extensions/deps.edn` and `extensions/tests.edn`, but `github` (the stated reference) is NOT in those files; it is wired only via top-level `deps.edn` source paths and `tests.edn`. Decide which model `edit-clj` follows and update the Scope wiring list accordingly.
- [ ] I2: Specify the psi-owned-extension-catalog lib key — name the exact `'psi/...` symbol to use as the map key for the new catalog entry (e.g. `'psi/edit-clj`).
- [ ] I3: Specify `:source-policies` for the catalog entry — state whether the entry should include `:development`, `:installed`, and `:jar` policies (like most psi-owned extensions) or only `:development` and `:installed` (like `github`), since both patterns exist and the design says "follow github" without clarifying which aspects to follow.
