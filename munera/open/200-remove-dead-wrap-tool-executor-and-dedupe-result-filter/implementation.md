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
