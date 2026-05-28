# 187 design follow-up steps

## Ambiguity review pass 1

- [x] **Specify `vars:` YAML parsing approach**: decide and document how nested map syntax
  in `vars:` frontmatter will be parsed. Options: (a) extend `parse-yaml-frontmatter` to
  support nested maps, (b) require the `vars:` value to use EDN literal syntax
  (e.g., `vars: {"my-var" {:from :workflow-input :path [:input]}}`), (c) another
  machine-readable format. Update the design's `vars:` section with the chosen format
  and any parser changes required.

- [x] **Fix implementation step 4**: update the design to clarify that
  `compile-prompt-workflow-step` must pass `(:vars referenced)` to
  `markdown-body->contribution` after step 1 adds `:vars` to the parsed result.
  Remove the incorrect "no additional change needed there" claim.

- [x] **Clarify `{{original}}` source spec**: decide whether `{{original}}` should map
  to `{:from :workflow-input :path [:original]}` (as currently written) or
  `{:from :workflow-original}` (consistent with existing `.edn` usage). Document the
  rationale in the design's `{{input}}` / `{{original}}` conventions section.

- [x] **Address `compiler_target_authoring_test.clj` breakage**: add a note to the design
  (or acceptance criteria) that the existing test asserting `:framing-prompt` in
  `workflow-file-meta` must be updated to assert its absence after the change.

- [x] **Enumerate task 186 wiring gap `.edn` targets**: update the design's scope section
  to explicitly list the four `.edn` workflows to be wired:
  `review-task-plan.edn`, `implement-task.edn`, `review-task-design.edn`,
  `create-task-plan.edn`. Note that `review-step.edn` is intentionally excluded.

## Inconsistency review pass 1

- [x] **Resolve `review-task-plan.edn` step count vs. `.md` corpus mismatch**: design
  scope says 5 steps for `review-task-plan.edn` but the actual file has 6 (including
  `final-summary`). No `review-task-plan-final-summary.md` exists. Decide: (a) create
  `review-task-plan-final-summary.md` and update the step count to 6 in design scope
  and desired outcome, or (b) keep `final-summary` inline in `review-task-plan.edn`
  and explicitly note the exclusion (like `review-step.edn`). Update design scope and
  desired outcome to match.

- [x] **Add `allowed-md-frontmatter-keys` update to implementation step 1**: the parser
  rejects unknown frontmatter keys before any new parsing logic runs. Implementation
  step 1 must explicitly include adding `:vars` to `allowed-md-frontmatter-keys` in
  `parser.clj`. Update the implementation path to name this change.

- [x] **Specify updated `parse-markdown-workflow-file` return shape**: the design
  references `(:vars referenced)` in step 4 but never states that the parsed result
  gains a `:vars` key. Update the design (implementation path step 1 or the `vars:`
  frontmatter section) to explicitly state that `parse-markdown-workflow-file` returns
  `{... :vars <parsed-vars-map-or-nil>}` so the parser/compiler interface is unambiguous.
