# Graph Surface

Psi exposes a self-describing EQL graph at session root. This graph surface is
intended to help humans and future ψ discover what is queryable before guessing
attribute names or internal paths.

## Purpose

The graph surface provides:

- a compact summary of registered resolvers and mutations
- a capability-oriented graph view over the live query system
- a canonical list of attrs reachable from session-root seeds
- a stable discovery workflow for interactive querying

## What this graph is

This is a **capability graph**.

- Nodes represent operations and domain capabilities.
- Edges represent **operation -> capability membership**.
- Edge `:attribute` values annotate those edges with operation IO attrs.

## What this graph is not

This is **not** a full dependency graph.

- Edges do not represent resolver dependency flow.
- Edges do not represent attr-to-resolver links as standalone nodes.
- Edges do not directly encode root reachability.

## Canonical discovery workflow

When exploring a running psi instance, prefer this sequence:

1. Query the graph surface for discovery metadata.
2. Read `:psi.graph/root-queryable-attrs`.
3. Read `:psi.graph/capabilities` and `:psi.graph/domain-coverage`.
4. Query the discovered attrs directly.

Examples:

```clojure
[:psi.graph/root-seeds]
[:psi.graph/root-queryable-attrs]
[:psi.graph/capabilities]
[:psi.graph/domain-coverage]
```

Then query discovered attrs directly, for example:

```clojure
[:psi.agent-session/phase :psi.agent-session/model]
[:git.repo/status :git.worktree/count]
[:psi.memory/status]
```

Session-admin discovery example:

```clojure
[:psi.agent-session/active-session-id
 {:psi.agent-session/context-session-summaries
  [:psi.session-info/id
   :psi.session-info/display-name
   :psi.session-info/created
   :psi.session-info/updated
   :psi.session-info/parent-session-id
   :psi.session-info/worktree-path]}]
```

Canonical session-admin flow:
1. query `:psi.agent-session/active-session-id`
2. query `:psi.agent-session/context-session-summaries`
3. choose explicit non-active session ids in caller logic
4. invoke `psi-tool(action: "mutate")` with `psi.extension/close-session`

This keeps target selection explicit in caller logic while routing the actual write
through the same registered mutation path the runtime already owns.

Prompt lifecycle introspection example:

```clojure
[:psi.agent-session/last-prepared-turn-id
 :psi.agent-session/last-prepared-message-count
 :psi.agent-session/last-execution-turn-id
 :psi.agent-session/last-execution-turn-outcome
 :psi.agent-session/last-execution-stop-reason]
```

## Root discovery

Notable session-admin root attrs include:
- `:psi.agent-session/active-session-id` — the invoking session id from the live query context
- `:psi.agent-session/context-session-summaries` — compact live context inventory for safe operational selection

### `:psi.graph/root-seeds`

The root context attrs injected for session-root querying.

Current seeds are:

- `:psi/agent-session-ctx`
- `:psi/memory-ctx`
- `:psi/recursion-ctx`
- `:psi/engine-ctx`

These seeds are diagnostic/discovery information. They are not included in
` :psi.graph/root-queryable-attrs`.

### `:psi.graph/root-queryable-attrs`

A sorted vector of keyword attrs reachable from the root seeds via resolvers.

Contract:

- derived by fixed-point reachability
- resolver outputs only
- mutations do not contribute
- seed attrs themselves are excluded
- only keyword attrs are returned
- sorted for deterministic discovery

## Graph summary attrs

### `:psi.graph/resolver-count`

Count of registered resolver operations in the introspected graph.

### `:psi.graph/mutation-count`

Count of registered mutation operations in the introspected graph.

### `:psi.graph/resolver-syms`

Set of qualified resolver symbols.

### `:psi.graph/mutation-syms`

Set of qualified mutation symbols.

### `:psi.graph/env-built`

Boolean indicating that a non-empty introspection graph/env is available.

This should be read as a practical readiness signal for graph discovery, not as
proof of a stronger internal compiler lifecycle state.

## Graph structure attrs

### `:psi.graph/nodes`

Vector of graph nodes.

Node types are restricted to:

- `:resolver`
- `:mutation`
- `:capability`

Typical node fields include:

- `:id`
- `:type`
- `:domain`
- `:symbol` for operation nodes
- `:operation-count` for capability nodes

### `:psi.graph/edges`

Vector of graph edges.

Each edge includes:

- `:from`
- `:to`
- `:attribute`

Edge semantics:

- `:from` is an operation node id
- `:to` is a capability node id
- `:attribute` annotates the edge with one IO attr from that operation

` :attribute` may be:

- a keyword attr
- a join map attr
- `nil` when an operation has no IO attrs

## Domain summary attrs

### `:psi.graph/capabilities`

Rich per-domain summaries for domains present in the graph.

Typical fields include:

- `:id`
- `:domain`
- `:operation-count`
- `:resolver-count`
- `:mutation-count`
- `:operation-symbols`
- `:attributes`

This surface is useful when you want a richer picture of what a domain exposes.

### `:psi.graph/domain-coverage`

Normalized per-domain operation counts.

Fields:

- `:domain`
- `:operation-count`
- `:resolver-count`
- `:mutation-count`

This surface differs from `:psi.graph/capabilities`:

- `capabilities` reports only domains present in the graph
- `domain-coverage` includes required zero-count domains

Current required domains are:

- `:ai`
- `:history`
- `:agent-session`
- `:introspection`

## Example queries

### Full graph summary

```clojure
[:psi.graph/resolver-count
 :psi.graph/mutation-count
 :psi.graph/resolver-syms
 :psi.graph/mutation-syms
 :psi.graph/env-built
 :psi.graph/nodes
 :psi.graph/edges
 :psi.graph/capabilities
 :psi.graph/domain-coverage]
```

### Root discovery summary

```clojure
[:psi.graph/root-seeds
 :psi.graph/root-queryable-attrs]
```

### Capability-oriented exploration

```clojure
[:psi.graph/capabilities
 :psi.graph/domain-coverage]
```

### Worktree discovery from session root

```clojure
[:git.worktree/list
 :git.worktree/current
 :git.worktree/count
 :git.worktree/inside-repo?]
```

## Related files

Implementation:

- `components/agent-session/src/psi/agent_session/resolvers.clj`
- `components/introspection/src/psi/introspection/graph.clj`

Tests:

- `components/agent-session/test/psi/agent_session/graph_surface_test.clj`
- `components/introspection/test/psi/introspection/graph_test.clj`
