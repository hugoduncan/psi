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
