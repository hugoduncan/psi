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

- [ ] Specify the five delegate step `:name` values. Acceptance criteria says the
      verification test asserts "the five step names/types/targets in order", but
      design fixes no step names. State the five `:name`s so the test target is
      concrete and the test can assert them.
- [ ] Specify the required top-level workflow keys. Every target `.edn` carries
      top-level `:name` and `:description`; registry presence and the test's
      "definition `task-lifecycle` is present" assertion are keyed off
      `:name "task-lifecycle"`. State that `task-lifecycle.edn` must include the
      top-level `:name "task-lifecycle"` and a `:description`.
- [ ] State whether each delegate step declares `:context`. Exemplars
      (`gh-issue-implement.edn`, `review-task-implementation.edn`) carry
      `:context [{:type :source :from :workflow-original}]`; grammar makes
      `:context` optional (omitted ≡ empty vector). Given the "input-only context
      threading" decision (id travels via `:prompt-string`, not `:context`), state
      definitively whether each step omits `:context` or carries
      `:workflow-original`, so the per-step shape is fully specified.
