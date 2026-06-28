# Data Flow

Data flow is expressed through source references and optional projection.

The core data-flow surface is:

- `:from`
- `:path`
- `:projection`
- `{:step ... :output ...}`
- `{:step ... :prompt ... :output ...}`
- `{:step ... :yield ...}`

This data-flow surface is shared across:

- invoke args
- source contributions
- template vars
- delegated context
- model-selection query values where applicable

The grammar therefore treats data flow as a common substrate rather than duplicating separate per-feature reference languages.

## Projection Rule

A source-spec may contain either:

- `:path`, or
- `:projection`

The first cut does not allow both on the same source-spec. `:path` is the simple selector form; `:projection` is the richer selector form.

## Workflow Input and Original Request

For a top-level workflow invocation:

- `:workflow-input` is the workflow's current input value
- `:workflow-original` is the invocation's original request surface

For a delegated workflow invocation:

- `:workflow-input` is the delegated step's fully rendered `:prompt-string`
- `:workflow-original` is rebound per invocation and is local to the delegated workflow run rather than implicitly inherited from the root caller

The rendered `:prompt-string` is a string for literal and `:template`-shaped
forms, and a **map** for the `:map` form (see the Delegation boundary section).
For a `:map` `:prompt-string`, `:workflow-input` is therefore that map, so the
delegated workflow's `{:from :workflow-input :path [<key>]}` selectors resolve
against it.
