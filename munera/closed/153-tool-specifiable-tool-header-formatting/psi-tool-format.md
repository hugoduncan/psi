## psi-tool call canonical header line format

```text
psi-tool <action> [detail]
```

Where:
- `<action>` is the effective psi-tool action
- `[detail]` is optional, action-specific summary text
- full rendered header is truncated to a maximum of 80 characters

## Effective action resolution

Determine `<action>` as:

1. `args["action"]` when present
2. otherwise `"query"` when legacy `args["query"]` is present
3. otherwise `"…"`

## Action-specific detail

### `query`
Format:
```text
psi-tool query <query-or-entity-summary>
```

Detail source preference:
1. `args["query"]`
2. `args["entity"]`

Examples:
```text
psi-tool query [:psi.agent-session/phase :psi.agent-session/session-id]
psi-tool query {:psi.agent-session/session-id "abc"}
```

### `eval`
Format:
```text
psi-tool eval <ns-or-form-summary>
```

Detail source preference:
1. `args["ns"]`
2. `args["form"]`

Examples:
```text
psi-tool eval clojure.core
psi-tool eval (+ 1 2)
```

### `mutate`
Format:
```text
psi-tool mutate <mutation-or-params-summary>
```

Detail source preference:
1. `args["mutation"]`
2. `(pr-str args["params"])`

Examples:
```text
psi-tool mutate psi.extension/close-session
psi-tool mutate {:session-id "s1"}
```

### `reload-code`
Format:
```text
psi-tool reload-code <namespaces-or-worktree-summary>
```

Detail source preference:
1. comma-joined `args["namespaces"]`
2. `args["worktree-path"]`

Examples:
```text
psi-tool reload-code psi.agent-session.tools,psi.agent-session.psi-tool
psi-tool reload-code /Users/duncan/projects/hugoduncan/psi/psi-main
```

### `project-repl`
Format:
```text
psi-tool project-repl <op-or-code-or-worktree-summary>
```

Detail source preference:
1. `op=<args["op"]>`
2. `args["code"]`
3. `args["worktree-path"]`

Examples:
```text
psi-tool project-repl op=status
psi-tool project-repl op=eval
```

### `workflow`
Format:
```text
psi-tool workflow <op-or-definition-or-run-summary>
```

Detail source preference:
1. `op=<args["op"]>`
2. `args["definition-id"]`
3. `args["run-id"]`

Examples:
```text
psi-tool workflow op=list-definitions
psi-tool workflow op=execute-run
```

### `scheduler`
Format:
```text
psi-tool scheduler <op-or-label-or-id-or-message-summary>
```

Detail source preference:
1. `op=<args["op"]>`
2. `args["label"]`
3. `args["schedule-id"]`
4. `args["message"]`

Examples:
```text
psi-tool scheduler op=create
psi-tool scheduler nightly-recap
```

## Truncation rules

- final header max length: `80`
- if truncated, append `…`
- action-specific detail truncation is applied before final header truncation

Current detail truncation targets:
- query/entity: 56
- mutation: 56
- reload namespaces/worktree: 56
- project-repl `op=`: 18
- project-repl code/worktree: 56
- workflow `op=`: 24
- workflow ids: 52
- scheduler `op=`: 24
- scheduler label/id/message: 52
- eval `ns`: 24
- eval `form`: 52

## Fallback behavior

If no action-specific detail is available, render just:

```text
psi-tool <action>
```

If action is also unavailable:

```text
psi-tool …
```

## Transport/runtime behavior

This header is:
- computed server-side from canonical tool args
- emitted as transport-safe `:call-summary` metadata on RPC tool lifecycle events
- preferred by Emacs during row rendering
- usable before tool completion, including `tool/executing`

## Examples

```text
psi-tool query [:x]
psi-tool eval clojure.core
psi-tool mutate psi.extension/close-session
psi-tool reload-code psi.agent-session.tools
psi-tool project-repl op=status
psi-tool workflow op=create-run
psi-tool scheduler op=create
```
