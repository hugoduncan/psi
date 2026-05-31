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

## Implementation review follow-up

- [x] **Fix standard-vars override protection in `markdown-body->contribution`**: change
  `(merge standard-vars declared-vars)` to `(merge declared-vars standard-vars)` so that
  `"input"` and `"original"` always use their canonical source specs regardless of any
  declaration in `vars:` frontmatter. Current code allows `declared-vars` to silently
  override standard vars, violating the spec.
- [x] **Add test for standard-vars override protection**: add a compiler test asserting that
  a `.md` file with `vars: '{"input" {:from :workflow-original}}'` in frontmatter still
  compiles `{{input}}` to `{:from :workflow-input :path [:input]}` (standard spec wins).

## Final check

- [x] Run `bb test` — all tests green (3 pre-existing unrelated failures unchanged)
- [x] Confirm all 8 acceptance criteria are satisfied (read design.md ACs 1–8)
- [x] Commit

## task-test-review follow-up

- [x] **Add parser test — `vars:` with `:from :workflow-original` is accepted**: add a
  `parser_test.clj` test proving that `vars: '{"x" {:from :workflow-original}}'` parses
  without error and returns `{"x" {:from :workflow-original}}` under `:vars`. Covers the
  second valid `:from` value in `parse-vars-frontmatter`.
- [x] **Add parser test — `vars:` with map-valued `:from` is rejected**: add a
  `parser_test.clj` test proving that `vars: '{"x" {:from {:step "y" :yield :text}}}'`
  returns an error matching `#"unsupported :from values"`. Covers explicit rejection of
  step-output refs per design.

## test-shaper review follow-up

- [x] **Strengthen unknown-var error assertion**: in `compiler_test.clj` "body with unknown
  {{foo}} not declared returns error", add a second `is` asserting the specific unknown var
  name appears in the error message (e.g. `(re-find #"\"foo\"" error)` or
  `(str/includes? error "foo")`).
- [x] **Add compiler test — non-pattern token passes through without error**: add a
  `compiler_test.clj` test with a body containing a non-matching token like `{{1bad}}` or
  `{{}}` and assert the compile succeeds (no error) and `:vars` is empty (token is not
  treated as an unknown var). Covers design step 2a's pass-through guarantee.
- [x] **Separate wired-step assertion from final-summary in `workflow_definitions_test`**:
  in `implement-task-test`, `review-task-design-test`, and `review-task-plan-test`, change
  the `doseq` over all steps to only assert `step-has-input-var-wired?` for the wired
  (non-final-summary) steps. Add a separate assertion for the `final-summary` step that
  explicitly notes it is inline (not wired) — or at minimum filter out `final-summary`
  from the wired-step assertion loop so the test is specific to what this task changed.

## code-shaper review follow-up

- [x] **Remove `cond-> ... true` no-op guard in `markdown-session-step`**: replace
  `(cond-> {:name "step" :type :session :contributions ...} true (merge session-config))`
  with `(merge {:name "step" :type :session :contributions ...} session-config)`.
- [x] **Remove `cond-> ... true` no-op guard in `compile-edn-workflow-file`**: the
  `(cond-> (assoc config ...) true (update :workflow-file-meta ...) source-path ...)` form
  has an always-true first branch. Apply the `:file-kind :edn` `update` unconditionally
  and keep only the `source-path` conditional branch in `cond->`.
- [x] **Add comment to `strip-yaml-single-quotes` documenting the `''` escape limitation**:
  YAML single-quoted strings escape interior `'` as `''`; the helper does not unescape
  this. Add a comment stating the scope: only outer delimiters are stripped; interior `''`
  sequences are not unescaped (safe in practice because `vars:` values use EDN syntax with
  double-quoted strings).

## review-task-docs follow-up

- [x] **Document `.md` authoring surface in `doc/workflows.md`**: add a section (or
  extend the existing single-step `.md` authoring paragraph) explaining: (a) `{{input}}`
  and `{{original}}` are auto-wired in any `.md` body — no frontmatter declaration
  needed; (b) the `vars:` frontmatter key (EDN string syntax) for custom var bindings
  with `:from :workflow-input` or `:from :workflow-original`; (c) unknown `{{varname}}`
  tokens that are neither standard nor declared in `vars:` produce a compile-time error
  at workflow load. Include a minimal `.md` authoring example showing `{{input}}` usage.
- [x] **Add `CHANGELOG.md` entry for new `.md` authoring capabilities**: add an `Added`
  entry under `[Unreleased]` covering: `{{input}}` and `{{original}}` auto-expansion in
  `.md` workflow bodies; the `vars:` frontmatter key for custom var bindings; compile-time
  error for unknown `{{varname}}` tokens in `.md` workflow files.

## task-implementation-review follow-up (pass 2)

- [x] **Revert `review-task-plan-*` `.md` files to use `steps.md` as follow-up target**: commit `245d2152` changed all four `review-task-plan-*.md` bodies to write follow-ups to `design-steps.md` instead of `steps.md`. Revert the four files (`review-task-plan-ambiguity-review.md`, `review-task-plan-ambiguity-follow-up.md`, `review-task-plan-inconsistency-review.md`, `review-task-plan-inconsistency-follow-up.md`) to use `steps.md` as the follow-up target. Also fix the internal inconsistency in the follow-up files: the "read" target and "write/mark-done" target must both be `steps.md`.

## task-test-review follow-up (pass 2)

- [x] **Add compiler test — `vars:` declared in `.md` frontmatter threads through `:prompt-workflow`**: add a `compiler_test.clj` test that writes a `.md` file with `vars: '{"my-var" {:from :workflow-input :path [:some-field]}}'` in frontmatter and `{{my-var}}` in the body, references it via `:prompt-workflow` from an `.edn` step, and asserts the compiled step's contribution `:vars` contains `{"my-var" {:from :workflow-input :path [:some-field]}}`. This exercises `compile-prompt-workflow-step` passing `(:vars referenced)` to `markdown-body->contribution` with a non-nil value — the only untested code path for `vars:` threading.

## test-shaper review follow-up (pass 2)

- [x] **Add `:vars nil` to `markdown-parsed` fixture in `compiler_test.clj`**: the fixture
  is missing the `:vars` key that `parse-markdown-workflow-file` now returns. Add `:vars nil`
  so the fixture matches the actual parser output shape and prevents silent divergence.

- [x] **Strengthen `final-summary step is inline` assertion in three `workflow_definitions_test` tests**:
  in `review-task-design-test`, `review-task-plan-test`, and `implement-task-test`, change
  `(is (some? final-step) "final-summary step should exist")` to also assert
  `(is (seq (:contributions final-step)) "final-summary step should have inline contributions")`
  so the test actually verifies the step is inline rather than just present.

## code-shaper review follow-up (pass 2)

- [x] **Add `:vars nil` to `parsed` fixture in `compiler_target_authoring_test.clj`**: the
  `compile-target-authored-workflow-file-test` fixture (line 11–14) is missing `:vars nil`,
  same shape divergence that was fixed in `compiler_test.clj`'s `markdown-parsed` fixture.
  Add `:vars nil` to keep the fixture aligned with the documented parser output shape.

- [x] **Simplify `merge-markdown-session-config` — replace `reduce` with `merge`/`select-keys`**:
  `(reduce (fn [acc key] (if (contains? acc key) acc (if (contains? md-config key) (assoc ...) acc))) step keys)`
  is equivalent to `(merge (select-keys markdown-session-config markdown-session-config-keys) step)`.
  `merge` gives `step` precedence (later map wins) — the correct semantics. Replace the `reduce` form.

- [x] **Collapse redundant `nil`/`not= :md` file-kind guards in `read-prompt-workflow`**:
  the two `cond` branches `(nil? file-kind)` and `(not= :md file-kind)` produce near-identical
  error messages and can be collapsed to `(not= :md (file-kind-from-path resolved-path))`.
  Remove the `file-kind` `let` binding and inline the call.
