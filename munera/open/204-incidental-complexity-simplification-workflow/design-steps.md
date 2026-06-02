# Design review follow-up steps

## Architecture fit

- [ ] Specify the step-1 → step-2 handoff using the verified workflow-grammar
      delegate-yield mechanism. The design must state that step-2 sources its
      `task-lifecycle` `:input` from step-1's yielded text via
      `:prompt-string {:type :map :fields {:input {:from {:step "<select-step-name>" :yield :text}}}}`,
      consistent with the `gh-issue-implement.edn` precedent and the fact that
      `task-lifecycle` sub-workflows read `{:from :workflow-input :path [:input]}`
      (a map `{:input "munera/open/NNN-slug"}`). This closes a `one_way` /
      grammar-conformance gap the design itself raises to "Verified facts".
