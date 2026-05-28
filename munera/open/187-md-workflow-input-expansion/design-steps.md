# 187 design follow-up steps

## Ambiguity review pass 1

- [ ] **Specify `vars:` YAML parsing approach**: decide and document how nested map syntax
  in `vars:` frontmatter will be parsed. Options: (a) extend `parse-yaml-frontmatter` to
  support nested maps, (b) require the `vars:` value to use EDN literal syntax
  (e.g., `vars: {"my-var" {:from :workflow-input :path [:input]}}`), (c) another
  machine-readable format. Update the design's `vars:` section with the chosen format
  and any parser changes required.

- [ ] **Fix implementation step 4**: update the design to clarify that
  `compile-prompt-workflow-step` must pass `(:vars referenced)` to
  `markdown-body->contribution` after step 1 adds `:vars` to the parsed result.
  Remove the incorrect "no additional change needed there" claim.

- [ ] **Clarify `{{original}}` source spec**: decide whether `{{original}}` should map
  to `{:from :workflow-input :path [:original]}` (as currently written) or
  `{:from :workflow-original}` (consistent with existing `.edn` usage). Document the
  rationale in the design's `{{input}}` / `{{original}}` conventions section.

- [ ] **Address `compiler_target_authoring_test.clj` breakage**: add a note to the design
  (or acceptance criteria) that the existing test asserting `:framing-prompt` in
  `workflow-file-meta` must be updated to assert its absence after the change.

- [ ] **Enumerate task 186 wiring gap `.edn` targets**: update the design's scope section
  to explicitly list the four `.edn` workflows to be wired:
  `review-task-plan.edn`, `implement-task.edn`, `review-task-design.edn`,
  `create-task-plan.edn`. Note that `review-step.edn` is intentionally excluded.
