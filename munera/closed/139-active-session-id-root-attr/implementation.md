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

7. ~~**plan.md and steps.md absent.**~~ steps.md now exists (created during the
   design-clarification pass). plan.md is still absent; it must be written before
   execution steps begin (first unchecked execution step in steps.md).

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

## Cross-file consistency follow-up execution — 2026-05-10

1. **implementation.md item 7 corrected.** Stale "plan.md and steps.md absent" note
   updated: steps.md exists; plan.md still absent (first execution step covers this).

2. **Test-contract redundancy resolved — option (a) chosen.** Inspected
   `root-queryable-attrs-contract-test` in `graph_surface_test.clj`: it queries all
   root-queryable attrs and asserts each resolves. Once the resolver is registered,
   `:psi.agent-session/active-session-id` is automatically covered. No separate
   deftest is needed in `graph_surface_test.clj`. The resolver unit tests (nil
   semantics, return value) belong in `resolvers_test.clj` and are already called
   out in the Test contract. design.md Test contract section updated accordingly.

## Review follow-up execution — 2026-05-10

Added `:psi.agent-session/active-session-id` to `canonical-graph-root-attrs` in
`graph_surface_test.clj`. The attr now appears in the pinned set alongside
`:psi.graph/*` introspection attrs; accidental resolver removal will fail
`root-queryable-attrs-contract-test` explicitly rather than only via the
dynamic resolution loop. 1678 tests, 11841 assertions, 0 failures; lint clean.

## Implementation review — 2026-05-10

Resolver, docstring, nil passthrough, and registration in `resolvers` def are all
correct and match the design. Tests cover the three required cases (non-nil, nil,
root-access). Lint clean; 1678 tests green at commit e2388eea.

**One gap:** `canonical-graph-root-attrs` in `graph_surface_test.clj` is a
hardcoded set that does not include `:psi.agent-session/active-session-id`.
The `root-queryable-attrs-contract-test` dynamically verifies every advertised
attr resolves, so accidental removal of the resolver would stop the attr from
being advertised and the resolution loop would catch it — but only indirectly.
Adding the attr to `canonical-graph-root-attrs` would make the regression
explicit and symmetric with how other stable root attrs are pinned.

## Tests review — 2026-05-10

Resolver implementation and `canonical-graph-root-attrs` pinning are correct.
Three issues found in `resolvers_test.clj`:

1. **Test 3 is a near-duplicate of test 1.** Both `"returns invoking session id…"`
   and `"queryable from root without extra entity seeding…"` call
   `(session/query-in ctx session-id [...])` with identical setup and assertion.
   Test 3 does not distinguish "root-queryable" from "entity-seeded" — it uses the
   same mechanism as test 1. The design intent was to show the attr resolves via
   root seeds alone (the `q` helper or an explicit root-only query path would be a
   better vehicle). As written, test 3 adds no new signal.

2. **Design test contract requires "does not reflect adapter focus or list ordering"
   but no test covers it.** The design's Test contract section explicitly lists this
   case. The resolver implementation has no adapter focus wiring, so this is a
   documentation/contract gap rather than a bug risk — but the required case is absent.

3. **`:psi.agent-session/active-session-id` absent from `combined-telemetry-query-test`
   and `mixed-attrs-query-test`.** These composite regression tests confirm attrs
   compose without interference. Adding `active-session-id` to at least one of them
   would confirm no Pathom3 conflict with the larger session resolver set.
