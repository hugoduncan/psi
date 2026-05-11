# Steps

## Design clarifications (pre-plan)

- [ ] Decide resolver namespace placement: extend `resolvers/session.clj` or new file?
      Record decision in design.md under Constraints or a new Decisions section.

- [ ] Confirm `::pco/input [:psi.agent-session/session-id]` (single-seed) is correct
      and that Pathom3 resolves it when `agent-session-ctx` is also present in the
      entity map. If both inputs are needed, update the design's Constraints section.

- [ ] Clarify nil-when-absent semantics: is nil only returned when session-id is
      present-but-nil in the query context, or must the resolver also cover the
      absent-input case? Update the API / Test contract section accordingly.

- [ ] Add a sentence to design.md explaining why `:psi.agent-session/active-session-id`
      is a distinct attr rather than re-using `:psi.agent-session/session-id` (which
      is already root-reachable via `agent-session-identity`).

- [ ] Add "register new resolver in `resolvers` def" to the In scope / Acceptance
      section so the wiring step is explicit.

- [ ] Identify the target test file for the root-queryable-attrs assertion
      (`graph_surface_test.clj`) and add it to the Test contract section.

## Execution (after design is unambiguous)

- [ ] Write plan.md
- [ ] Implement resolver
- [ ] Add/extend tests per test contract
- [ ] Verify `bb test` green, lint clean
