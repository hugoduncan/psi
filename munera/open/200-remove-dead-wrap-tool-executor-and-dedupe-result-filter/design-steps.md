# Design follow-up steps — 200

## Ambiguity review follow-ups

- [x] A1: Resolve the scope-decision criterion. State explicitly how the planner
  decides direction 1 (remove) vs direction 2 (keep) given (a) confirmed zero
  production callers and (b) the `wrap-tool-executor` "compatibility wrapper"
  test (extensions_test.clj:393) being only a pass-through assertion, not
  evidence of an intentional public surface. Make the decision deterministic.
- [x] A2: Clarify whether the single-source "expressed once" contract must also
  subsume `tool-result-event` (extensions.clj:311–313), which is a third
  co-located enumeration of `:content`/`:details`/`:is-error` (also consumed by
  `tool_runtime_adapter.clj`), or whether it is intentionally excluded.
- [x] A3: Specify, for direction 1 (remove), what behaviour must remain covered
  after `wrap-tool-executor` tests are removed/migrated — e.g. note that
  `dispatch-tool-result-in` coercion/normalization behaviour is independently
  covered (extensions_test.clj:445–473), so those tests need not be migrated.

## Inconsistency review follow-ups

- [x] I1: Fix A2's description of `tool_runtime_adapter.clj`. The design states
  the adapter "reads those keys off the constructed event … consumes the
  payload," but the code reads `:content`/`:details`/`:is-error` from the
  *incoming* `lifecycle-event` and passes them as *arguments into*
  `tool-result-event` (it sources the constructor inputs, it does not consume the
  constructed payload). Correct the prose so the A2 exclusion rests on an accurate
  account of the code; re-confirm the exclusion conclusion still holds.
- [x] I2: Correct the `tool-result-event` citation. Design cites it as
  `extensions.clj:311–313` (Context, A2, Out of Scope), but the `defn` spans
  299–313; 311–313 are only its trailing map keys. Cite the function's actual
  location (or use line-stable references).
- [x] I3: Correct the A3 test-coverage citation. Design cites the two coercion
  tests as `extensions_test.clj:453–490`; they actually span 445–473
  (`dispatch-tool-result-normalizes-content-test` @445,
  `dispatch-tool-result-coerces-is-error-test` @459), and 490 falls inside the
  unrelated `tool-event-payload-constructors-test` (@475). Also reconcile the
  divergent `453–471` citation in design-steps.md A3. Use accurate ranges.
