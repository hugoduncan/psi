## work-on call canonical header line format

```text
work-on <description-summary>
```

Where:
- `<description-summary>` is the requested work description
- full rendered header is truncated to a maximum of 80 characters

## Detail resolution

Determine `<description-summary>` as:

1. `args["description"]`
2. otherwise `"…"`

Optional base-branch information:
- when `args["base_branch"]` is present, append ` from <base-branch-summary>`
- resulting form:

```text
work-on <description-summary> from <base-branch-summary>
```

Rationale:
- the primary subject of `work-on` is the requested work description
- optional `base_branch` materially affects behavior and is worth surfacing when present
- the tool should not mirror slash-command syntax like `--base`; the header should summarize the canonical tool request shape

## Truncation rules

- final header max length: `80`
- if truncated, append `…`
- base branch and description are each truncated before final assembly when needed

Current detail truncation targets:
- description without base branch: 72
- description with base branch: 44
- base branch when present: 20

## Fallback behavior

If no description is available, render:

```text
work-on …
```

If `base_branch` is present but description is unavailable, still prefer:

```text
work-on …
```

rather than rendering a base-only summary, because description is the primary operation subject.

## Transport/runtime behavior

This header is:
- computed server-side from canonical tool args
- emitted as transport-safe `:call-summary` metadata on RPC tool lifecycle events
- preferred by Emacs during row rendering
- usable before tool completion, including `tool/executing`

## Examples

```text
work-on fix flaky workflow test
work-on github issue 27 triage from origin/master
work-on improve retry display from release/1.2
```
