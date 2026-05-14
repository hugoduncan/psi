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

- [x] S1 — `find-candidates` now stores `:zloc` instead of `:node`; `replace-in` uses it directly — no second `z/of-string` + loop.
- [x] S2 — Removed dead `_old-node` first parameter from `replace-in`; updated all call sites.
- [x] S3 — `replace-failed` branch removed (unreachable after S1 eliminated the second walk). S3 resolved by S1.
- [x] S4 — `replace-in` now accepts `new-str` (original argument) as second param; returns it verbatim in `:new`. New `ok-new-verbatim-test` pins whitespace-preservation behaviour.
- [x] S5 — Unified to `n/sexpr` throughout `find-candidates`: `(n/sexpr (z/node z))` for comparison and `(n/sexpr old-node)` for target.
- [x] S6 — Second `comment-in-new-string-preserved-test` subtest now asserts `(= ":y" (n/string (:ok result)))`, documenting that leading (top-level) comments are stripped during form extraction — AC 7 only covers comments *inside* a form.
