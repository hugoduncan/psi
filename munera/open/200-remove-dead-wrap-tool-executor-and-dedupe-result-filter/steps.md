# Steps — 200 Remove dead wrap-tool-executor and de-duplicate tool-result filter

## Slice 1 — Remove dead code, migrate one behaviour

### Pre-flight verification

- [ ] Re-confirm zero production callers: `grep -rn "wrap-tool-executor" components --include="*.clj" | grep -v "_test.clj"` returns only `extensions.clj:335` (the `defn`). Abort and revisit design if any other hit.
- [ ] Confirm `tool-wrapping-test` spans `extensions_test.clj` @386–441 and is the only deftest referencing `ext/wrap-tool-executor`.

### Migrate the non-map-return guard

- [ ] Add a new deftest in `extensions_test.clj` (placed alongside `dispatch-tool-result-coerces-is-error-test`, after line ~473) that calls `ext/dispatch-tool-result-in` directly with a registered `tool_result` handler returning a non-map value (e.g. `"not-a-map"`), and asserts the return is `nil` (no override).
- [ ] Verify the new test fails meaningfully if the `map?` guard is removed (mental/quick check) — it exercises the surviving filter predicate's `map?`/`contains?` guard.

### Remove the wrapper test

- [ ] Delete `tool-wrapping-test` (`extensions_test.clj` @386–441) in full, including its `;; ── Tool wrapping ──` section comment if it becomes orphaned.

### Remove the dead function

- [ ] Delete `wrap-tool-executor` (`extensions.clj`, `defn` @335 through its closing form), leaving `dispatch-tool-result-in` (@322) and `tool-result-event` (@299–313) unchanged.
- [ ] Confirm the modifiable-key contract (`:content`/`:details`/`:is-error`) now appears exactly once in `extensions.clj` — in the `dispatch-tool-result-in` filter predicate (`#(and (map? %) (or (contains? % :content) ...))`).

### Verify

- [ ] `clj-paren-repair components/agent-session/src/psi/agent_session/extensions.clj` and `.../test/psi/agent_session/extensions_test.clj` — balanced + formatted.
- [ ] `clj-kondo --lint` clean on both changed files.
- [ ] Run the extension test namespace via nREPL; all tests pass (no regression on plan-path result override / coercion / block behaviour), and the new non-map test passes.
- [ ] Confirm no remaining reference to `wrap-tool-executor` anywhere: `grep -rn "wrap-tool-executor" components` returns nothing.

### Acceptance check (from design.md)

- [ ] `wrap-tool-executor` removed; modifiable-key contract expressed exactly once; `tool-result-event` unchanged.
- [ ] `tool-wrapping-test` removed; non-map ⇒ no-override behaviour migrated to a direct `dispatch-tool-result-in` test.
- [ ] No production caller breaks (none existed).
- [ ] `clj-kondo` clean on changed files.
- [ ] Existing extension tests pass.

### Commit

- [ ] Commit with a `⚒` build message referencing the removal + single-source contract (e.g. `⚒ Remove dead wrap-tool-executor; single-source tool-result modifiable-key filter`).
