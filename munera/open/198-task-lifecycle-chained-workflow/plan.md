# 198 — Plan

## Approach

Deliver a single new project-local orchestration workflow,
`.psi/workflows/task-lifecycle.edn`, that chains the five existing
task-lifecycle workflows as sequential `:delegate` steps, plus the design's
required verification test and CHANGELOG entry. No existing workflow, prompt,
operation, or grammar is touched.

The design is fully concrete (multiple ambiguity + inconsistency review passes,
all `design-steps.md` items checked); this plan executes its specified shape
directly rather than re-deriving it.

### Key decisions (from design, taken as fixed)

- **File shape.** Pure-`:delegate` `.edn` with top-level `:name "task-lifecycle"`
  and a `:description`. Mirrors the structure of
  `review-task-implementation.edn` (a `{:steps [...] :name ... :description ...}`
  map), minus the per-step yield-chaining context.
- **Five steps, in order**, each `:type :delegate`, each `:name` equal to its
  `:target`:
  `review-task-design` → `create-task-plan` → `review-task-plan` →
  `implement-task` → `review-task-implementation`.
  `:name` = `:target` is a **local** self-documenting choice (each target is
  distinct), explicitly *not* the exemplar convention.
- **Input threading.** Every step uses
  `:prompt-string {:type :map :fields {:input {:from :workflow-input :path [:input]}}}`
  so the delegated sub-workflow's `:workflow-input` resolves `:path [:input]`.
  This is the existing `:map` prompt-string form (cf. `gh-issue-implement.edn`,
  `review-task-implementation.edn`), not a new step shape.
- **Context.** Each step carries `:context [{:type :source :from :workflow-original}]`
  and nothing else. Input-only threading: no prior-stage summary is threaded
  forward (contrast the yield-chaining later steps of
  `review-task-implementation.edn`).
- **Terminal surfacing.** No trailing synthesizing/`final-summary` step. The
  last delegate (`review-task-implementation`) propagates its terminal session
  text yield unchanged. The terminal step declares no explicit `:yields` and no
  `:terminal-contract`.
- **Verification test.** Add a `deftest` to
  `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
  using `load-edn-only` (isolated single-`.edn` load), asserting
  `(empty? errors)`, definition `"task-lifecycle"` present, and the five
  step `:name`s / `:type`s / `:target`s in order. The `:target` assertion is an
  explicit **addition** beyond the sibling `*-test` deftests (which assert only
  names + types). `(empty? errors)` proves isolated parse/compile only — it does
  **not** verify cross-workflow target resolution.
- **CHANGELOG.** Add an `[Unreleased]` → `### Added` entry following the existing
  `review-task-design` / `create-task-plan` precedent (user-visible: invokable
  via `/delegate task-lifecycle`).
- **No `doc/workflows.md` edit** — it is the example-led authoring guide, not an
  exhaustive enumeration.

## Risks

- **Map prompt-string form is undocumented in the concepts docs.** The mechanism
  is verified against the runtime
  (`source-resolution/render-delegate-prompt-string` returns a map for
  `{:type :map}`) and matches two shipping exemplars. Mitigation: model the
  exact shape from `review-task-implementation.edn` / `gh-issue-implement.edn`;
  rely on the loader test to catch parse/compile regressions. The concepts-doc
  gap is noted in design and is out of scope to fix here.
- **EDN delimiter integrity.** Hand-authored `.edn` is easy to mis-balance.
  Mitigation: run `clj-paren-repair` after writing, and load via the test.
- **Test isolation over-claim.** `(empty? errors)` does not prove targets
  resolve. Accepted by design; the combined-load target-resolution test is
  optional and out of scope for the first cut.
- **Reload/registration.** Acceptance requires the workflow to appear in the
  registry after reload. The loader test (isolated parse/compile + definition
  presence) is the required surface; an optional runtime reload check can confirm
  registry visibility but is not a gating requirement.

## Slice order

One vertical slice (the workflow file) plus its proof and docs. Ordered so each
step is independently verifiable:

1. **Slice 1 — Workflow file.** Author `.psi/workflows/task-lifecycle.edn`;
   repair/format; confirm it reads as valid EDN.
2. **Slice 2 — Verification test.** Add the `task-lifecycle-test` `deftest`;
   run the workflow-loader test namespace; confirm green.
3. **Slice 3 — CHANGELOG.** Add the `[Unreleased]` `### Added` entry.
4. **Slice 4 — Coherence + close-out.** Lint; optional reload/registry check;
   confirm acceptance criteria; commit.
