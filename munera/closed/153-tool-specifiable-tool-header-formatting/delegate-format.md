## delegate call canonical header line format

```text
delegate <action> [detail]
```

Where:
- `<action>` is the effective delegate action
- `[detail]` is optional, action-specific summary text
- full rendered header is truncated to a maximum of 80 characters

## Effective action resolution

Determine `<action>` as:

1. `args["action"]` when present
2. otherwise `"run"` when `args["workflow"]` is present
3. otherwise `"list"` when no explicit action and no workflow are present
4. otherwise `"…"`

Rationale:
- the tool currently behaves as action=`run` when a workflow is supplied without an explicit action
- the tool currently behaves as action=`list` when invoked without a workflow/action payload
- the header should reflect effective behavior, not only explicit arguments

## Action-specific detail

### `run`
Format:
```text
delegate run <workflow-or-name-summary>
```

Detail source preference:
1. `args["workflow"]`
2. `args["name"]`
3. `args["prompt"]`

Examples:
```text
delegate run lambda-build
delegate run planner
delegate run fix retry status
```

### `list`
Format:
```text
delegate list
```

No detail text is required.

Example:
```text
delegate list
```

### `continue`
Format:
```text
delegate continue <id-or-prompt-summary>
```

Detail source preference:
1. `args["id"]`
2. `args["prompt"]`

Examples:
```text
delegate continue run-123
delegate continue add failing-case coverage
```

### `remove`
Format:
```text
delegate remove <id>
```

Detail source preference:
1. `args["id"]`

Examples:
```text
delegate remove run-123
delegate remove workflow-run-abc
```

## Truncation rules

- final header max length: `80`
- if truncated, append `…`
- action-specific detail truncation is applied before final header truncation

Current detail truncation targets:
- run workflow/name: 60
- run prompt: 56
- continue id: 56
- continue prompt: 56
- remove id: 58

## Fallback behavior

If no action-specific detail is available for an action that normally has detail, render just:

```text
delegate <action>
```

If action is also unavailable:

```text
delegate …
```

## Transport/runtime behavior

This header is:
- computed server-side from canonical tool args
- emitted as transport-safe `:call-summary` metadata on RPC tool lifecycle events
- preferred by Emacs during row rendering
- usable before tool completion, including `tool/executing`

## Examples

```text
delegate list
delegate run lambda-build
delegate continue run-123
delegate remove run-123
```
