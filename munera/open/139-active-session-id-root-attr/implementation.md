# Implementation notes

## Design clarification pass — 2026-05-10

### Ambiguities found

1. **Resolver namespace placement unspecified.** Design says "add a root resolver" but
   doesn't say whether it goes in `resolvers/session.clj` (natural home alongside
   existing session resolvers) or a new file. Must be decided before plan.md is written.

2. **`::pco/input [:psi.agent-session/session-id]` — single-seed intent needs
   confirmation.** All existing session resolvers use both `:psi/agent-session-ctx`
   and `:psi.agent-session/session-id` as inputs. A resolver with only
   `[:psi.agent-session/session-id]` is unusual. Design asserts this is correct for
   root-queryable-attrs inclusion but does not confirm Pathom3 will satisfy the
   resolver correctly when `agent-session-ctx` is also present in the entity map.

3. **`nil`-when-absent vs. Pathom3 `:not-found`.** The design specifies
   `{:psi.agent-session/active-session-id nil}` when no session-id is in the query
   context. But if the resolver's input `[:psi.agent-session/session-id]` is absent
   from the entity, Pathom3 will not run the resolver at all and will return
   `:com.wsscode.pathom3.core/not-found`. The design needs to clarify: is the nil
   case only reachable when session-id is present but nil, or must the resolver also
   handle the absent-input case? The test contract example implies the former, but
   the wording is ambiguous.

4. **`agent-session-identity` already outputs `:psi.agent-session/session-id` from
   root.** That resolver takes `[:psi/agent-session-ctx :psi.agent-session/session-id]`
   and re-emits `:psi.agent-session/session-id`. The design should explain why
   `:psi.agent-session/active-session-id` is a distinct attr rather than the agent
   querying `:psi.agent-session/session-id` directly (which is already root-reachable
   via the identity resolver). This is the core semantic justification that is
   currently implicit.

5. **Registration in `resolvers` var not called out.** The new resolver must be
   appended to the `resolvers` def in `resolvers/session.clj` (and thereby included
   in `all-resolvers` in `resolvers.clj`). The design and acceptance criteria are
   silent on this wiring step.

6. **`graph_surface_test.clj` assertion gap.** `canonical-graph-root-attrs` is a
   hardcoded set that does not include `:psi.agent-session/active-session-id`. The
   existing `context-session-graph-introspection-test` pattern shows how to assert a
   specific attr appears in root-queryable-attrs. A new or extended test is needed
   there; the design's test contract mentions this but does not identify the file or
   whether it extends existing tests or adds a new deftest.

7. **plan.md and steps.md absent.** The task directory contains only design.md.
   Execution cannot begin until plan.md and steps.md exist per Munera protocol.

## Design-step execution — 2026-05-10

All six pre-plan design clarification steps resolved and recorded in design.md.

- Resolver namespace: `resolvers/session.clj`, append to `resolvers` def.
- Single-seed input `[:psi.agent-session/session-id]` confirmed correct; Pathom3
  ignores extra seeds for resolver matching; psi-tool always seeds `session-id`.
- nil semantics: nil only when session-id present-but-nil; absent-input → Pathom3
  `:not-found` (not a resolver concern).
- Semantic distinction documented: `active-session-id` answers "who am I?" from
  root; `session-id` is an entity key for seeded queries.
- Registration wiring (`resolvers` def) made explicit in In scope and Acceptance.
- Test file identified: `graph_surface_test.clj` for root-queryable-attrs assertion;
  resolver unit tests in nearest session resolver test file.

Design is now unambiguous. plan.md can be written and execution can begin.

## Cross-file consistency review — 2026-05-10

### Inconsistencies found

1. **implementation.md item 7 is stale.** It states "plan.md and steps.md absent" but
   steps.md now exists (created during the design-clarification pass). The note is
   factually incorrect and may confuse future readers.

2. **Test contract specifies a new `active-session-id-root-attr-test` deftest in
   `graph_surface_test.clj` for the root-queryable-attrs assertion — but
   `root-queryable-attrs-contract-test` already covers every advertised
   root-queryable attr.** Once the resolver is registered, the existing test
   exercises it automatically. A separate deftest is redundant for this assertion.
   The test contract should either (a) drop the new-deftest requirement and rely on
   the existing contract test, or (b) clarify what additional assertion the new
   deftest provides beyond what `root-queryable-attrs-contract-test` already covers.

3. **No `plan.md` exists.** Munera protocol requires plan.md before execution steps
   begin. The first unchecked step in steps.md is "Write plan.md" — consistent —
   but plan.md must be written before any implementation step proceeds.
