# 139 — active session id root attr

## Problem

Agents using `psi-tool` cannot query "which session am I?" from root. No root
attr exposes the invoking session id. The only workaround is entity-seeded
queries — which require already knowing the session id, defeating the purpose.

This forces agents into either inferring identity from list ordering or
hard-coding session ids that they obtained out-of-band.

## Background

The runtime does have an `active-session-id` concept in `context_index.clj`,
but it is unused in production — nothing in non-test src requires it.
There was previously a `:context-active-session-id` graph attr that was
deliberately removed; the comment in `resolvers/session.clj` reads:

> `:context-active-session-id` removed — adapters (RPC, TUI) own focus locally.

Adapter-local focus (TUI `tui-focus*` atom, RPC session binding) is the right
owner of "which session the user is looking at." That is a different question.

The question this task answers is narrower and always has a definite answer:
**which session invoked this psi-tool call?** In `tool_plan.clj`, the query-fn
is already closed over that `session-id`:

```clojure
(fn ([eql-query]
     (query/query-in qctx
                     {:psi/agent-session-ctx        ctx
                      :psi.agent-session/session-id session-id}
                     eql-query)))
```

The invoking session id is always present in the query context. It is not
exposed as a root attr, so it cannot be queried without an entity seed.

## Intent

Add a root resolver for `:psi.agent-session/active-session-id` that returns
the `session-id` already present in the psi-tool query context — making the
invoking session's own identity queryable from root without entity seeding.

This task should:

- add a root resolver that returns the invoking session id from the query context
- document the concrete source of truth (the query-context-bound `session-id`)
- return `nil` when no session id is present in the context rather than throwing

This task should not:

- re-introduce adapter-local UI focus onto the graph
- wire up `context_index.clj` or any runtime-central focus tracking
- add mutation capabilities
- broaden into a session-summary or inventory surface

## Constraints

- the attr must resolve from the query context's bound `session-id` — ¬adapter focus, ¬list ordering, ¬context-index
- `nil` when no session id is present in the query context — ¬throw, ¬guess
- must not re-introduce the previously-removed `:context-active-session-id` semantics
- the resolver's input must be a subset of root seeds so the attr appears in `:psi.graph/root-queryable-attrs`
  - root seeds: `#{:psi/agent-session-ctx :psi.agent-session/session-id :psi/memory-ctx :psi/recursion-ctx :psi/engine-ctx}`
  - resolver input must be `[:psi.agent-session/session-id]` — already a root seed — so the fixed-point reachability pass in `derive-root-queryable-attrs` includes this attr

## Invariants

- the resolved value is the session that invoked the tool call — always unambiguous
- does not change meaning based on adapter state or UI focus

## In scope

- root resolver for `:psi.agent-session/active-session-id`
- documentation of the source of truth (query-context `session-id`)
- focused tests

## Out of scope

- adapter-local UI focus on the graph
- `context_index.clj` wiring
- compact session summary surface — see task 134
- mutation surface — see task 134

## API

### Query example

```edn
[:psi.agent-session/active-session-id]
```

### Result example

```clojure
{:psi.agent-session/active-session-id "731274a7-55d0-4854-aa23-35df82c6abdd"}
```

Returns `nil` when no session id is present in the query context:

```clojure
{:psi.agent-session/active-session-id nil}
```

## Test contract

Cover at least:

- returns the invoking session id when a session id is present in the query context
- returns `nil` when no session id is present
- queryable from root without entity seeding
- does not reflect adapter focus or list ordering
- `:psi.agent-session/active-session-id` appears in `:psi.graph/root-queryable-attrs`

## Acceptance

- `:psi.agent-session/active-session-id` is queryable from root without entity seeding
- resolves to the session id present in the psi-tool query context
- returns `nil` when no session id is present
- appears in `:psi.graph/root-queryable-attrs`
- source of truth is documented: query-context-bound `session-id` from `tool_plan.clj`
- ¬adapter focus, ¬list ordering, ¬context-index wiring

## Related work

- `012-psi-tool-session-targeting-introspection` established the need for trustworthy explicit session targeting
- `134-psi-tool-mutation-surface-and-session-summary-introspection` — compact session summaries and mutate action; composes with this attr for session-admin workflows
