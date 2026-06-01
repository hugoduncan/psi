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
  `dispatch-tool-result-in` filter behaviour is independently covered
  (extensions_test.clj:453–471), so its tests need not be migrated.
