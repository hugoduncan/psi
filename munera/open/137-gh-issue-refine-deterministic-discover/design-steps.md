# Design follow-up steps

Unchecked items added by design review pass 1 (2026-05-10).

- [ ] **A. Resolve `:tool` vs `:invoke` architectural overlap.** Decide explicitly: should `gh-find-issue` be a deterministic operation registered in `deterministic-operation-registry` (allowing use of the existing `:invoke` step type with zero IR/adapter changes) or a psi tool in the tool catalog (requiring a new `:tool` step type)? If `:invoke`, update design to replace Phase 2 entirely with a deterministic-operation registration in the github component and remove the `:tool` step type from scope. If `:tool`, justify why it must live in the tool catalog rather than the operation registry.

- [ ] **B. Specify the IR schema for `:tool` steps (or confirm compile-to-`:invoke`).** If `:tool` is a new IR step type, add its malli schema to `ir.clj`, specify its source-ref traversal in `step-source-refs`, and add its semantic validation rules. If `:tool` compiles to `:invoke` in the target-IR compiler (preferred), document this in the design and remove the IR schema change from Phase 2.

- [ ] **C. Fix tool registration contract.** Replace `{:fn ... :schema ...}` in the extension manifest example with the actual tool-def keys `{:name :description :parameters :execute}` as defined in `psi.tool-registry.defs/normalize-tool-def`. Clarify how the shell-seam ctx is threaded into the `:execute` fn.

- [ ] **D. Replace `clojure.data.json` with `cheshire`.** Update design to declare `cheshire/cheshire` in the github component `deps.edn`. Remove the "already available" claim for `clojure.data.json`.

- [ ] **E. Specify `:yields` shape for `:tool` steps.** Define what the IR `:yields` spec looks like for a `:tool` step so that `{:from {:step "discover" :yield :text}}` resolves correctly in downstream steps. Specify where the serialized Markdown string is stored in the result envelope (e.g. as `:final-llm-reply` in `:outputs`) and what `step-yield-field-value` returns for `:text` on a `:tool` step.

- [ ] **F. Create `plan.md`.** Write `munera/open/137-gh-issue-refine-deterministic-discover/plan.md` with the execution approach and phase ordering before implementation begins (Munera protocol requirement).
