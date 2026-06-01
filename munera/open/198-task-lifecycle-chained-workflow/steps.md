# 198 — Steps

## Slice 1 — Workflow file

- [ ] Re-read `review-task-implementation.edn` and `gh-issue-implement.edn` to
      confirm the exact `:map` `:prompt-string` shape and the top-level
      `{:steps [...] :name ... :description ...}` map layout.
- [ ] Write `.psi/workflows/task-lifecycle.edn` with top-level
      `:name "task-lifecycle"` and a `:description` ("Run a Munera task through
      its full design → plan → implement → review lifecycle by chaining the five
      task-lifecycle workflows in order.").
- [ ] Add the five `:type :delegate` steps in order, each with `:name` = `:target`:
      `review-task-design`, `create-task-plan`, `review-task-plan`,
      `implement-task`, `review-task-implementation`.
- [ ] Give every step
      `:prompt-string {:type :map :fields {:input {:from :workflow-input :path [:input]}}}`.
- [ ] Give every step `:context [{:type :source :from :workflow-original}]` and
      no other context (no prior-step yield references).
- [ ] Confirm the terminal (`review-task-implementation`) step declares no
      explicit `:yields` and no `:terminal-contract`.
- [ ] Run `clj-paren-repair .psi/workflows/task-lifecycle.edn` to balance
      delimiters and format.

## Slice 2 — Verification test

- [ ] Add a `task-lifecycle-test` `deftest` to
      `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
      using `load-edn-only "task-lifecycle.edn"`.
- [ ] Assert `(empty? errors)` and `(contains? definitions "task-lifecycle")`.
- [ ] Assert step count = 5 and `(mapv :name steps)` =
      `["review-task-design" "create-task-plan" "review-task-plan"
      "implement-task" "review-task-implementation"]`.
- [ ] Assert `(mapv :type steps)` = `[:delegate :delegate :delegate :delegate
      :delegate]`.
- [ ] Assert `(mapv :target steps)` equals the same five names in order (the
      `:target` assertion is the addition beyond the sibling `*-test` deftests).
- [ ] Run the workflow-loader test namespace and confirm `task-lifecycle-test`
      passes green.

## Slice 3 — CHANGELOG

- [ ] Add a `[Unreleased]` → `### Added` entry for the new `task-lifecycle`
      workflow, matching the `review-task-design` / `create-task-plan` precedent
      (note: invokable via `/delegate task-lifecycle`).

## Slice 4 — Coherence and close-out

- [ ] Lint with `clj-kondo` the edited test file (and confirm no new warnings).
- [ ] (Optional) Reload workflows / inspect the registry to confirm
      `task-lifecycle` is registered after reload.
- [ ] Re-check every Acceptance criterion in `design.md` against the
      implementation; record outcomes in `implementation.md`.
- [ ] Commit the workflow file, test, and CHANGELOG entry with a descriptive
      message (e.g. `⚒ Add task-lifecycle chained workflow (198)`).
