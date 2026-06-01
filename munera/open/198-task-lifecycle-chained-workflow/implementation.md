# Implementation notes

## 2026-06-01 — design ambiguity review (ψ)

Reviewed design.md against workflow grammar (`doc/workflow-grammar.md`,
`doc/workflow-grammar-concepts.md`) and the five target workflow .edn files.
Actionable ambiguities found:

1. Input-threading mechanism unspecified. Scope claims sub-workflows consume
   `{:from :workflow-input :path [:input]}`, but per concepts doc a delegated
   workflow's `:workflow-input` IS the fully-rendered `:prompt-string`. For
   `:path [:input]` to resolve, the prompt-string must use the `:map` form
   (`{:type :map :fields {:input ...}}`), as in `gh-issue-implement.edn`. Design
   never specifies this form; "Constraints" name only `:invoke|:session|:delegate`.
2. `create-task-plan` is the odd one out: its .edn has no `:from :workflow-input`
   ref; `{{input}}` resolves inside `create-task-plan-create-plan.md`. The blanket
   "all five consume `{:from :workflow-input :path [:input]}`" claim is inaccurate.
3. Final-stage surfacing mechanism unspecified — final synthesizing step vs.
   last delegate yield, and what `:yields` the terminal step declares.
4. "Narrowest relevant parser/compiler/definition surface" verification is vague:
   no concrete ns/test/REPL entry point named.
5. doc/workflows.md / CHANGELOG obligation unresolved: doc is example-led (not an
   exhaustive list) and project-local-workflow user-visibility is undecided.

## 2026-06-01 — ambiguity follow-up execution (ψ)

Executed all five ambiguity-review follow-up items; design.md updated.

1. Input-threading form — RESOLVED. `:prompt-string {:type :map :fields {:input
   {:from :workflow-input :path [:input]}}}`. Evidence: `gh-issue-implement.edn`
   `implement`/`review` delegate steps use the `:map` form; concepts doc § says a
   delegated step's rendered `:prompt-string` becomes the sub-workflow's
   `:workflow-input`. Reconciled with Constraints: `:map` prompt-string is part
   of the existing `:delegate` grammar, not a new step shape.
2. `create-task-plan` "odd one out" claim — CORRECTED, and the original premise
   was wrong. `compiler.clj` `standard-vars` auto-wires the `{{input}}` token to
   `{:from :workflow-input :path [:input]}`. So `{{input}}` in
   `create-task-plan-create-plan.md` resolves to the same `:path [:input]` ref
   as the other four. Verified by `create-task-plan-test` asserting
   `step-has-input-var-wired?`. design.md now states per-stage how the identifier
   reaches each target, all uniformly via `:path [:input]`.
3. Final-stage surfacing — RESOLVED. No extra synthesizing step; last delegate
   (`review-task-implementation`) text yield surfaces directly, mirroring
   `review-task-implementation.edn` which ends on its last delegate. Terminal
   step declares no explicit `:yields` (default text yield); no
   `:terminal-contract` needed.
4. Verification surface — RESOLVED to a concrete entry point: add a `deftest` in
   `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
   using `load-edn-only` (→ `loader/load-workflow-definitions`), asserting
   `(empty? errors)`, definition presence, and the five step names/types/targets
   in order — same surface as the sibling `*-test` deftests.
5. doc/CHANGELOG obligation — RESOLVED definitively. CHANGELOG `[Unreleased]`
   Added entry REQUIRED (precedent: existing `review-task-design` /
   `create-task-plan` entries are listed as user-visible, invokable via
   `/delegate <name>`). `doc/workflows.md` edit NOT required — it is the
   example-led authoring guide, not an exhaustive workflow enumeration.

## 2026-06-01 — design inconsistency review (ψ)

Reviewed design.md for internal consistency and against referenced artifacts
(`doc/workflow-grammar-concepts.md`, the five target `.edn` files, `review-step.edn`,
`source_resolution.clj`, `compiler.clj`, `workflow_definitions_test.clj`). The
five-stage ordering, per-stage `:path [:input]` threading claims, verification
surface, and CHANGELOG/doc obligations are internally consistent and match the
artifacts. New actionable inconsistencies found:

1. Doc-citation contradiction for input threading. "Input threading mechanism"
   asserts the `:map` form makes `:workflow-input` the map `{:input "<task-id>"}`
   "per `doc/workflow-grammar-concepts.md` § 'Workflow input and original
   request'". But that section says `:workflow-input` is the delegated step's
   "fully rendered `:prompt-string`" / "final rendered prompt string" — a string,
   not a map — and the `:map` prompt-string form is not documented anywhere in
   that doc (nor in workflow-grammar.md / workflow-ir.md). The runtime
   (`source_resolution.clj` `render-delegate-prompt-string`) does return a map for
   the `:map` form, so the mechanism is correct against code but the cited doc
   contradicts it. The design rests its central mechanism on an authority that
   does not support (and textually contradicts) it.

2. Misstated delegate default-yield. "Final-stage surfacing" claims a `:delegate`
   step "yields its delegated run's text result by default". The documented rule
   (concepts § default yielded-value) is: delegate "yields the called workflow's
   yielded value unchanged" — not specifically text. The terminal output is text
   only because the chain bottoms out in session steps
   (`review-task-implementation` → `review-code-shape` delegate → `review-step`
   session yields `:final-llm-reply` text). The design states text-as-default as a
   universal delegate property rather than tracing it through the
   review-task-implementation → review-step chain.

## 2026-06-01 — inconsistency follow-up execution (ψ)

Executed both inconsistency-review follow-up items; design.md updated.

1. Doc-citation contradiction — RESOLVED. "Input threading mechanism" no longer
   cites `doc/workflow-grammar-concepts.md` § "Workflow input and original
   request" as the authority for the map-shaped `:workflow-input`. Verified
   against runtime:
   `source_resolution.clj` `render-delegate-prompt-string` returns a map via
   `(into {} ...)` over `:fields` for `{:type :map}`. Verified the concepts doc
   describes `:workflow-input` only as "fully rendered `:prompt-string`" /
   "final rendered prompt string" (string) and does not document the `:map`
   form (also absent from workflow-grammar.md / workflow-ir.md). design.md now
   names the runtime as authority, points to `gh-issue-implement.edn` /
   `review-task-implementation.edn` usage, and flags the concepts-doc gap.

2. Delegate default-yield — CORRECTED. "Final-stage surfacing" no longer claims
   a `:delegate` step yields text by default. Verified the documented default
   (concepts § default yielded-value composition, line 287): delegate "yields
   the called workflow's yielded value unchanged". design.md now traces the text
   yield through the chain: task-lifecycle → review-task-implementation (ends on
   `review-code-shape` delegate) → review-step (terminates in `:session` step
   yielding `:final-llm-reply` text). Confirmed against `.psi/workflows/`
   `review-task-implementation.edn` (last step `review-code-shape` :delegate →
   "review-step") and `review-step.edn` (terminal `:session` steps). Text is the
   propagated session default, not a delegate property.
