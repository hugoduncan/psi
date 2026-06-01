# Plan — 200 Remove dead wrap-tool-executor and de-duplicate tool-result filter

## Approach

Direction 1 (remove) is already decided in design.md by the A1 deterministic
rule: `wrap-tool-executor` has zero production callers and no documented public
surface ⇒ internal dead code ⇒ remove. Removal alone satisfies the
"modifiable-key contract expressed once" acceptance criterion (the `cond->` in
`wrap-tool-executor` was the second expression; after removal only the
`dispatch-tool-result-in` filter predicate remains). No shared helper/key-set is
introduced. `tool-result-event` (`defn` 299–313) is intentionally untouched
(A2: payload constructor, a separate concern).

Key decisions, all inherited from the stable design:

1. **Remove `wrap-tool-executor`** (`extensions.clj`, `defn` @335). It is dead
   production code; its post-result `cond->` is the duplicated modifiable-key
   contract.
2. **Remove `tool-wrapping-test`** (`extensions_test.clj` @386–441) — it only
   exercises the removed function.
3. **Migrate exactly one behaviour**: the non-map handler-return ⇒ no-override
   guard, currently only reached through the wrapper's last sub-test
   (@436–441). Add a direct `dispatch-tool-result-in` test asserting a non-map
   handler return yields `nil`, preserving coverage of the surviving filter
   predicate's `map?`/`contains?` guard.
4. **No migration** for coercion/normalization (already covered by
   `dispatch-tool-result-normalizes-content-test` @445 and
   `dispatch-tool-result-coerces-is-error-test` @459, span 445–473) or `:block`
   detection (covered by plan-path / `dispatch-in` tests).

Verification before/during: confirm zero production callers (already verified —
only `defn` + tests), then remove, migrate the one test, and run `clj-kondo` +
the extension test namespace.

## Risks

- **Low — false sense of "no callers".** Mitigated: grep over
  `components/**.clj` excluding `*_test.clj` confirms only the `defn` itself.
  Re-confirm at execution time before deleting (the grep is in steps).
- **Low — coverage gap on the filter predicate.** Removing
  `tool-wrapping-test` deletes the only test reaching the non-map guard via the
  wrapper. Mitigated by the explicit migration step (a direct
  `dispatch-tool-result-in` non-map test) executed in the same slice as the
  removal.
- **Low — accidental removal of `tool-result-event` or its `cond->`-adjacent
  constructor logic.** Mitigated: `tool-result-event` (299–313) and
  `tool_runtime_adapter.clj` (37–42) are explicitly out of scope; only the
  `wrap-tool-executor` `defn` (@335) and `tool-wrapping-test` (@386–441) are
  deleted.

## Slice order

Single vertical slice (small δ: one intent, one rule cluster, one test cluster).

1. **Slice 1 — Remove dead code and migrate the one uncovered behaviour.**
   - Add the migrated direct `dispatch-tool-result-in` non-map test.
   - Remove `tool-wrapping-test`.
   - Remove `wrap-tool-executor`.
   - Lint + run extension tests + verify acceptance criteria.

(No separate spec/meta artifacts: this is a code-shape removal under existing
behaviour; the design.md acceptance criteria are the spec surface. No doc
changes — `wrap-tool-executor` is not user-facing.)
