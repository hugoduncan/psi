# Implementation Notes — 151 edit-clj structural edit extension

## 2026-05-14 — code-shaper review pass 1

Reviewed `core.clj`, `extension.clj`, `core_test.clj`, `extension_test.clj` against design.md.

**S1 — Double zipper walk + dead `:node` field (simplicity).**
`find-candidates` stores `{:node … :row … :col … :text …}` in each candidate map, but
`replace-in` never reads `:node` — it re-parses `file-content` from scratch and walks again
by position to find the replacement site.  The stored `:node` is dead data and the second
walk is redundant.  Fix: store the zipper loc (`:zloc`) in the candidate map and use it
directly in `replace-in`, eliminating the second `z/of-string` + loop.

**S2 — Dead first parameter `_old-node` in `replace-in` (simplicity).**
`replace-in` accepts `[_old-node new-node file-content candidates]` but `_old-node` is
never read.  The underscore prefix documents the intent, but the parameter itself should be
removed from the public signature to avoid confusing callers and polluting the API surface.

**S3 — `replace-failed` code is untested and undocumented (robustness).**
The `replace-failed` error branch in `replace-in` can be triggered if a candidate's
position is found during `find-candidates` but not re-located in the second walk (e.g.
due to a bug in position tracking).  No test covers this path, and it is absent from the
design's result shapes.  Either add a test that exercises it via a test-double or remove
the branch and let an exception propagate (making the impossible case loud).

**S4 — `ok.new` deviates from design spec (consistency).**
Design says `ok.new` = "the `new-string` argument string verbatim."  Code returns
`(n/string new-node)` where `new-node` is the significant child extracted by
`parse-single-form`.  If `new-string` has leading/trailing whitespace (e.g. `"  :foo  "`),
`n/string` returns `":foo"`, not the argument verbatim.  The test only checks
`(= "(* x 2)" (:new result))` where argument and node-string coincidentally match.
Either update the design to say "node string of the parsed form" or store and return the
original argument string.

**S5 — `n/sexpr` / `z/sexpr` mixing (consistency).**
`find-candidates` computes the target via `(n/sexpr old-node)` but compares candidates
via `(z/sexpr z)`.  `z/sexpr` delegates to `(n/sexpr (z/node z))`, so they are
semantically equivalent, but mixing the two entry points without explanation is a
consistency smell.  Unify to one call site.

**S6 — AC 7 second subtest contradicts the "verbatim" claim (consistency).**
The second case in `comment-in-new-string-preserved-test` asserts that
`";; comment\n:y"` parses successfully but does not assert the comment survives in the
output.  When `new-string` begins with a comment, `parse-single-form` extracts `:y` as the
significant node; `n/string` returns `":y"` — the comment is silently dropped.  The test
comment says "parse must succeed" but says nothing about preservation.  The design AC 7
says "comments inside `new-string` are preserved verbatim."  A leading comment is
arguably not "inside" the form, but the boundary is unstated; the test should either
assert the comment is dropped (document the known limitation) or the design should
explicitly exclude leading comments from the preservation guarantee.

## 2026-05-14 — Ambiguity follow-up execution (design-steps A1–A7)

All seven design follow-up steps executed against design.md:

- **A1**: `ok.old` = matched node text via `rewrite-clj.zip/string`; `ok.new` = `new-string` argument verbatim. Added to Result shapes § Field semantics.
- **A2**: `no-match.hint` = "Try adding or widening the `start-line`/`end-line` range, or verify that `old-string` appears in the file." `ambiguous-match.hint` = "Narrow the `start-line`/`end-line` range to isolate the intended occurrence." Added to Field semantics.
- **A3**: Validation order stated as explicit contract before Matching steps: old-string → new-string → file open; first error returned; both-invalid → old-string error wins.
- **A4**: `cheshire/cheshire {:mvn/version "5.13.0"}` added to Scope deps list alongside rewrite-clj.
- **A5**: Wiring scope expanded in Scope: top-level `deps.edn` source paths, top-level `tests.edn` test/source paths, and `bases/main/src/psi/launcher/extensions.clj` catalog entry with init symbol `psi.edit-clj.extension/init`.
- **A6**: `ok.location` = old node's start position before replacement. Added to Field semantics.
- **A7**: `:execute` must support both `([args])` and `([args opts])` arities per `work-on` pattern. Updated Architecture alignment §.

---

## 2026-05-14 — Inconsistency review pass 1

Reviewed design.md against extensions/deps.edn, extensions/tests.edn, top-level deps.edn, tests.edn, and bases/main/src/psi/launcher/extensions.clj.

### Findings

**I1 — Wiring scope conflicts with the github reference pattern.**
Design says "Wire into `extensions/deps.edn` and `extensions/tests.edn`", but `github` (the stated reference pattern) is NOT wired into `extensions/deps.edn` or the `extensions/tests.edn` `:test` alias — it is wired only into top-level `deps.edn` source paths and `tests.edn`. The two wiring models are mutually inconsistent; the design must choose one and apply it consistently.

**I2 — Catalog lib key (the `'psi/...` symbol) never specified.**
Design says "Add entry to `psi-owned-extension-catalog` with init symbol `psi.edit-clj.extension/init`" but never names the catalog map key (e.g. `'psi/edit-clj`). Every existing entry has an explicit lib symbol key; without it the implementor must guess.

**I3 — Catalog `:source-policies` shape unspecified.**
The design says to add a psi-owned-extension-catalog entry but does not specify `:source-policies`. All psi-owned extensions have `:development` + `:installed` + `:jar` policies *except* `github` (which has no `:jar`). Since the design says "follow github", it is unclear whether `:jar` should be included. This must be stated.

---

## 2026-05-14 — Ambiguity review pass 1

Reviewed design.md against the extension codebase (work-on, github, hello-ext, launcher/extensions.clj, deps.edn, tests.edn).

### Findings

**A1 — `ok` result `old`/`new` field semantics unspecified.**
The design shows `ok → {status, filename, location, old, new}` but does not say whether `old` and `new` are the argument strings or the actual matched node text. These differ when `old-string` has different whitespace than the file node (sexpr equality ignores whitespace). The matched node text is more useful for confirmation.

**A2 — `hint` field content never defined.**
Both `no-match` and `ambiguous-match` include a `hint` field in the result shape, but the design never specifies what the hint should say. Implementors must guess.

**A3 — Validation order / error priority not stated as a contract.**
Steps 1→2→3 imply old-string is validated before new-string, which is validated before file open. But this ordering is not stated as an explicit contract. If both strings are invalid, which error is returned? If new-string is invalid but the file doesn't exist, which error wins?

**A4 — `cheshire` missing from deps spec.**
The design requires `cheshire.core/generate-string` for JSON serialization but the deps.edn scope only names `rewrite-clj/rewrite-clj`. Cheshire must also be listed.

**A5 — Wiring scope underspecified; launcher catalog omitted.**
Design says "Wire into `extensions/deps.edn` and `extensions/tests.edn`" but the actual system-wide wiring requires four additional files: `bases/main/src/psi/launcher/extensions.clj` (catalog entry + init symbol), top-level `deps.edn` source paths, and top-level `tests.edn` test/source paths. The launcher catalog is the critical gap — without it the extension is never loaded at runtime.

**A6 — `ok` result `location` field: old node or new node position?**
Not specified. Since the file is written after replacement, the old node's file position is the meaningful one for the agent to confirm the correct location was edited.

**A7 — `:execute` arity: single or dual?**
Design specifies `(args opts)` but `work-on` uses two arities `([args] [args _opts])`. Not stated whether single-arity is sufficient or dual-arity is required for compatibility.

---

## 2026-05-14 — Inconsistency follow-up execution (design-steps I1–I3)

Investigated the actual codebase to resolve three wiring/catalog inconsistencies. Verified against `extensions/deps.edn`, `extensions/tests.edn`, top-level `deps.edn`, and `bases/main/src/psi/launcher/extensions.clj`.

- **I1 — Wiring model resolved**: `github` is absent from `extensions/deps.edn` (not listed as a `:local/root` dep) and absent from the `extensions/tests.edn` `:test` alias extra-paths. It is wired exclusively via top-level `deps.edn` source paths and top-level `tests.edn`. Updated Scope in design.md: removed the `extensions/deps.edn`/`extensions/tests.edn` bullet; replaced with explicit top-level-only wiring statement (`extensions/edit-clj/src` in source paths, `extensions/edit-clj/test` + `extensions/edit-clj/src` in test paths), with an explicit note that github follows the same model.
- **I2 — Catalog lib key specified**: `'psi/edit-clj` — follows the `'psi/<extension-name>` pattern used by all existing entries. Added to Scope catalog entry spec in design.md.
- **I3 — `:source-policies` shape specified**: `:development` + `:installed` only, no `:jar`. `github` is the only psi-owned extension without `:jar`; since design says "follow github", no `:jar` policy. Added to Scope catalog entry spec in design.md.
