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

## Test review follow-ups (test-shaper, 2026-06-01)

- [x] S1: Extract a shared helper for the `dispatch-tool-result-*` test cluster
  (extensions_test.clj:383–478) that registers one `tool_result` handler
  returning a fixed value and invokes `dispatch-tool-result-in`, compressing the
  6× repeated `create-registry`/`register-extension-in!`/`register-handler-in!`/
  5-arg-dispatch ceremony so each test states only its varying axis
  (handler return → selected map / nil). Helper must compress ceremony without
  hiding intent (keep the handler-return and expected-override visible per test).
  Added `result-override` helper; rewrote the 5 override-selection tests to one
  visible `(is (= <expected> (result-override <handler-return>)))` line each.
  (The two payload-capturing coercion tests vary the *input* result/is-error?
  and assert on the captured incoming payload, a different axis, so they are
  intentionally left outside the override-selection helper.)
- [x] S2: Remove the incidental, never-asserted original-result detail
  (`{:content "original" :is-error false}`) from the override-selection tests —
  fold it into the S1 helper as a single fixed constant (or an inert marker) so
  the reader focuses on the handler-return vs expected-override contract, not the
  discarded original payload.
  Replaced with the inert marker `inert-original-result` (`::original`) held
  inside the `result-override` helper; no test references it.

## Test review follow-ups (test-shaper second pass, 2026-06-01)

- [x] S3: Extract a sibling helper for the payload-capture coercion tests
  (`dispatch-tool-result-normalizes-content-test` @400,
  `dispatch-tool-result-coerces-is-error-test` @414), e.g.
  `(capture-payload <result> <is-error?>)` that registers the
  `(fn [p] (reset! payload p) nil)` capture handler, invokes
  `dispatch-tool-result-in`, and returns the captured incoming payload. Rewrite
  the two coercion tests so each states only its varying input axis and asserted
  field — `(is (= … (:content (capture-payload {:content "raw string"} false))))`
  and `(is (false?/true? (:is-error (capture-payload {…} <raw>))))`. Compress the
  repeated `create-registry`/`register-extension-in!`/`register-handler-in!`/
  6-arg-dispatch ceremony (3 inline dispatch calls) without hiding intent (keep
  input result/`is-error?` and asserted field visible). This also restores
  fixture/abstraction consistency across the cluster (override tests use
  `result-override`; coercion tests should use the sibling helper rather than
  hand-rolling). Run clj-kondo + Kaocha focus; coverage must be unchanged
  (pure shape change, 30/98 expected).

## Docs review follow-ups (review-task-docs, 2026-06-01)

- [x] D1: Fix stale internal terminology in the user-facing
  `doc/extensions.md:898` "Implementation" namespace table — the
  `psi.agent-session.extensions` Role still reads "Registry, loading, event
  dispatch, **tool wrapping**", but the `wrap-tool-executor` tool-wrapping
  mechanism it named was removed in slice 1 (zero production callers). The
  namespace now dispatches/filters `tool_result` events via
  `dispatch-tool-result-in`. Update the Role label (e.g. "…event dispatch,
  tool-result filtering" or drop the "tool wrapping" clause). Leave the public
  "Tool Wrapping" event-API section (`doc/extensions.md:550`) and its
  `#tool-wrapping` anchors unchanged — that section documents the surviving
  event-subscription capability, not the removed internal function.

## Code review follow-ups (code-shaper, 2026-06-01)

- [x] C1: The modifiable-key contract (`#{:content :details :is-error}`) is
  expressed in **two** live production sites, not one — contradicting the
  task's "expressed exactly once" acceptance criterion. Besides the
  `dispatch-tool-result-in` *selection* predicate
  (`extensions.clj:331–332`), the *application* `cond->` in
  `tool_plan.clj:222–224` re-enumerates the same three keys to copy the
  override into the result. They are the producer/consumer of one contract and
  must stay in lockstep, with no compiler/lint enforcement of agreement
  (robust: invariant not enforceable). The design enumerated only the
  filter + the (now-removed) wrapper `cond->` and missed the live
  `tool_plan.clj` `cond->`. Single-source the contract: introduce a named
  modifiable-key set / shared helper (e.g. `modifiable-tool-result-keys`, or a
  `merge-tool-result-override` / `select-tool-result-override` fn in
  `extensions.clj`) used by both the selection predicate and the
  `tool_plan.clj` application `cond->`, so the key set is enumerated once and
  both sites derive from it. If task scope must stay fixed at pure removal,
  instead correct design.md / Acceptance Criteria to state the contract spans
  two coupled production sites (not one) — but the honest fix is single-sourcing
  across the producer/consumer pair. Run clj-kondo + Kaocha focus
  `psi.agent-session.extensions-test` (plus any tool-plan tests touched);
  behaviour must be unchanged.
  Single-sourced via a named `modifiable-tool-result-keys` set in
  `extensions.clj`; selection guard (`modifiable-tool-result-override?`) and
  application (`merge-tool-result-override`) both derive from it; `tool_plan.clj`
  now calls `merge-tool-result-override` instead of re-enumerating the keys.
