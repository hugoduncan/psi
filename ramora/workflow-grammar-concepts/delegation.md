# Delegation Boundary

Delegation is a distinct execution form rather than a variant of inline session construction.

The delegation boundary has two channels:

- `:prompt-string`
- `:context`

`:prompt-string` is the new request sent to the delegated workflow. It may be authored as a literal string or as a template-shaped renderer, both of which render to a final string before delegation.

A `:prompt-string` may also be authored as a `:map` form — `{:type :map :fields {<key> <source-spec> ...}}` — which renders to a **map**, not a string, with each field resolved from its source-spec. The delegated workflow then receives that map as its `:workflow-input`, so a sub-workflow reference like `{:from :workflow-input :path [<key>]}` resolves the corresponding field. This is the mechanism for threading a structured identifier (e.g. a task id under `:input`) into a delegated workflow; see the shipping exemplars `gh-issue-implement.edn` and `review-task-implementation.edn`.

`:context` is caller-derived material forwarded across the delegation boundary. It is optional; when omitted, it is equivalent to an empty vector.

The grammar keeps these separate because they play different roles:

- prompt string = explicit new ask
- context = carried-forward source material

The delegated workflow treats the final rendered prompt string — or, for the `:map` form, the rendered map — as its local workflow input surface.
