# Steps — 187 `.md` workflow `{{input}}` expansion

## Slice 1 — Parser: `vars:` frontmatter support

- [x] In `components/workflow-loader/src/psi/workflow_loader/parser.clj`:
  add `:vars` to `allowed-md-frontmatter-keys`
- [x] In `parse-markdown-workflow-file`: after extracting frontmatter, read the
  raw `:vars` value (scalar string) with `clojure.edn/read-string`; validate
  result is a map; return error if parse fails or value is not a map
- [x] Return parsed vars map (or `nil` when absent) under `:vars` key in the
  result so the return shape is:
  `{:workflow-kind :single-step-markdown :name :description :session-config :body :vars}`
- [x] In `components/workflow-loader/test/psi/workflow_loader/parser_test.clj`:
  add test — `vars:` EDN string parses to map, returned under `:vars`
- [x] Add test — missing `vars:` key returns `:vars nil`
- [x] Add test — `vars:` with non-map EDN string returns error
- [x] Add test — `vars:` with invalid EDN string returns error
- [x] `bb test` green

## Slice 2 — Compiler: expansion, framing-prompt removal, vars threading

- [x] In `components/workflow-loader/src/psi/workflow_loader/compiler.clj`:
  update `markdown-body->contribution` to accept an optional `declared-vars` map
  argument (default `nil`)
- [x] In `markdown-body->contribution`: scan body for all `{{varname}}` tokens
  using a regex (e.g. `#"\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}"`)
- [x] Auto-wire `"input"` → `{:from :workflow-input :path [:input]}`
  and `"original"` → `{:from :workflow-original}` for any matching tokens found
- [x] Merge `declared-vars` into the auto-wired map (declared vars take
  precedence for non-standard names; standard vars always use their canonical
  source specs regardless of any override in `declared-vars`)
- [x] Error (throw `ex-info`) for any `{{varname}}` remaining after
  merging standard and declared vars — i.e. any token not in the final vars map
- [x] In `compile-markdown-workflow-file`: remove `:framing-prompt body` from
  the `workflow-file-meta` map (leave `{:file-kind :md}` or no `:framing-prompt`
  key at all)
- [x] In `compile-prompt-workflow-step`: pass `(:vars referenced)` as the
  `declared-vars` argument when calling `markdown-body->contribution`
- [x] In `components/workflow-loader/test/psi/workflow_loader/compiler_test.clj`:
  update the `compile-markdown-workflow-file-test` fixture or assertion — the
  compiled step's contribution `:vars` should now contain
  `{"input" {:from :workflow-input :path [:input]}}` when body contains `{{input}}`
- [x] Update `compile-markdown-workflow-file-test` to assert no `:framing-prompt`
  key in `workflow-file-meta`
- [x] In `compiler_target_authoring_test.clj` line ~19: update assertion from
  `(= "Frame it." (get-in definition [:workflow-file-meta :framing-prompt]))`
  to assert `:framing-prompt` is absent (e.g. `(nil? (get-in definition [:workflow-file-meta :framing-prompt]))`)
- [x] Add compiler test — body with `{{input}}` produces `:vars {"input" {:from :workflow-input :path [:input]}}`
- [x] Add compiler test — body with `{{original}}` produces `:vars {"original" {:from :workflow-original}}`
- [x] Add compiler test — body with unknown `{{foo}}` (not declared) returns error
- [x] Add compiler test — body with `{{my-var}}` declared in frontmatter `vars:`
  produces correct `:vars` entry
- [x] Add compiler test — `:prompt-workflow` step referencing a `.md` file with
  `{{input}}` compiles to a step whose contribution has the correct `:vars`
- [x] Add compiler test — `:tools` present on `.edn` step takes precedence over
  `tools:` in referenced `.md` frontmatter; `.md` `tools:` fills in only when
  `.edn` step omits `:tools`
- [x] `bb test` green

## Slice 3 — Wiring: four `.edn` workflows → `:prompt-workflow`

- [x] In `.psi/workflows/review-task-design.edn`: replace inline
  `:contributions` on each of the **5 non-final-summary steps** with
  `:prompt-workflow "<filename>.md"` (final-summary kept inline — see plan.md):
  - `ambiguity-review` → `"review-task-design-ambiguity-review.md"`
  - `ambiguity-follow-up` → `"review-task-design-ambiguity-follow-up.md"`
  - `inconsistency-review` → `"review-task-design-inconsistency-review.md"`
  - `inconsistency-follow-up` → `"review-task-design-inconsistency-follow-up.md"`
  - `clarity-status` → `"review-task-design-clarity-status.md"`
  - `final-summary` — **keep inline** (carries `:source` contributions with step-output
    refs that cannot be expressed in `.md` frontmatter vars)
- [x] In `.psi/workflows/review-task-plan.edn`: same for its **5 non-final-summary steps**:
  - `ambiguity-review` → `"review-task-plan-ambiguity-review.md"`
  - `ambiguity-follow-up` → `"review-task-plan-ambiguity-follow-up.md"`
  - `inconsistency-review` → `"review-task-plan-inconsistency-review.md"`
  - `inconsistency-follow-up` → `"review-task-plan-inconsistency-follow-up.md"`
  - `clarity-status` → `"review-task-plan-clarity-status.md"`
  - `final-summary` — **keep inline** (same reason as review-task-design)
- [x] In `.psi/workflows/implement-task.edn`: replace inline contribution on
  **implement-pass only** (final-summary kept inline):
  - `implement-pass` → `"implement-task-implement-pass.md"`
  - `final-summary` — **keep inline** (carries `:source` contributions with
    `:workflow-original` and `implement-pass` step-output yield)
- [x] In `.psi/workflows/create-task-plan.edn`: replace inline contribution on
  the single step:
  - `create-plan` → `"create-task-plan-create-plan.md"`
- [x] For each wired `.edn` file: remove `:tools`/`:skills` step-level keys from
  wired steps — values are identical to `.md` frontmatter, so
  `merge-markdown-session-config` supplies them from the `.md` file. Eliminates
  redundancy; runtime behaviour unchanged.
- [x] In `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`:
  **update** the four existing `deftest` blocks (`review-task-design-test`,
  `review-task-plan-test`, `implement-task-test`, `create-task-plan-test`) to use
  `load-edn-with-md-refs` with both the `.edn` and all referenced `.md` files.
  Added `load-edn-with-md-refs` helper. Assertions for wired steps confirm
  non-empty `:vars` with `"input"` wired.
- [x] `bb test` green

## Plan ambiguity follow-up (pass 1)

- [x] **Resolve final-summary wiring for implement-task, review-task-plan, review-task-design**:
  Decision: option (a) — exclude final-summary steps from wiring in these three workflows.
  The step-output source refs (`:workflow-original`, step yields) are out of scope for `.md`
  frontmatter vars; wiring would silently drop them. The three `.md` final-summary files exist
  but are intentionally not referenced. Documented in `plan.md` under Key decisions.
  Slice 3 wiring instructions updated below to reflect the exclusion.

- [x] **Update existing `workflow_definitions_test.clj` tests to work after wiring**:
  Documented in `plan.md` under Risks. Slice 3 test step updated below to explicitly require
  updating (not just adding to) the existing `deftest` blocks to use `with-workflow-dir` with
  both the `.edn` and all referenced `.md` files.

## Inconsistency follow-up (pass 3)

- [x] **Fix steps.md Slice 2 error mechanism**: updated Slice 2 step to say "throw `ex-info`"
  — matching `design.md` implementation path step 2d.

- [x] **Fix steps.md Slice 2 regex**: updated Slice 2 step's example regex to
  `#"\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}"` — matching `design.md` step 2a.

- [x] **Resolve steps.md Slice 3 `:tools`/`:skills` removal vs design.md**: chose option (a).
  Verified that all wired `.edn` step `:tools`/`:skills` values are identical to the
  corresponding `.md` frontmatter values — removing them produces identical runtime
  behaviour (merge-markdown-session-config fills in from `.md` frontmatter when the step
  key is absent). Updated `design.md` step 5 to explicitly authorize removing
  `:tools`/`:skills` step-level keys after wiring, with rationale.

## Final check

- [x] Run `bb test` — all tests green (3 pre-existing unrelated failures unchanged)
- [x] Confirm all 8 acceptance criteria are satisfied (read design.md ACs 1–8)
- [ ] Commit
