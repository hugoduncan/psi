# 139 — active session id root attr

## Problem

No authoritative root attr exists for the active/current session id. Callers infer "current" from list ordering — an implicit, unreliable contract. The runtime already knows which session is the active conversation target for a given invoking context, but this is not surfaced through the graph.

Without an explicit identity signal, any logic that must distinguish "current" from "other" sessions degrades to ordering heuristics. This is both fragile and misleading.

## Intent

Expose the runtime's authoritative active-session identity through a single root attr, removing the need for list-order inference.

This task should:

- add a root resolver for `:psi.agent-session/active-session-id`
- anchor the attr to the invoking tool context, not a process-global UI focus concept
- identify and document the concrete runtime source of truth so adapter semantics do not leak into the contract
- return `nil` when no active conversation target exists rather than guessing

This task should not:

- redesign session lifecycle or ordering semantics
- add mutation capabilities
- broaden into a session-summary or inventory surface

## Constraints

- the attr must be relative to the invoking tool context — ¬process-global UI focus, ¬list-order inference
- `nil` when no active target exists — ¬guess, ¬fall back to oldest/newest/first
- implementation must identify and document the concrete runtime source of truth before writing the resolver

## Invariants

- ¬oldest session, ¬newest session, ¬arbitrary list ordering
- the attr must not change meaning based on adapter focus state

## In scope

- root resolver for `:psi.agent-session/active-session-id`
- documentation of the concrete runtime source of truth
- focused tests

## Out of scope

- compact session summary surface — see task 134
- mutation surface — see task 134
- session lifecycle or ordering redesign

## API

### Query example

```edn
[:psi.agent-session/active-session-id]
```

### Result example

```clojure
{:psi.agent-session/active-session-id "731274a7-55d0-4854-aa23-35df82c6abdd"}
```

Returns `nil` when no active target exists:

```clojure
{:psi.agent-session/active-session-id nil}
```

## Test contract

Cover at least:

- `:psi.agent-session/active-session-id` returns the authoritative active session id for the invoking live context
- `:psi.agent-session/active-session-id` returns `nil` rather than guessing when no active conversation target exists
- the attr is queryable from root without entity seeding
- the resolved value does not reflect arbitrary session-list ordering

## Acceptance

- `:psi.agent-session/active-session-id` is queryable as a root attr
- returns the authoritative active session id relative to the invoking tool context
- returns `nil` when no active conversation target exists
- the concrete runtime source of truth is identified and documented in the implementation
- ¬list-order inference, ¬process-global UI focus, ¬guessing

## Related work

- `012-psi-tool-session-targeting-introspection` established the need for trustworthy explicit session targeting
- `134-psi-tool-mutation-surface-and-session-summary-introspection` — compact session summaries and mutate action; composes with this attr for session-admin workflows
