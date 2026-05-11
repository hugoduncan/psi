# Steps

## Design clarifications (pre-plan)

- [x] Decide resolver namespace placement: extend `resolvers/session.clj` or new file?
      → `resolvers/session.clj`; append to `resolvers` def. Recorded in design.md Decisions.

- [x] Confirm `::pco/input [:psi.agent-session/session-id]` (single-seed) is correct
      and that Pathom3 resolves it when `agent-session-ctx` is also present in the
      entity map. → Confirmed correct; extra seeds are ignored by Pathom3 for resolver
      matching. Recorded in design.md Decisions.

- [x] Clarify nil-when-absent semantics: is nil only returned when session-id is
      present-but-nil in the query context, or must the resolver also cover the
      absent-input case? → nil only when present-but-nil; absent-input → Pathom3
      :not-found (expected). Updated API, Test contract, and Acceptance in design.md.

- [x] Add a sentence to design.md explaining why `:psi.agent-session/active-session-id`
      is a distinct attr rather than re-using `:psi.agent-session/session-id`.
      → Added to design.md Decisions.

- [x] Add "register new resolver in `resolvers` def" to the In scope / Acceptance
      section so the wiring step is explicit. → Added to both sections in design.md.

- [x] Identify the target test file for the root-queryable-attrs assertion
      (`graph_surface_test.clj`) and add it to the Test contract section.
      → `graph_surface_test.clj` identified; Test contract updated in design.md.

## Execution (after design is unambiguous)

- [ ] Write plan.md
- [ ] Implement resolver
- [ ] Add/extend tests per test contract
- [ ] Verify `bb test` green, lint clean
