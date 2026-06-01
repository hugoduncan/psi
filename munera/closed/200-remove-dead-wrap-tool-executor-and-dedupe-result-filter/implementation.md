# Implementation notes — 200

## Design review: ambiguity pass (2026-06-01)

Reviewed design.md against `components/agent-session/src/psi/agent_session/extensions.clj`
and `.../test/psi/agent_session/extensions_test.clj`. Verified: zero production
callers of `wrap-tool-executor` (only `defn` + tests). Found 3 actionable
ambiguities → added to design-steps.md:

- A1 Scope-decision criterion is undetermined: the "compatibility wrapper" test
  (extensions_test.clj:393) only asserts pass-through behaviour; its name is not
  evidence of an intentional public surface, yet the design uses it to gate
  direction 2 (keep) against the confirmed zero-caller fact for direction 1
  (remove). The planner cannot deterministically pick a direction as written.
- A2 The "expressed once" key-set contract names only 2 sites
  (`dispatch-tool-result-in` filter + `wrap-tool-executor` `cond->`), but
  `tool-result-event` (extensions.clj:311–313) is a third co-located
  enumeration of the same `:content`/`:details`/`:is-error` keys (also used by
  `tool_runtime_adapter.clj`). Unspecified whether single-sourcing must subsume it.
- A3 "delete or migrate its test coverage" (direction 1) does not state what
  behaviour must remain covered after removal. (`dispatch-tool-result-in` filter
  is already independently tested at extensions_test.clj:453–471, but the design
  does not say so, leaving migrate-vs-delete undetermined.)

## Ambiguity follow-up execution (2026-06-01)

Executed A1–A3. All resolved in design.md (no blockers).

- A1 → **direction 1 (remove)**. Deterministic rule added: zero production
  callers ∧ ¬documented-public-surface ⇒ internal dead code. Test *name*
  ("compatibility wrapper") is not surface evidence. Both conditions hold for
  `wrap-tool-executor` ⇒ remove. Scope section rewritten from "to be decided"
  to "decided".
- A2 → `tool-result-event` **intentionally excluded**. It is the bus-event
  *payload constructor* (cross-path shape), a different concern from the
  *modifiable-key* contract. `tool_runtime_adapter.clj` consumes the constructed
  payload, not the contract. After removing `wrap-tool-executor` the contract is
  expressed once (the `dispatch-tool-result-in` filter) — no shared helper
  needed. Added to Out of Scope.
- A3 → coverage audit added. Of `tool-wrapping-test`'s behaviours: coercion/
  normalization already covered by `dispatch-tool-result-{normalizes-content,
  coerces-is-error}-test`; `:block` detection covered by dispatch tests; the one
  behaviour to **migrate** is the non-map-return ⇒ no-override filter guard
  (currently only exercised through the wrapper) → add a direct
  `dispatch-tool-result-in` non-map test. Acceptance Criteria updated to make
  removal + single-migration explicit.

## Design review: inconsistency pass (2026-06-01)

Reviewed design.md against `extensions.clj` and `extensions_test.clj` (and
`tool_runtime_adapter.clj`). Found 3 actionable inconsistencies → added to
design-steps.md. None duplicate the prior ambiguity pass.

- I1 (substantive): A2's justification misdescribes `tool_runtime_adapter.clj`.
  Design says the adapter "reads those keys *off the constructed event* … i.e.
  it consumes the payload." Code reads `:content`/`:details`/`:is-error` from the
  *incoming* `lifecycle-event` and passes them *into* `tool-result-event` as
  constructor args (`extensions.clj:37–42` adapter call). The adapter sources the
  constructor inputs; it does not consume `tool-result-event`'s output. The
  factual basis for the A2 exclusion is inverted.
- I2 (citation): `tool-result-event` cited as `extensions.clj:311–313` (Context,
  A2, Out of Scope) but the `defn` spans **299–313**; 311–313 are only its
  trailing `:content`/`:details`/`:is-error` map entries — mislabels the function.
- I3 (citation): A3 cites the two coercion tests as `extensions_test.clj:453–490`;
  actual span is **445–473** (`dispatch-tool-result-normalizes-content-test` @445,
  `dispatch-tool-result-coerces-is-error-test` @459). Line 453 is mid-first-test;
  490 falls inside the unrelated `tool-event-payload-constructors-test` (@475).
  (design-steps.md A3 separately cites `453–471` — also inaccurate.)

## Inconsistency follow-up execution (2026-06-01)

Executed I1–I3. All resolved in design.md (no blockers). Verified against source.

- I1 → A2 prose corrected. Verified `tool_runtime_adapter.clj:37–42`: adapter
  reads `:content`/`:details`/`:is-error` off the *incoming* `lifecycle-event`
  (40–42) and passes them as *constructor args into* `ext/tool-result-event`
  (37). It sources inputs, does not consume the constructed payload — the prior
  prose was inverted. Reworded to "sources the constructor inputs"; A2 exclusion
  conclusion still holds (it touches payload shape, not the modifiable-key
  contract).
- I2 → citations corrected. Verified `extensions.clj`: `tool-result-event`
  `defn` spans **299–313** (311–313 are only its trailing map entries). Fixed
  Context (added explicit entry), A2, and Out of Scope to cite `defn` at 299–313.
- I3 → A3 citations corrected. Verified `extensions_test.clj`:
  `dispatch-tool-result-normalizes-content-test` @445,
  `dispatch-tool-result-coerces-is-error-test` @459 (ends 473);
  `tool-event-payload-constructors-test` @475 (unrelated). Two coercion tests
  span **445–473**. Fixed design.md A3 (`453–490` → `445`/`459`, span 445–473)
  and reconciled design-steps.md A3 (`453–471` → `445–473`).

## Slice 1 execution (2026-06-01)

Implemented as designed; no deviations from plan.

- Re-confirmed zero production callers (only the `defn` + tests).
- Added `dispatch-tool-result-non-map-return-test` after
  `dispatch-tool-result-coerces-is-error-test` (extensions_test.clj:413):
  registers a `tool_result` handler returning `"not-a-map"`, asserts
  `dispatch-tool-result-in` returns `nil`. Migrates the one wrapper-only
  behaviour (map?/contains? guard).
- Removed `tool-wrapping-test` (and its `;; ── Tool wrapping ──` section comment).
- Removed `wrap-tool-executor` from extensions.clj. Modifiable-key contract now
  appears exactly once (filter predicate, extensions.clj:331).
- Verify: clj-paren-repair (no changes), clj-kondo clean (0/0), Kaocha focus
  `psi.agent-session.extensions-test` → 26 tests, 94 assertions, 0 failures.
  No project nREPL available; used Kaocha runner instead.

## Implementation review: task-implementation-review (2026-06-01)

Reviewed code/tests against design.md + plan.md. Verified at runtime, not just docs.

- **matches design** ✓ `wrap-tool-executor` removed (grep: no refs anywhere in
  `components`); modifiable-key contract (`:content`/`:details`/`:is-error`)
  expressed exactly once — `dispatch-tool-result-in` filter predicate
  (extensions.clj:331). `tool-result-event` (defn 299–313) and
  `tool_runtime_adapter.clj` (37–42, sources constructor inputs off the incoming
  `lifecycle-event`) untouched — confirmed against source; matches corrected A2/I1.
- **architecture** ✓ pure removal of dead code; no shim/adapter introduced; no
  `one_way` violation.
- **test quality** ✓ `dispatch-tool-result-non-map-return-test` is non-vacuous:
  the handler IS registered, so `"not-a-map"` flows into `dispatch-in` `:results`
  and the `map?` guard is genuinely exercised (verified `dispatch-in` shape,
  extensions.clj:240–264). Migrates the one wrapper-only behaviour.
- **no unnecessary abstraction / no new pattern** ✓ new test reuses the existing
  direct `dispatch-tool-result-in` call pattern; change reduces abstraction.
- **runtime verification** ✓ clj-kondo clean (0/0 on both changed files);
  `clojure -M:test:kaocha --focus psi.agent-session.extensions-test` →
  26 tests, 94 assertions, 0 failures (re-run during this review).

No new actionable issues. REVIEW_COMPLETE.

## Test review: task-test-review (2026-06-01)

Applied task-test-review (well-formed ∧ behaviour-coverage ∧ injectable-nullable
infra deps). Verified at runtime: 26 tests, 94 assertions, 0 failures.

- **well-formed** ✓ Tests use `deftest`/`testing`/`is`, assert on return values
  (state/output), not interactions. Real `create-registry` used; no mocks/stubs.
- **infra deps** ✓ No infra deps to null — handlers are plain in-test fns; the
  registry is the real domain object.
- **behaviour coverage** ✗ One gap (actionable, T1 below).

T1 (actionable — coverage gap on the single-sourced contract): the task's stated
purpose is that the `dispatch-tool-result-in` filter predicate
(extensions.clj:330–332) becomes the *sole* expression of the modifiable-key
contract. That predicate has two guard branches: `(map? %)` AND
`(or (contains? % :content) (contains? % :details) (contains? % :is-error))`.
The migrated `dispatch-tool-result-non-map-return-test` exercises only the
`map?` branch (handler returns `"not-a-map"` ⇒ nil). The `contains?` branch —
which *is* the modifiable-key contract — is untested in both directions:
  - **positive (selection):** no test feeds a handler return that is a map
    containing a modifiable key and asserts it is selected/returned as the
    override. The two coercion tests (extensions_test.clj:383/397) register
    handlers returning `nil` (`(fn [p] (reset! payload p) nil)`), so they
    exercise plan-path payload construction, never the filter's positive select.
  - **negative (map-without-keys):** no test feeds a map handler return lacking
    all three keys (expected ⇒ nil).
A `:content`/`:details`/`:is-error` key could be dropped from the predicate (or
the positive selection broken) with the whole suite still green. The design A3
audit framed migration around the "non-map filter return-shape" only and missed
that the surviving predicate is now the single source of the modifiable-key
contract whose *selection* behaviour has no test. Add a positive-selection test
(and ideally the map-without-keys negative) so the single-sourced contract is
covered.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review follow-up execution (2026-06-01)

Executed T1a and T1b from the task-test-review pass. Both close the coverage gap
on the surviving `dispatch-tool-result-in` filter predicate's `contains?` branch
— the single source of the modifiable-key contract.

- T1a → `dispatch-tool-result-modifiable-key-override-test`: handler returns
  `{:content "override"}`; asserts `dispatch-tool-result-in` returns that map
  (positive selection of the modifiable-key branch). Placed after
  `dispatch-tool-result-non-map-return-test`.
- T1b → `dispatch-tool-result-map-without-modifiable-key-test`: handler returns
  `{:other 1}` (no `:content`/`:details`/`:is-error`); asserts return is `nil`
  (the `contains?` guard rejects map returns lacking all modifiable keys).
- Verify: clj-paren-repair (no changes), clj-kondo clean (0/0); Kaocha focus
  `psi.agent-session.extensions-test` → 28 tests, 96 assertions, 0 failures
  (was 26/94 before; +2 tests, +2 assertions). No deviations.
