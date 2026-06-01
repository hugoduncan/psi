# Steps — 200 Remove dead wrap-tool-executor and de-duplicate tool-result filter

## Slice 1 — Remove dead code, migrate one behaviour

### Pre-flight verification

- [x] Re-confirm zero production callers: `grep -rn "wrap-tool-executor" components --include="*.clj" | grep -v "_test.clj"` returns only `extensions.clj:335` (the `defn`). Confirmed — only the `defn` plus test references.
- [x] Confirm `tool-wrapping-test` spans `extensions_test.clj` @386–441 and is the only deftest referencing `ext/wrap-tool-executor`. Confirmed.

### Migrate the non-map-return guard

- [x] Add a new deftest `dispatch-tool-result-non-map-return-test` in `extensions_test.clj` (placed after `dispatch-tool-result-coerces-is-error-test`) that calls `ext/dispatch-tool-result-in` directly with a registered `tool_result` handler returning `"not-a-map"`, asserting the return is `nil` (no override).
- [x] The new test exercises the surviving filter predicate's `map?`/`contains?` guard — a non-map handler return is rejected, so no modifiable-key override is produced.

### Remove the wrapper test

- [x] Deleted `tool-wrapping-test` (`extensions_test.clj`) in full, including its `;; ── Tool wrapping ──` section comment.

### Remove the dead function

- [x] Deleted `wrap-tool-executor` (`extensions.clj`, `defn` @335), leaving `dispatch-tool-result-in` and `tool-result-event` unchanged.
- [x] Confirmed the modifiable-key contract (`:content`/`:details`/`:is-error`) now appears exactly once in `extensions.clj` — in the `dispatch-tool-result-in` filter predicate (line 331).

### Verify

- [x] `clj-paren-repair` on both changed files — balanced + formatted (no changes needed).
- [x] `clj-kondo --lint` clean on both changed files (0 errors, 0 warnings).
- [x] Ran the extension test namespace (Kaocha focus): 26 tests, 94 assertions, 0 failures — including the new non-map test.
- [x] Confirmed no remaining reference to `wrap-tool-executor` anywhere: `grep -rn "wrap-tool-executor" components` returns nothing.

### Acceptance check (from design.md)

- [x] `wrap-tool-executor` removed; modifiable-key contract expressed exactly once; `tool-result-event` unchanged.
- [x] `tool-wrapping-test` removed; non-map ⇒ no-override behaviour migrated to a direct `dispatch-tool-result-in` test.
- [x] No production caller breaks (none existed).
- [x] `clj-kondo` clean on changed files.
- [x] Existing extension tests pass.

### Commit

- [x] Committed with a `⚒` build message.

## Test review follow-ups (task-test-review, 2026-06-01)

- [x] T1a: Add a positive-selection test for `dispatch-tool-result-in`: register
  a `tool_result` handler returning a map containing a modifiable key (e.g.
  `{:content "override"}`), assert `dispatch-tool-result-in` returns that map
  (the override is selected). Covers the surviving filter predicate's
  `(or (contains? :content) (contains? :details) (contains? :is-error))` branch
  — currently untested, yet it is the single-sourced modifiable-key contract.
  Added `dispatch-tool-result-modifiable-key-override-test`.
- [x] T1b: Add a negative test: register a handler returning a map containing
  *none* of `:content`/`:details`/`:is-error` (e.g. `{:other 1}`), assert
  `dispatch-tool-result-in` returns `nil` (the `contains?` guard rejects it).
  Completes coverage of the predicate's modifiable-key branch.
  Added `dispatch-tool-result-map-without-modifiable-key-test`.

## Test review follow-ups (task-test-review second pass, 2026-06-01)

- [x] T2a: Add a positive-selection test for the `:details`-only branch of the
  `dispatch-tool-result-in` filter predicate: register a `tool_result` handler
  returning a map containing `:details` and *no* `:content`/`:is-error` (e.g.
  `{:details {:k :v}}`), assert `dispatch-tool-result-in` returns that map.
  Covers the `(contains? % :details)` disjunct of the single-sourced
  modifiable-key contract, currently unprotected (only the `:content` disjunct
  is tested by `dispatch-tool-result-modifiable-key-override-test`).
  Added `dispatch-tool-result-details-only-override-test`.
- [x] T2b: Add a positive-selection test for the `:is-error`-only branch:
  register a handler returning a map containing `:is-error` and *no*
  `:content`/`:details` (e.g. `{:is-error true}`), assert
  `dispatch-tool-result-in` returns that map. Covers the
  `(contains? % :is-error)` disjunct so each branch of the modifiable-key `or`
  is protected against silent removal.
  Added `dispatch-tool-result-is-error-only-override-test`.
