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

## Test review: task-test-review (second pass, 2026-06-01)

Re-applied task-test-review after the T1a/T1b follow-ups landed. Verified at
runtime: 28 tests, 96 assertions, 0 failures (Kaocha focus
`psi.agent-session.extensions-test`).

- **well-formed** ✓ `deftest`/`testing`/`is`; assert on return values; real
  `create-registry`, no mocks/stubs. New tests are non-vacuous (handlers
  registered; returns flow through `dispatch-in` into the filter).
- **infra deps** ✓ None to null.
- **behaviour coverage** ✗ One residual gap (actionable, T2 below).

T2 (actionable — per-key selection coverage of the single-sourced contract):
the surviving `dispatch-tool-result-in` filter predicate (extensions.clj:331–333)
is the *sole* expression of the modifiable-key contract, an `or` over three
independent branches: `(contains? :content)`, `(contains? :details)`,
`(contains? :is-error)`. T1a's `dispatch-tool-result-modifiable-key-override-test`
proves positive selection only for the `:content` branch; T1b proves rejection
of maps with *none* of the keys. The `:details`-only and `:is-error`-only
positive-selection branches have no test: a handler returning `{:details {...}}`
or `{:is-error true}` (and no `:content`) is never asserted to be selected as the
override. Each `or` disjunct is independently mutable — dropping `:details` or
`:is-error` from the predicate would leave the whole suite green, silently
narrowing the very contract this task exists to single-source. The prior T1 pass
closed the `:content` branch and the all-keys-absent boundary but did not split
the modifiable-key set into its per-key disjuncts. Add a positive-selection test
for the `:details`-only and `:is-error`-only handler returns so each disjunct of
the single-sourced contract is protected against silent removal.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review follow-up execution (second pass, 2026-06-01)

Executed T2a and T2b from the second task-test-review pass. Both add
per-disjunct positive-selection coverage for the surviving
`dispatch-tool-result-in` filter predicate's modifiable-key `or`, so each
branch of the single-sourced contract is independently protected.

- T2a → `dispatch-tool-result-details-only-override-test`: handler returns
  `{:details {:k :v}}` (no `:content`/`:is-error`); asserts
  `dispatch-tool-result-in` returns that map (positive selection of the
  `(contains? % :details)` disjunct). Placed after
  `dispatch-tool-result-map-without-modifiable-key-test`.
- T2b → `dispatch-tool-result-is-error-only-override-test`: handler returns
  `{:is-error true}` (no `:content`/`:details`); asserts return is that map
  (positive selection of the `(contains? % :is-error)` disjunct).
- Verify: clj-paren-repair (no changes), clj-kondo clean (0/0); Kaocha focus
  `psi.agent-session.extensions-test` → 30 tests, 98 assertions, 0 failures
  (was 28/96 before; +2 tests, +2 assertions). No deviations.

## Test review: task-test-review (third pass, 2026-06-01)

Re-applied task-test-review after T2a/T2b landed. Verified at runtime: 30 tests,
98 assertions, 0 failures (Kaocha focus `psi.agent-session.extensions-test`).

- **well-formed** ✓ `deftest`/`testing`/`is`; assert on return values, not
  interactions. Real `create-registry`; handlers are plain in-test fns; no
  mocks/stubs.
- **infra deps** ✓ None to null — registry is the real domain object.
- **behaviour coverage** ✓ The surviving `dispatch-tool-result-in` filter
  predicate (the single-sourced modifiable-key contract, extensions.clj:330–332)
  is now fully covered: `map?` guard (non-map ⇒ nil); each modifiable-key
  disjunct positively selected (`:content` T1a, `:details` T2a, `:is-error`
  T2b); map-without-modifiable-keys rejected (T1b). Coercion/normalization
  covered by `dispatch-tool-result-{normalizes-content,coerces-is-error}-test`.
  No predicate branch is silently mutable.

No new actionable test issues within this task's scope. The remaining untested
behaviours touching `dispatch-tool-result-in` — `first (filter …)` first-writer
selection across multiple modifiable handler returns, and the `{:error …}`
handler-exception map being rejected by the filter — are pre-existing `dispatch-in`
semantics neither introduced nor changed by this task (override precedence is
covered by `dispatch-override-test`; exception wrapping by `dispatch-exception-test`).
They are out of scope for this dead-code-removal / single-sourcing task.

PASS_STATUS: REVIEW_COMPLETE

## Test review: test-shaper (2026-06-01)

Applied test-shaper (clarity ∧ signal ∧ robustness ∧ economy) to the
`dispatch-tool-result-*` test cluster (extensions_test.clj:383–478). Prior passes
were task-test-review (coverage-focused) and reached full branch coverage; this
pass reviews test *shape*, a distinct lens. Two actionable shape issues (S1, S2);
no coverage regressions.

S1 (actionable — economy / repeated ceremony): the six `dispatch-tool-result-*`
tests (383–478) each rebuild identical scaffolding — `create-registry` +
`register-extension-in!` + `register-handler-in!` + the full 5-arg
`dispatch-tool-result-in` call (`"read" "call-N" {"path" "x"} {…original…}
false`). Only the handler return and the expected value vary. This is
incidental setup repeated 6× (`λ economical`: `minimal(incidental_variation)`;
`prefer helpers_that_compress(ceremony)`). No shared helper exists (confirmed:
the test ns defines none for this cluster). Extract a helper that registers a
single `tool_result` handler returning a fixed value and invokes
`dispatch-tool-result-in`, e.g. `(result-override <handler-return>) ⇒
<dispatch return>`, so each test states only its one varying axis (return →
selected/nil). This compresses ceremony without hiding intent.

S2 (actionable — incidental detail obscures signal): the override-selection
tests (`-modifiable-key-override`, `-map-without-modifiable-key`,
`-details-only-override`, `-is-error-only-override`, `-non-map-return`) pass an
*original* result `{:content "original" :is-error false}` that is never
asserted against — the override fully replaces it, so the literal "original"
content is incidental noise that does not aid comprehension of the
filter-selection behaviour under test (`λ simple`: `minimal_incidental_setup`,
`¬embed(unrelated_details)`). Either fold the original payload into the S1
helper as a single fixed constant (so it stops varying / drawing attention) or
use a clearly-inert marker, so the reader's eye lands on the handler-return vs
expected-override contract, not the discarded original.

Note (¬actionable, recorded for shape rationale): the four override-selection
tests are one parameterized behaviour ("which handler return is selected") split
per-disjunct deliberately (per the T2 coverage rationale — each `or` disjunct
must be independently mutation-protected). Keeping them as distinct deftests
preserves per-branch failure signal (`meaningful_failures`); they need not be
collapsed into one `are`-table. The S1 helper alone suffices to remove the
ceremony while retaining per-test signal.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review follow-up execution (test-shaper pass, 2026-06-01)

Executed S1 and S2 from the test-shaper pass. Both reshape the
`dispatch-tool-result-*` override-selection cluster without changing coverage.

- S1 → added a private `result-override` helper (placed just before
  `dispatch-tool-result-normalizes-content-test`): registers one `tool_result`
  handler returning a fixed value and invokes `dispatch-tool-result-in` with the
  inert original result, returning the dispatch result. Rewrote the five
  override-selection tests (`-non-map-return`, `-modifiable-key-override`,
  `-map-without-modifiable-key`, `-details-only-override`,
  `-is-error-only-override`) so each is a single
  `(is (= <expected> (result-override <handler-return>)))` (or `nil?`) line —
  the handler return and expected override stay visible per test; per-disjunct
  deftests retained for per-branch failure signal (per the test-shaper note).
  The two payload-capturing coercion tests
  (`-normalizes-content`, `-coerces-is-error`) were intentionally left out of the
  helper: they vary the *input* result and `is-error?` arg and assert on the
  captured *incoming* payload — a different axis the override helper does not
  model.
- S2 → removed the never-asserted original payload
  `{:content "original" :is-error false}` from the override-selection tests;
  folded into the helper as a single inert marker constant
  `inert-original-result` (`::original`). The original is incidental to
  override-selection (the handler return fully replaces it), so a non-map marker
  is safe — `dispatch-tool-result-in` only reads `(:content result)`/`(:details
  result)` to build the event the handler ignores.
- Also corrected the now-stale ns docstring ("…dispatch, tool wrapping, and
  introspection" → "…dispatch, and introspection"): tool wrapping was removed in
  slice 1.
- Verify: clj-paren-repair (no changes), clj-kondo clean (0/0); Kaocha focus
  `psi.agent-session.extensions-test` → 30 tests, 98 assertions, 0 failures
  (unchanged from before — pure shape change, no coverage delta). No deviations.

PASS_STATUS: REVIEW_COMPLETE (test-shaper S1/S2 resolved)

## Test review: test-shaper (second pass, 2026-06-01)

Re-applied test-shaper to the `dispatch-tool-result-*` cluster
(extensions_test.clj:383–461) after the prior S1/S2 reshape landed. Verified at
runtime: 30 tests, 98 assertions, 0 failures (Kaocha focus
`psi.agent-session.extensions-test`).

- **override-selection cluster** ✓ S1/S2 resolved: the five override tests are
  one-line `(is (= <expected> (result-override <return>)))` / `nil?`,
  per-disjunct deftests retained for branch failure signal, original payload
  folded into the inert `inert-original-result` marker. Clear, economical,
  behaviour-focused. No regression.

- **coercion cluster** ✗ One residual shape issue (actionable, S3 below).

S3 (actionable — economy / consistency: payload-capture ceremony):
`dispatch-tool-result-normalizes-content-test` (400) and
`dispatch-tool-result-coerces-is-error-test` (414) each rebuild the same
payload-capturing scaffold — `create-registry` + `register-extension-in!` +
`register-handler-in!` with the `(fn [p] (reset! payload p) nil)` capture
handler — and inline a full 6-arg `dispatch-tool-result-in` call per assertion
(three calls total across the two tests). This is the *same kind* of incidental
ceremony S1 compressed for the override cluster, on a different axis: these tests
vary the *input* result/`is-error?` and assert on the *captured incoming
payload*. S1 deliberately scoped itself to override-selection and left these out;
the payload-capture ceremony was therefore never compressed (`λ economical`:
`minimal(incidental_variation)`; `λ simple`: `minimal_incidental_setup`). It also
leaves the cluster inconsistent (`λ consistent`: `consistent(fixtures)` ∧
`consistent(test_abstractions)`): override tests go through `result-override`,
coercion tests hand-roll the registry+capture+dispatch. Extract a sibling helper
(e.g. `(capture-payload <result> <is-error?>)` that registers the capture
handler, dispatches, and returns the captured incoming payload) so each coercion
test states only its varying input axis and asserted field. Helper must compress
ceremony without hiding intent — the input result/`is-error?` and the asserted
payload field stay visible per test.

Note (¬actionable): `dispatch-tool-result-coerces-is-error-test` holds two
`testing` blocks (nil→false, truthy-non-bool→true) — two boundary cases of one
coercion behaviour, correctly grouped; keep them as distinct assertions for
boundary failure signal. With a `capture-payload` helper they collapse to two
visible `(is (… (:is-error (capture-payload {…} <raw>))))` lines.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-shaper second-pass follow-up execution: S3 (2026-06-01)

Executed S3 (the one newly-added unchecked item from the test-shaper second
pass). Extracted a sibling `capture-payload` helper next to `result-override`
that registers the `(fn [p] (reset! payload p) nil)` capture handler, invokes
`dispatch-tool-result-in` with the given raw result/`is-error?`, and returns the
captured incoming payload. Rewrote both coercion tests to state only their
varying input axis and asserted field:

- `dispatch-tool-result-normalizes-content-test` → one
  `(is (= [{:type :text :text "raw string"}] (:content (capture-payload {…} false))))`.
- `dispatch-tool-result-coerces-is-error-test` → two visible
  `(is (false?/true? (:is-error (capture-payload {…} <raw>))))` lines, keeping
  the nil/non-boolean boundary cases as distinct assertions for failure signal.

This restores fixture/abstraction consistency across the cluster: override tests
use `result-override`; coercion tests now use the sibling `capture-payload`
helper instead of hand-rolling the registry/atom/dispatch ceremony. Pure shape
change — no behaviour or coverage change.

Verification: `clj-paren-repair` (balanced + formatted), `clj-kondo` clean
(0 errors, 0 warnings), Kaocha focus `psi.agent-session.extensions-test`
**30 tests, 98 assertions, 0 failures** (coverage unchanged, 30/98 as expected).
