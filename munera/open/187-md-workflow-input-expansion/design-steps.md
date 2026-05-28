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

## Ambiguity review pass 2

- [x] **Enumerate valid `:from` values for `vars:` frontmatter validation**: the design says
  "supported `:from` values match the same source-spec grammar used in `.edn` :vars" but `.edn`
  :vars support step-output/step-yield refs (map `:from` values) which are explicitly out-of-scope
  for `.md` frontmatter. Decide and document the exact set of keyword `:from` values allowed in
  `vars:` frontmatter (e.g. `:workflow-input`, `:workflow-original` only, or also `:workflow-runtime`).
  Update the `vars:` frontmatter section and validation description in the design.

- [x] **Specify `{{varname}}` token scanning pattern**: `markdown-body->contribution` scans for
  `{{varname}}` tokens but the character set valid in `varname` is never stated. Specify the
  pattern (e.g. `\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}`) so the implementation is unambiguous for
  both auto-wiring and unknown-var error detection. Add to the implementation path step 2.

- [x] **Specify unknown-var error propagation mechanism**: `compile-markdown-workflow-file`
  currently has no error-return path. If `markdown-body->contribution` signals an unknown-var
  error, the design must specify how it propagates: (a) throw `ex-info` (caught by the existing
  `try/catch ExceptionInfo` in `compile-workflow-file`), or (b) return `{:error ...}` requiring
  `compile-markdown-workflow-file` to gain an error-return path. Update the implementation path
  step 2 and step 3 to specify the chosen mechanism and any required changes to
  `compile-markdown-workflow-file`.

## Inconsistency review pass 2

- [x] **Sync `design.md` wiring scope and desired outcome with `plan.md` final-summary exclusion**:
  `design.md` lists `final-summary` in the step counts for `review-task-plan.edn` (6 steps),
  `implement-task.edn` (2 steps), and `review-task-design.edn` (6 steps), and the desired outcome
  says "all task 186 extracted `.md` files are wired". But `plan.md` records the decision to
  **exclude** `final-summary` steps from wiring in those three workflows (they carry `:source`
  contributions that `compile-prompt-workflow-step` would silently drop). Update `design.md` to:
  - Reduce step counts to exclude `final-summary` (review-task-plan.edn: 5, implement-task.edn: 1,
    review-task-design.edn: 5).
  - Add an explicit exclusion note for `final-summary` in those three workflows, parallel to the
    `review-step.edn` exclusion note.
  - Update the desired outcome to clarify that the three `final-summary` `.md` files
    (`implement-task-final-summary.md`, `review-task-plan-final-summary.md`,
    `review-task-design-final-summary.md`) are not wired.
  - Update acceptance criterion 6 to say "all task 186 extracted `.md` files **that do not carry
    `:source` contributions** are referenced" (or equivalent precise wording).

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
