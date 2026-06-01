# Implementation notes

## 2026-06-01 — design ambiguity review (ψ)

Reviewed design.md against workflow grammar (`doc/workflow-grammar.md`,
`doc/workflow-grammar-concepts.md`) and the five target workflow .edn files.
Actionable ambiguities found:

1. Input-threading mechanism unspecified. Scope claims sub-workflows consume
   `{:from :workflow-input :path [:input]}`, but per concepts doc a delegated
   workflow's `:workflow-input` IS the fully-rendered `:prompt-string`. For
   `:path [:input]` to resolve, the prompt-string must use the `:map` form
   (`{:type :map :fields {:input ...}}`), as in `gh-issue-implement.edn`. Design
   never specifies this form; "Constraints" name only `:invoke|:session|:delegate`.
2. `create-task-plan` is the odd one out: its .edn has no `:from :workflow-input`
   ref; `{{input}}` resolves inside `create-task-plan-create-plan.md`. The blanket
   "all five consume `{:from :workflow-input :path [:input]}`" claim is inaccurate.
3. Final-stage surfacing mechanism unspecified — final synthesizing step vs.
   last delegate yield, and what `:yields` the terminal step declares.
4. "Narrowest relevant parser/compiler/definition surface" verification is vague:
   no concrete ns/test/REPL entry point named.
5. doc/workflows.md / CHANGELOG obligation unresolved: doc is example-led (not an
   exhaustive list) and project-local-workflow user-visibility is undecided.
