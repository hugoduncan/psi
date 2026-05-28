# Steps — 187 `.md` workflow `{{input}}` expansion

## Slice 1 — Parser: `vars:` frontmatter support

- [ ] In `components/workflow-loader/src/psi/workflow_loader/parser.clj`:
  add `:vars` to `allowed-md-frontmatter-keys`
- [ ] In `parse-markdown-workflow-file`: after extracting frontmatter, read the
  raw `:vars` value (scalar string) with `clojure.edn/read-string`; validate
  result is a map; return error if parse fails or value is not a map
- [ ] Return parsed vars map (or `nil` when absent) under `:vars` key in the
  result so the return shape is:
  `{:workflow-kind :single-step-markdown :name :description :session-config :body :vars}`
- [ ] In `components/workflow-loader/test/psi/workflow_loader/parser_test.clj`:
  add test — `vars:` EDN string parses to map, returned under `:vars`
- [ ] Add test — missing `vars:` key returns `:vars nil`
- [ ] Add test — `vars:` with non-map EDN string returns error
- [ ] Add test — `vars:` with invalid EDN string returns error
- [ ] `bb test` green

## Slice 2 — Compiler: expansion, framing-prompt removal, vars threading

- [ ] In `components/workflow-loader/src/psi/workflow_loader/compiler.clj`:
  update `markdown-body->contribution` to accept an optional `declared-vars` map
  argument (default `nil`)
- [ ] In `markdown-body->contribution`: scan body for all `{{varname}}` tokens
  using a regex (e.g. `#"\{\{(\w+)\}\}"`)
- [ ] Auto-wire `"input"` → `{:from :workflow-input :path [:input]}`
  and `"original"` → `{:from :workflow-original}` for any matching tokens found
- [ ] Merge `declared-vars` into the auto-wired map (declared vars take
  precedence for non-standard names; standard vars always use their canonical
  source specs regardless of any override in `declared-vars`)
- [ ] Error (return `{:error "..."}`) for any `{{varname}}` remaining after
  merging standard and declared vars — i.e. any token not in the final vars map
- [ ] In `compile-markdown-workflow-file`: remove `:framing-prompt body` from
  the `workflow-file-meta` map (leave `{:file-kind :md}` or no `:framing-prompt`
  key at all)
- [ ] In `compile-prompt-workflow-step`: pass `(:vars referenced)` as the
  `declared-vars` argument when calling `markdown-body->contribution`
- [ ] In `components/workflow-loader/test/psi/workflow_loader/compiler_test.clj`:
  update the `compile-markdown-workflow-file-test` fixture or assertion — the
  compiled step's contribution `:vars` should now contain
  `{"input" {:from :workflow-input :path [:input]}}` when body contains `{{input}}`
- [ ] Update `compile-markdown-workflow-file-test` to assert no `:framing-prompt`
  key in `workflow-file-meta`
- [ ] In `compiler_target_authoring_test.clj` line ~19: update assertion from
  `(= "Frame it." (get-in definition [:workflow-file-meta :framing-prompt]))`
  to assert `:framing-prompt` is absent (e.g. `(nil? (get-in definition [:workflow-file-meta :framing-prompt]))`)
- [ ] Add compiler test — body with `{{input}}` produces `:vars {"input" {:from :workflow-input :path [:input]}}`
- [ ] Add compiler test — body with `{{original}}` produces `:vars {"original" {:from :workflow-original}}`
- [ ] Add compiler test — body with unknown `{{foo}}` (not declared) returns error
- [ ] Add compiler test — body with `{{my-var}}` declared in frontmatter `vars:`
  produces correct `:vars` entry
- [ ] Add compiler test — `:prompt-workflow` step referencing a `.md` file with
  `{{input}}` compiles to a step whose contribution has the correct `:vars`
- [ ] `bb test` green

## Slice 3 — Wiring: four `.edn` workflows → `:prompt-workflow`

- [ ] In `.psi/workflows/review-task-design.edn`: replace inline
  `:contributions` on each of the 6 steps with `:prompt-workflow "<filename>.md"`:
  - `ambiguity-review` → `"review-task-design-ambiguity-review.md"`
  - `ambiguity-follow-up` → `"review-task-design-ambiguity-follow-up.md"`
  - `inconsistency-review` → `"review-task-design-inconsistency-review.md"`
  - `inconsistency-follow-up` → `"review-task-design-inconsistency-follow-up.md"`
  - `clarity-status` → `"review-task-design-clarity-status.md"`
  - `final-summary` → `"review-task-design-final-summary.md"`
- [ ] In `.psi/workflows/review-task-plan.edn`: same for its 6 steps:
  - `ambiguity-review` → `"review-task-plan-ambiguity-review.md"`
  - `ambiguity-follow-up` → `"review-task-plan-ambiguity-follow-up.md"`
  - `inconsistency-review` → `"review-task-plan-inconsistency-review.md"`
  - `inconsistency-follow-up` → `"review-task-plan-inconsistency-follow-up.md"`
  - `clarity-status` → `"review-task-plan-clarity-status.md"`
  - `final-summary` → `"review-task-plan-final-summary.md"`
- [ ] In `.psi/workflows/implement-task.edn`: replace inline contributions on
  both steps:
  - `implement-pass` → `"implement-task-implement-pass.md"`
  - `final-summary` → `"implement-task-final-summary.md"`
- [ ] In `.psi/workflows/create-task-plan.edn`: replace inline contribution on
  the single step:
  - `create-plan` → `"create-task-plan-create-plan.md"`
- [ ] For each wired `.edn` file: remove `:tools`, `:skills` step-level keys
  that are now covered by the referenced `.md` frontmatter (merger is handled by
  `merge-markdown-session-config` — verify the frontmatter carries the needed
  keys and remove duplicates from `.edn` step)
- [ ] In `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
  (or a new test file): add loader tests asserting each of the four wired `.edn`
  workflows loads without error, has correct step count, and step contributions
  have non-empty `:vars` with `"input"` wired
- [ ] `bb test` green

## Final check

- [ ] Run `bb test` — all tests green
- [ ] Confirm all 8 acceptance criteria are satisfied (read design.md ACs 1–8)
- [ ] Commit
