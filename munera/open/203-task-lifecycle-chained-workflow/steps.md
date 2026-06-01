# 203 — Steps

## Slice 1 — Workflow file

- [x] Re-read `review-task-implementation.edn` and `gh-issue-implement.edn` to
      confirm the exact `:map` `:prompt-string` shape and the top-level
      `{:steps [...] :name ... :description ...}` map layout.
- [x] Write `.psi/workflows/task-lifecycle.edn` with top-level
      `:name "task-lifecycle"` and a `:description` ("Run a Munera task through
      its full design → plan → implement → review lifecycle by chaining the five
      task-lifecycle workflows in order.").
- [x] Add the five `:type :delegate` steps in order, each with `:name` = `:target`:
      `review-task-design`, `create-task-plan`, `review-task-plan`,
      `implement-task`, `review-task-implementation`.
- [x] Give every step
      `:prompt-string {:type :map :fields {:input {:from :workflow-input :path [:input]}}}`.
- [x] Give every step `:context [{:type :source :from :workflow-original}]` and
      no other context (no prior-step yield references).
- [x] Confirm the terminal (`review-task-implementation`) step declares no
      explicit `:yields` and no `:terminal-contract`.
- [x] Run `clj-paren-repair .psi/workflows/task-lifecycle.edn` to balance
      delimiters and format.

## Slice 2 — Verification test

- [x] Add a `task-lifecycle-test` `deftest` to
      `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
      using `load-edn-only "task-lifecycle.edn"`.
- [x] Assert `(empty? errors)` and `(contains? definitions "task-lifecycle")`.
- [x] Assert step count = 5 and `(mapv :name steps)` =
      `["review-task-design" "create-task-plan" "review-task-plan"
      "implement-task" "review-task-implementation"]`.
- [x] Assert `(mapv :type steps)` = `[:delegate :delegate :delegate :delegate
      :delegate]`.
- [x] Assert `(mapv :target steps)` equals the same five names in order (the
      `:target` assertion is the addition beyond the sibling `*-test` deftests).
- [x] Run the workflow-loader test namespace and confirm `task-lifecycle-test`
      passes green.

## Slice 3 — CHANGELOG

- [x] Add a `[Unreleased]` → `### Added` entry for the new `task-lifecycle`
      workflow, matching the `review-task-design` / `create-task-plan` precedent
      (note: invokable via `/delegate task-lifecycle`).

## Slice 4 — Coherence and close-out

- [x] Lint with `clj-kondo` the edited test file (and confirm no new warnings).
- [x] (Optional) Reload workflows / inspect the registry to confirm
      `task-lifecycle` is registered after reload.
- [x] Re-check every Acceptance criterion in `design.md` against the
      implementation; record outcomes in `implementation.md`.
- [x] Commit the workflow file, test, and CHANGELOG entry with a descriptive
      message (e.g. `⚒ Add task-lifecycle chained workflow (203)`).

## Test review follow-ups (task-test-review)

- [x] Add an assertion to `task-lifecycle-test` that every step's
      `:prompt-string` equals `{:type :map :fields {:input {:from :workflow-input
      :path [:input]}}}` (covers the design's central input-threading acceptance
      criterion, currently unguarded).
- [x] Add an assertion to `task-lifecycle-test` that every step's `:context`
      equals `[{:type :source :from :workflow-original}]` and references no
      prior-step yield (covers the input-only context-threading acceptance
      criterion).
- [x] Add an assertion to `task-lifecycle-test` that no step declares `:yields`
      or `:terminal-contract` (and in particular the terminal
      `review-task-implementation` step omits both), guarding the design's
      "Final-stage surfacing" contract that the terminal output relies on the
      propagated session default yield — currently confirmed only by inspection,
      not by the test.

## Test-shaper follow-ups (test-shaper)

- [x] Reshape the per-step `(is (every? pred steps))` checks in
      `task-lifecycle-test` (the `:prompt-string`, `:context`, and
      `:yields`/`:terminal-contract` assertions) into projected-collection
      equalities (e.g. `(is (= (repeat 5 <expected>) (mapv :prompt-string
      steps)))`, and `(mapv #(select-keys % [:yields :terminal-contract])
      steps)` for the absence check) so a failing assertion names the offending
      step and its actual value instead of collapsing to a bare `false`
      (`meaningful_failures`), matching the existing `(mapv :name …)` /
      `(mapv :target …)` assertion style (`consistent(assertion_style)`).
- [x] Remove the incidental duplication of the five-element step-name vector in
      `task-lifecycle-test` (currently written verbatim twice, for `:name` and
      `:target`) by binding it once in a `let` (e.g. `expected-targets`) and
      referencing it from both assertions, making the name=target invariant
      explicit rather than two copy-pasted literals that can silently drift
      (`economical` / `minimal_incidental_variation`).

## Docs review follow-ups (review-task-docs)

- [x] Document the `:map` `:prompt-string` form in
      `doc/workflow-grammar-concepts.md` (§ "Delegation" / § "Workflow input and
      original request"). The doc currently frames `:prompt-string` and the
      resulting `:workflow-input` as string-only ("rendered to a final string
      before delegation"; "`:workflow-input` is the delegated step's fully
      rendered `:prompt-string`"), which contradicts the map-shaped result the
      runtime (`source-resolution/render-delegate-prompt-string`) produces for
      `{:type :map}` and that the shipped `task-lifecycle.edn` relies on to
      thread the task identifier via `{:from :workflow-input :path [:input]}`.
      Add a short, additive note that a `{:type :map :fields {...}}`
      `:prompt-string` renders to a map and becomes the sub-workflow's
      map-shaped `:workflow-input` (so `:path` selectors resolve), matching the
      shipping exemplars (`gh-issue-implement.edn`,
      `review-task-implementation.edn`). Keep it minimal — do not restructure the
      authoring guide.

## Docs review follow-ups (review-task-docs) — pass 3

- [x] Move the `task-lifecycle` CHANGELOG entry from under the released
      `## [0.1.2166] - 2026-06-01` section into the empty `## [Unreleased]`
      section (add a `### Added` subheading under `[Unreleased]` and place the
      entry there), satisfying the acceptance criterion that `[Unreleased]`
      MUST carry the entry and avoiding mislabelling an unreleased change as
      shipped in `v0.1.2166`. The entry was authored into the released section
      (commit `8a1ac8501`, two commits after the `v0.1.2166` tag), so this is a
      placement defect, not a release stamp. Move only the `task-lifecycle`
      line; leave the sibling `review-task-design` / `create-task-plan` entries
      (out of this task's scope) untouched.
