# Design follow-up steps

## Ambiguity review follow-ups

- [x] Specify the exact `:prompt-string` form for each delegate step. Confirm that
      input threading requires the `:map` form `{:type :map :fields {:input {:from
      :workflow-input :path [:input]}}}` (per `gh-issue-implement.edn`) so the
      delegated sub-workflow's `:workflow-input` resolves `:path [:input]`. Reconcile
      with the "Constraints" wording that names only `:invoke|:session|:delegate`.
- [x] Correct the Scope claim that "all five target workflows consume
      `{:from :workflow-input :path [:input]}`". `create-task-plan.edn` has no such
      ref; its `{{input}}` resolves inside `create-task-plan-create-plan.md`. State
      per-stage how the task identifier reaches each target.
- [x] Define how the orchestrator "surfaces the outcome of the final stage":
      whether there is a final synthesizing step (cf. other workflows'
      `final-summary`) or the last delegate's yield is surfaced directly, and what
      `:yields` the terminal step declares.
- [x] Make the acceptance verification surface concrete: name the actual
      parser/compiler/definition entry point (ns / test / REPL call) instead of
      "narrowest relevant ... surface".
- [x] Resolve the doc/CHANGELOG obligation: `doc/workflows.md` exists but is
      example-led (not an exhaustive enumeration), and project-local-workflow
      user-visibility is undecided. State definitively whether this task must update
      `doc/workflows.md` and/or CHANGELOG.

## Inconsistency review follow-ups

- [x] Resolve the doc-citation contradiction in "Input threading mechanism". The
      design cites `doc/workflow-grammar-concepts.md` § "Workflow input and
      original request" as authority for `:workflow-input` becoming the map
      `{:input "<task-id>"}`, but that section describes `:workflow-input` as the
      "fully rendered `:prompt-string`" / "final rendered prompt string" (a
      string) and never documents the `:map` form. Either cite the actual
      authority (runtime `render-delegate-prompt-string` in
      `source_resolution.clj`, which returns a map for `{:type :map}`) and/or note
      the doc gap, so the design's mechanism is not justified by a contradicting
      reference.
- [x] Correct the "Final-stage surfacing" claim that a `:delegate` step "yields
      its delegated run's text result by default". The documented default
      (concepts § default yielded-value composition) is that a delegate "yields
      the called workflow's yielded value unchanged". State that the terminal
      output is text because the chain terminates in session steps
      (`review-task-implementation` → `review-code-shape` delegate → `review-step`
      session text yield), rather than asserting text-as-default as a universal
      delegate property.

## Ambiguity review follow-ups (pass 2)

- [x] Specify the five delegate step `:name` values. Acceptance criteria says the
      verification test asserts "the five step names/types/targets in order", but
      design fixes no step names. State the five `:name`s so the test target is
      concrete and the test can assert them.
- [x] Specify the required top-level workflow keys. Every target `.edn` carries
      top-level `:name` and `:description`; registry presence and the test's
      "definition `task-lifecycle` is present" assertion are keyed off
      `:name "task-lifecycle"`. State that `task-lifecycle.edn` must include the
      top-level `:name "task-lifecycle"` and a `:description`.
- [x] State whether each delegate step declares `:context`. Exemplars
      (`gh-issue-implement.edn`, `review-task-implementation.edn`) carry
      `:context [{:type :source :from :workflow-original}]`; grammar makes
      `:context` optional (omitted ≡ empty vector). Given the "input-only context
      threading" decision (id travels via `:prompt-string`, not `:context`), state
      definitively whether each step omits `:context` or carries
      `:workflow-original`, so the per-step shape is fully specified.

## Inconsistency review follow-ups (pass 2)

- [x] Correct the step `:name`-equals-`:target` justification. design.md claims
      each delegate step's `:name` "mirrors its `:target`" / "`:name` equals its
      `:target`" is "the convention used by `review-task-implementation.edn`", but
      that file's five steps all `:target "review-step"` with purpose-named
      `:name`s (`review-task-implementation`, `review-task-tests`, …) — names do
      NOT equal targets; `gh-issue-implement.edn` is the same (`implement` →
      `implement-task-in-worktree`). Remove the false exemplar-precedent claim
      (and the self-contradicting "named for its purpose" parenthetical). Either
      justify name=target on its own merits (distinct per-stage targets make
      name=target unambiguous here) or rename steps to purpose-style names
      matching the actual exemplar convention; keep the verification-test
      `:name` assertions in sync with whichever is chosen.

## Inconsistency review follow-ups (pass 3)

- [ ] Correct the verification "same surface" over-claim (Acceptance criteria).
      design.md prescribes a test asserting "the five step
      names/types/**targets** in order" and calls it "the same
      parser/compiler/definition surface used by the sibling `*-test` deftests
      (e.g. `review-task-implementation-test`, `create-task-plan-test`)". Those
      siblings assert only `:name`s and `:type`s, NOT `:target`s
      (`review-task-implementation-test`: `(mapv :name steps)` + `(mapv :type
      steps)`; `create-task-plan-test` likewise, single `:session` step has no
      target). Either drop/soften the "targets ... same surface" framing or
      state explicitly that the `:target` assertion is an addition beyond the
      cited exemplars.
- [ ] Fix the acceptance criterion that over-claims `(empty? errors)`. design.md
      states "The workflow parses and compiles cleanly (delegate targets resolve
      to workflow references). Verification is done by ... `load-edn-only` ...
      asserts `(empty? errors)`". `load-edn-only` loads ONLY `task-lifecycle.edn`
      in isolation (`with-workflow-dir`: global dirs → [], project dir → temp dir
      containing just that file), and the loader performs no cross-workflow
      target-resolution check — `review-task-implementation-test` loads its own
      .edn without `review-step.edn` yet asserts `(empty? errors)`. So
      `(empty? errors)` does NOT verify "delegate targets resolve to workflow
      references". Either remove the target-resolution claim from what
      `(empty? errors)` establishes, or verify target resolution via a mechanism
      that loads all targets together (cf. `review-workflow-set-loads-together-test`).
