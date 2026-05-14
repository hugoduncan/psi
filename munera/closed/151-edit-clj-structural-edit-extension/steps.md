# Steps — 151 edit-clj structural edit extension

## Slice 1 — Pure core

- [x] Create `extensions/edit-clj/deps.edn`
  - runtime: `rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}`, `cheshire/cheshire {:mvn/version "5.13.0"}`
  - `:test` alias: kaocha + `psi/extension-test-helpers` + `psi/agent-session` (mirror `extensions/github/deps.edn`)

- [x] Implement `psi.edit-clj.core/parse-single-form`
- [x] Implement `psi.edit-clj.core/find-candidates`
- [x] Implement `psi.edit-clj.core/apply-line-filter`
- [x] Implement `psi.edit-clj.core/replace-in`

- [x] Write `extensions/edit-clj/test/psi/edit_clj/core_test.clj`
  - AC 1–4, 6a–f, 7, 8 all covered

- [x] Verify slice 1: 12 tests, 45 assertions, 0 failures; lint clean

## Slice 2 — Extension shell

- [x] Implement `psi.edit-clj.extension/resolve-path`
- [x] Implement `psi.edit-clj.extension/execute` (validation order: old-string → new-string → file)
- [x] Implement `psi.edit-clj.extension/tool-def` (≤20 words description, dual-arity :execute)
- [x] Implement `psi.edit-clj.extension/init` (register tool via `(:register-tool api)`)

- [x] Write `extensions/edit-clj/test/psi/edit_clj/extension_test.clj`
  - AC 1, 2, 3, 4, 5, 9, 10 all covered; uses `create-nullable-extension-api`

- [x] Verify slice 2: 19 tests, 73 assertions, 0 failures; lint clean

## Slice 3 — Wiring

- [x] Add `"extensions/edit-clj/src"` to every alias in top-level `deps.edn` that carries `"extensions/github/src"` (4 locations); also added `"extensions/edit-clj/test"` to `:test` alias; added `rewrite-clj/rewrite-clj` to all runtime + test `:extra-deps`

- [x] Add `"extensions/edit-clj/src"` and `"extensions/edit-clj/test"` to `tests.edn` (unit, extensions, integration suites)

- [x] Add `'psi/edit-clj` entry to `psi-owned-extension-catalog` in `bases/main/src/psi/launcher/extensions.clj`

- [x] Verify: 1776 unit tests + 169 extension tests, 0 failures; lint clean on all changed files

## code-shaper follow-up (review pass 1)

- [ ] S1 — Eliminate double zipper walk: store `:zloc` (not `:node`) in candidate maps from `find-candidates`; use it directly in `replace-in` instead of re-parsing `file-content` and re-walking by position. Remove the second `z/of-string` + loop from `replace-in`.
- [ ] S2 — Remove dead `_old-node` first parameter from `replace-in`; update all call sites (only `extension.clj` calls it).
- [ ] S3 — Resolve `replace-failed` branch: either add a focused test that reaches it (e.g. via a test-double that injects a candidate with a position that does not exist in the file) and add it to the design's result shapes, or remove the branch and let an exception surface the impossible case loudly.
- [ ] S4 — Clarify `ok.new` semantics: decide whether to return the `new-string` argument verbatim (store original string alongside `new-node` in `parse-single-form` result) or update design to say "node string of the parsed form"; add a test with leading/trailing whitespace in `new-string` to pin the chosen behaviour.
- [ ] S5 — Unify `n/sexpr` / `z/sexpr` usage in `core.clj`: pick one entry point for all sexpr calls and apply consistently.
- [ ] S6 — Document the leading-comment boundary in AC 7: add an assertion to the second subtest of `comment-in-new-string-preserved-test` that explicitly states whether the leading comment is preserved or dropped, and update the design AC 7 note to exclude (or include) leading-comment preservation.
