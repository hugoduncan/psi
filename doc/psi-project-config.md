# psi Project Config

Project-level configuration and runtime query conventions.

## `psi-tool`

`psi-tool` is the canonical live runtime self-inspection and self-modification surface.

Canonical requests are action-based:
- `action: "query"` — read from the live EQL graph
- `action: "eval"` — evaluate in-process Clojure in a named already-loaded namespace
- `action: "mutate"` — invoke one registered runtime mutation with one params map
- `action: "reload-code"` — reload already loaded namespaces by explicit namespace list or worktree scope

Legacy query-only calls of the form `{query: "..."}` remain accepted as a migration compatibility alias for `action: "query"`, but canonical docs and examples use the action-based form.

### Query

Use query mode for:
- session/runtime introspection
- extension capability discovery
- querying resolver-backed runtime state

Examples:

```clojure
{:action "query"
 :query  "[:psi.graph/resolver-syms]"}
```

```clojure
{:action "query"
 :query  "[:psi.agent-session/session-name :psi.agent-session/model-id]"
 :entity "{:psi.agent-session/session-id \"sid\"}"}
```

Extension install introspection example:

```clojure
{:action "query"
 :query  "[:psi.extensions/effective
           :psi.extensions/diagnostics
           :psi.extensions/last-apply]"
 :entity "{:psi.agent-session/session-id \"sid\"}"}
```

Canonical discovery flow:
1. Query `:psi.graph/resolver-syms`
2. Query discovered attrs directly
3. Use root discovery attrs when needed:
   - `:psi.graph/root-seeds`
   - `:psi.graph/root-queryable-attrs`

### Mutate

Use mutate mode for canonical runtime writes that already exist as registered
mutations.

Contract:
- exactly one mutation per tool call
- `mutation` must be a qualified mutation symbol string
- `params` must be a map/object when present
- `entity` is query-only and is rejected for `action: "mutate"`
- `psi-tool` reuses the canonical runtime mutation path rather than raw eval

Example:

```clojure
{:action   "mutate"
 :mutation "psi.extension/close-session"
 :params   {:session-id "8fe9b0a6-ad8a-4373-9478-557e537499f2"}}
```

Typical success shape:

```clojure
#:psi-tool{:action :mutate
           :mutation 'psi.extension/close-session
           :duration-ms 4
           :overall-status :ok
           :result #:psi.agent-session{:close-session-closed? true
                                       :close-session-id "8fe9b0a6-ad8a-4373-9478-557e537499f2"}}
```

Typical validation failure shape:

```clojure
#:psi-tool{:action :mutate
           :mutation 'psi.extension/not-a-real-mutation
           :duration-ms 0
           :overall-status :error
           :error {:phase :validate
                   :message "Unknown psi-tool mutation: psi.extension/not-a-real-mutation"}}
```

Canonical session-admin flow:
1. query `:psi.agent-session/active-session-id`
2. query `:psi.agent-session/context-session-summaries`
3. choose explicit non-active session ids in caller logic
4. call `action: "mutate"` with `psi.extension/close-session`

Example discovery + mutate sequence:

```clojure
{:action "query"
 :query  "[:psi.agent-session/active-session-id]"}
```

```clojure
{:action "query"
 :query  "[{:psi.agent-session/context-session-summaries
            [:psi.session-info/id
             :psi.session-info/display-name
             :psi.session-info/created
             :psi.session-info/updated
             :psi.session-info/parent-session-id
             :psi.session-info/worktree-path]}]"}
```

```clojure
{:action   "mutate"
 :mutation "psi.extension/close-session"
 :params   {:session-id "8fe9b0a6-ad8a-4373-9478-557e537499f2"}}
```

### Eval

Eval is namespace-scoped, not worktree-scoped.

Requirements:
- `ns` is required
- `ns` must already be loaded
- `psi-tool` does not auto-require or auto-create namespaces
- forms are read with `*read-eval*` disabled

Example:

```clojure
{:action "eval"
 :ns     "clojure.core"
 :form   "(+ 1 2)"}
```

### Reload code

For psi self-development, reload is worktree-authoritative.

Interpretation:
- `:psi.agent-session/worktree-path` is the invoking session worktree
- when editing psi itself, that worktree is the canonical reload target
- namespace reload resolves target source files from that worktree and loads them into the running process
- if reload reports that the previously loaded namespace source differs from the target worktree source, treat that as a diagnostic warning rather than a reload failure

Reliable self-reload loop:
1. discover the active target worktree
2. start with one small already loaded namespace reload
3. inspect any mismatch warnings for loaded-source-path vs target-source-path
4. expand to worktree reload only after the small reload succeeds

Examples:

```clojure
{:action "query"
 :query  "[:psi.agent-session/worktree-path]"}
```

```clojure
{:action     "reload-code"
 :namespaces ["psi.prompt-assets.system-prompt"]}
```

`reload-code` supports exactly one targeting mode:

1. namespace mode

```clojure
{:action     "reload-code"
 :namespaces ["psi.agent-session.tools"]}
```

2. worktree mode using session-derived worktree-path

```clojure
{:action "reload-code"}
```

3. worktree mode using explicit worktree-path

```clojure
{:action        "reload-code"
 :worktree-path "/abs/path/to/worktree"}
```

Rules:
- reload-code has two targeting axes:
  - namespace selection via `namespaces`
  - worktree constraint via effective target worktree-path
- if `namespaces` are supplied, the effective target worktree-path defaults to:
  1. explicit `worktree-path`
  2. invoking session `:worktree-path`
  3. otherwise error
- each requested already loaded namespace must have a canonical source path under the effective target worktree-path or the request errors
- when `namespaces` are omitted, reload-code operates in worktree mode
- worktree precedence is:
  1. explicit `worktree-path`
  2. invoking session `:worktree-path`
  3. otherwise error
- no process cwd fallback
- worktree mode reloads only already loaded namespaces whose canonical source path resolves under the effective target worktree-path
- worktree mode does not discover brand new namespaces from disk
- namespace mode reloads exactly the requested already loaded namespaces in request order
- reload reports code reload and graph/runtime refresh separately; success of one does not imply success of the other
- graph/runtime refresh currently includes mandatory safety fixups for:
  - cached Pathom query env invalidation
  - mutation snapshot refresh
  - live tool definition refresh
  - dispatch handler re-registration
  - built-in workflow runtime reinitialization when already active
- when adding a new long-lived defonce registry, cached env, or runtime state object that captures function values, update the reload fixup inventory and add an explicit refresh step if stale references would break psi after reload
