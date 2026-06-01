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

## 2026-06-01 — design ambiguity review pass 2 (ψ)

Re-reviewed design.md against `.psi/workflows/` exemplars (`gh-issue-implement.edn`,
`review-task-implementation.edn`, `review-task-design.edn`, `create-task-plan.edn`,
`implement-task.edn`, `review-step.edn`) and `doc/workflow-grammar.md` /
`-concepts.md`. Prior passes (input threading, create-task-plan claim, final-stage
surfacing, verification surface, doc/CHANGELOG, doc-citation, delegate yield) are
resolved. New actionable ambiguities found:

1. Step `:name` values unspecified. Acceptance criteria says the test asserts
   "the five step names/types/targets in order", but design fixes no step names.
   The test cannot assert names the design leaves open. Specify the five `:name`s.
2. Top-level `:name` / `:description` keys unspecified. Every target `.edn` has
   top-level `:name` and `:description`; registry presence (and the test's
   "definition `\"task-lifecycle\"` is present" assertion) is keyed off
   `:name "task-lifecycle"`. design.md never states these required top-level keys.
3. Per-step `:context` unspecified. Exemplar delegate steps carry
   `:context [{:type :source :from :workflow-original}]` (`gh-issue-implement.edn`,
   `review-task-implementation.edn`); grammar makes `:context` optional
   (omitted ≡ empty vector). The "input-only context threading" decision threads
   the id via `:prompt-string`, not `:context`, but design never states whether
   each step declares `:context` at all (and whether `:workflow-original` is
   carried). Per-step shape is ambiguous beyond `:name`/`:type`/`:target`/
   `:prompt-string`.

## 2026-06-01 — ambiguity follow-up execution pass 2 (ψ)

Executed all three pass-2 ambiguity-review follow-up items; design.md updated
(new "Concrete step and file shape" subsection + Acceptance criteria).

1. Step `:name` values — RESOLVED. Each delegate step's `:name` equals its
   `:target` (convention from `review-task-implementation.edn`, whose first
   step name mirrors its purpose). Stated as a 5-row table mapping order →
   `:name` → `:target`: `review-task-design`, `create-task-plan`,
   `review-task-plan`, `implement-task`, `review-task-implementation`. The
   verification test asserts these five `:name`/`:type :delegate`/`:target`
   triples in order.
2. Top-level keys — RESOLVED. design.md (Concrete step shape + Acceptance
   criteria) now requires top-level `:name "task-lifecycle"` and a
   `:description`. Verified all five target `.edn` files carry top-level
   `:name` + `:description`; registry presence and the test's definition-presence
   assertion key off the top-level `:name`.
3. Per-step `:context` — RESOLVED. Each delegate step carries
   `:context [{:type :source :from :workflow-original}]` and nothing else.
   Verified against exemplars: `gh-issue-implement.edn` and the first step of
   `review-task-implementation.edn` carry `:workflow-original`; grammar makes
   `:context` optional. Consistent with input-only threading — `:workflow-original`
   supplies the original request, NOT prior-stage summaries; no step references a
   prior step's yield (contrast later `review-task-implementation.edn` steps which
   deliberately chain yields). Task id travels via the `:map` `:prompt-string`
   `:input` field only.

## 2026-06-01 — design inconsistency review pass 2 (ψ)

Re-reviewed design.md after the pass-2 ambiguity follow-up added the "Concrete
step and file shape" subsection. Checked the new step-naming claim against the
cited exemplars (`review-task-implementation.edn`, `gh-issue-implement.edn`),
the verification-test surface (`workflow_definitions_test.clj`), and runtime
(`source_resolution.clj`). New actionable inconsistency found:

1. Step `:name`-equals-`:target` convention is contradicted by its own cited
   authority. design.md ("Concrete step and file shape" + Acceptance criteria)
   states each delegate step's `:name` "mirrors its `:target`" / "`:name` equals
   its `:target`", attributing this to "the convention used by
   `review-task-implementation.edn`, whose first step is named for its purpose".
   But in `review-task-implementation.edn` all five steps `:target "review-step"`
   while their `:name`s are `review-task-implementation`, `review-task-tests`,
   `review-test-shape`, `review-task-docs`, `review-code-shape` — names do NOT
   equal targets. `gh-issue-implement.edn` likewise: `:name "implement"` →
   `:target "implement-task-in-worktree"`, `:name "review"` →
   `:target "review-implementation-in-worktree"`. The exemplars show the
   opposite convention (names describe the step's role/purpose, distinct from
   target). The parenthetical "named for its purpose" is itself inconsistent
   with "mirrors its `:target`": being named for purpose ≠ equalling the target.
   The name=target choice may still be acceptable for this workflow (each stage
   delegates to a distinctly-named target, so name=target is unambiguous here),
   but the stated justification is wrong and must not claim exemplar precedent
   it lacks.

## 2026-06-01 — inconsistency follow-up execution pass 2 (ψ)

Executed the single pass-2 inconsistency-review follow-up item; design.md updated.

1. Step `:name`=`:target` justification — CORRECTED. Removed the false
   exemplar-precedent claim ("the convention used by
   `review-task-implementation.edn`") and the self-contradicting "named for its
   purpose" parenthetical. Verified the exemplars directly: in
   `review-task-implementation.edn` all five steps `:target "review-step"` with
   distinct purpose-named `:name`s; `gh-issue-implement.edn` likewise
   (`:name "implement"` → `:target "implement-task-in-worktree"`) — names do NOT
   equal targets there. design.md ("Concrete step and file shape") now states
   name=target is a **local** choice justified on its own merits (each stage
   delegates to a distinctly-named target, so reusing it as the step name is
   unambiguous and self-documenting), explicitly noting it is NOT the exemplar
   convention. Verification-test `:name` assertions stay in sync with the
   unchanged name=target table (Acceptance criteria line "`:name` equals its
   `:target`" is a plain factual statement of the chosen shape, no precedent
   claim).

## 2026-06-01 — design ambiguity review pass 3 (ψ)

Re-reviewed design.md against the runtime invocation surface and the five target
`.edn` files / exemplars after the prior two ambiguity passes plus two
inconsistency passes. Verified the previously-resolved items hold against code:

- Top-level invocation surface — VERIFIED, not ambiguous. `delegate-run`
  (`components/agent-session/src/psi/agent_session/workflow/core.clj` ~L378)
  builds `workflow-input` as `{:input prompt-text :original prompt-text}`, so
  invoking `task-lifecycle` via the delegate tool/command makes the
  orchestrator's own `:workflow-input` a map, and the design's internal
  `{:from :workflow-input :path [:input]}` references resolve at the top level
  exactly as for the sibling workflows. The design's input-addressability claim
  is correct.
- Input-threading `:map` form vs concepts doc — already covered. The
  string-yielding doc claim (`workflow-grammar-concepts.md` §"Workflow input and
  original request" / "Delegation boundary": prompt-string "rendered to a final
  string", treated as the "local workflow input surface") textually contradicts
  the `:map` map-shaped result; design + inconsistency-follow-up #1 already flag
  this as a known concepts-doc gap and name the runtime
  (`render-delegate-prompt-string`) as authority. Not new.
- Verification surface — VERIFIED. `review-task-implementation-test` (a
  pure-delegate workflow) uses `load-edn-only` + `(empty? errors)` +
  definition-presence + step names/types, exactly matching the design's
  prescribed test. `load-edn-only` (no `.md` refs) is the correct helper because
  `task-lifecycle.edn` is pure-delegate with no prompt-workflow `.md`.
- CHANGELOG precedent — VERIFIED. CHANGELOG `[Unreleased]` already lists
  `review-task-design` / `create-task-plan` Added entries "Invokable via
  `/delegate <name>`", matching the design's stated obligation.
- Per-step `:context [{:type :source :from :workflow-original}]` + input-only
  threading — VERIFIED against `review-task-implementation.edn` first step and
  `gh-issue-implement.edn`; consistent with stage-4 `implement-task` handoff NOT
  being threaded forward (input-only is a stated Resolved decision).

No new actionable ambiguity found. Design is concretely specified and every
checked claim matches the runtime and exemplars.

## 2026-06-01 — design inconsistency review pass 3 (ψ)

Re-reviewed design.md against the referenced test/loader artifacts
(`workflow_definitions_test.clj`: `load-edn-only`, `with-workflow-dir`,
`review-task-implementation-test`, `create-task-plan-test`;
`workflow-loader/core.clj` `load-workflow-definitions`). Prior inconsistency
items (doc-citation, delegate default-yield, name=target justification) remain
resolved and verified against runtime/exemplars. Two NEW actionable
inconsistencies between design.md and the cited artifacts:

1. Verification "same surface" over-claim (Acceptance criteria, ~L208-211).
   design.md prescribes a test asserting "the five step names/types/**targets**
   in order" and calls this "the same parser/compiler/definition surface used by
   the sibling `*-test` deftests (e.g. `review-task-implementation-test`,
   `create-task-plan-test`)". But the cited siblings assert only `:name`s and
   `:type`s — NOT `:target`s. `review-task-implementation-test` asserts
   `(mapv :name steps)` + `(mapv :type steps)` only; `create-task-plan-test`
   likewise (and its single step is a `:session` with no target). The prescribed
   target assertion is a superset beyond the cited exemplars, so "same surface"
   is inaccurate. Either drop "targets" / soften to "names and types like the
   siblings", or state explicitly that the target assertion is an addition the
   cited exemplars do not make.

2. Acceptance criterion over-claims what `(empty? errors)` proves (~L203-207).
   design.md: "The workflow parses and compiles cleanly (delegate targets
   resolve to workflow references). Verification is done by ... `load-edn-only`
   ... asserts `(empty? errors)`". `load-edn-only` writes ONLY the single
   `task-lifecycle.edn` into the temp `with-workflow-dir` (global dirs → [],
   project dir → temp dir), so the five target workflows are absent at load. The
   loader (`core.clj` `load-workflow-definitions` → `compiler/compile-workflow-files`)
   performs no cross-workflow target-resolution check — confirmed because the
   pure-delegate `review-task-implementation-test` loads only its own .edn
   (target `review-step` absent) yet asserts `(empty? errors)`. Therefore
   `(empty? errors)` does NOT verify "delegate targets resolve to workflow
   references"; the parenthetical claim that this verification establishes
   target resolution is unsupported by the cited loader/test artifacts. Either
   remove the target-resolution claim from what `(empty? errors)` proves, or
   move target-resolution verification to a mechanism that actually loads all
   targets together (cf. `review-workflow-set-loads-together-test`).

## 2026-06-01 — Slice 1: workflow file authored (ψ)

Wrote `.psi/workflows/task-lifecycle.edn` exactly per design "Concrete step and
file shape": top-level `:name "task-lifecycle"` + `:description`, five
`:type :delegate` steps in order with `:name` = `:target`
(`review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`,
`review-task-implementation`), each step `:prompt-string {:type :map :fields
{:input {:from :workflow-input :path [:input]}}}` and
`:context [{:type :source :from :workflow-original}]`. Terminal step declares no
`:yields` / `:terminal-contract` (relies on propagated session default yield).
Description uses literal `→` to match sibling convention (`gh-pr-refine.edn`),
not a `\u2192` escape. `clj-paren-repair` reports Success: 1 / Failed: 0; file
reads as valid EDN.

## 2026-06-01 — Slice 2: verification test added (ψ)

Added `task-lifecycle-test` `deftest` at the end of
`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`,
following the `review-task-implementation-test` `load-edn-only` pattern. Asserts
`(empty? errors)`, `(contains? definitions "task-lifecycle")`, step count = 5,
`(mapv :name steps)`, `(mapv :type steps)` (all `:delegate`), and — the
addition beyond the sibling deftests — `(mapv :target steps)` equal to the five
target names in order. Focused run (`--focus
psi.workflow-loader.workflow-definitions-test/task-lifecycle-test`): 1 test, 6
assertions, 0 failures. Full namespace: 10 tests, 110 assertions, 0 failures.
Per design, `(empty? errors)` here only proves isolated parse/compile — not
cross-workflow target resolution (the temp `with-workflow-dir` holds only
`task-lifecycle.edn`); this matches the accepted design scope.

## 2026-06-01 — Slice 3 + Slice 4: CHANGELOG, registry check, acceptance (ψ)

CHANGELOG: added `[Unreleased]` → `### Added` entry for `task-lifecycle`
immediately after the `create-task-plan` precedent line, noting invocation via
`/delegate task-lifecycle`.

Registry check (optional, beyond the required isolated loader test): ran a real
full-directory load via `psi.workflow-loader.core/load-workflow-definitions`
against the worktree root (NOT the isolated temp-dir helper). Result: 52
definitions loaded together with all five targets present; `task-lifecycle`
registered? true; no errors mentioning task-lifecycle; steps resolve in order as
`[name type target]` triples
`[review-task-design :delegate review-task-design]` …
`[review-task-implementation :delegate review-task-implementation]`. This
confirms registry presence after a real load and, incidentally, that the five
delegate targets exist in the real registry (the isolated loader test
deliberately does not establish this — see design Acceptance scope).

Acceptance criteria re-check vs `design.md`:
- `task-lifecycle` under `.psi/workflows/`, top-level `:name` + `:description`,
  registry-present after reload — ✅ (registry check above).
- Exactly five sequential `:delegate` steps, `:name` = `:target`, in the
  prescribed order — ✅ (file + loader test + registry check).
- Each step `:context [{:type :source :from :workflow-original}]`, no prior-step
  yield — ✅ (file).
- Each step `:prompt-string {:type :map :fields {:input {:from :workflow-input
  :path [:input]}}}` — ✅ (file).
- Parses/compiles in isolation; verification deftest with `(empty? errors)` +
  definition presence + five names/types/targets in order — ✅ (Slice 2 test,
  green).
- CHANGELOG `[Unreleased]` Added entry — ✅ (Slice 3).
- No `doc/workflows.md` edit required — ✅ (none made).

No deviations from the design. Implementation matches the concrete shape exactly.

## 2026-06-01 — inconsistency follow-up execution pass 3 (ψ)

Executed both pass-3 inconsistency-review follow-up items; design.md
Acceptance-criteria verification bullet rewritten.

1. "Same surface" over-claim — CORRECTED. Verified against
   `workflow_definitions_test.clj`: `review-task-implementation-test` asserts
   `(mapv :name steps)` + `(mapv :type steps)` only (no `:target`);
   `create-task-plan-test` likewise (single `:session` step, no target). design.md
   no longer calls the prescribed test "the same parser/compiler/definition
   surface" as the siblings. It now states the names/types assertions match the
   siblings and that the `:target` assertion is an explicit **addition** beyond
   them (justified: distinct per-stage targets make target order a meaningful
   invariant).

2. `(empty? errors)` over-claim — CORRECTED. Verified `load-edn-only` →
   `with-workflow-dir` writes ONLY the single `.edn` (global dirs → [], project
   dir → temp dir); loader does no cross-workflow target-resolution check
   (proof: pure-delegate `review-task-implementation-test` loads only its own
   `.edn`, target `review-step` absent, still asserts `(empty? errors)`).
   design.md now scopes `(empty? errors)` to "parses and compiles in isolation"
   and explicitly states it does NOT verify delegate-target resolution. Removed
   the "(delegate targets resolve to workflow references)" parenthetical from
   what the isolated test establishes. Added that actual target resolution, if
   wanted, needs a combined-load test (cf. `review-workflow-set-loads-together-test`,
   which writes every member `.edn` into one `with-workflow-dir`) — declared
   optional / not required for this task's first cut.

## 2026-06-01 — implementation review (task-implementation-review skill) (ψ)

Applied `task-implementation-review` to the committed implementation
(`.psi/workflows/task-lifecycle.edn`, the `task-lifecycle-test` deftest, and the
CHANGELOG `[Unreleased]` Added entry). Re-read design/plan/steps and verified the
code against the runtime and exemplars.

Findings — no new actionable feedback:

1. matches(design): the `.edn` is byte-for-byte the design's "Concrete step and
   file shape" — top-level `:name "task-lifecycle"` + `:description`, five
   `:type :delegate` steps in order with `:name` = `:target`, each
   `:prompt-string {:type :map :fields {:input {:from :workflow-input :path
   [:input]}}}` and `:context [{:type :source :from :workflow-original}]`,
   terminal step with no `:yields`/`:terminal-contract`. ✓
2. follows(architecture): uses only the existing converged `:delegate` grammar
   and the established `:map` prompt-string / `:workflow-original` context
   patterns (cf. `gh-issue-implement.edn`, `review-task-implementation.edn`); no
   new step shape, operation, prompt, or grammar. ✓
3. Runtime correctness of the central mechanism — VERIFIED against code (not just
   doc). `delegate.clj` `delegate-step-runtime-result` calls
   `source_resolution/render-delegate-prompt-string` (which returns the map
   `{:input "<task-id>"}` for `{:type :map}`) and passes it directly as the
   sub-workflow's `:workflow-input` (`create-run … :workflow-input prompt-string`).
   So `{:from :workflow-input :path [:input]}` resolves in the four explicit-ref
   targets, and `compiler.clj` `standard-vars` auto-wires `{{input}}` →
   `{:from :workflow-input :path [:input]}` for `create-task-plan`'s `:session`
   prompt-workflow — all five stages resolve the identifier uniformly. Top-level
   invocation is also covered: `core.clj` builds `{:input prompt-text :original
   prompt-text}`, so the orchestrator's own `:workflow-input` is a map. ✓
4. No reusable-pattern duplication, no unnecessary abstraction, no structural /
   performance issue: the file is a minimal pure-delegate chain; no synthesizing
   step, no yield-chaining, no redundant context.
5. Proof + docs: focused `task-lifecycle-test` green (1 test, 6 assertions);
   full `psi.workflow-loader.workflow-definitions-test` green (10 tests, 110
   assertions); `clj-kondo` on the edited test file clean (0/0). CHANGELOG
   `[Unreleased]` Added entry present and matches the
   `review-task-design`/`create-task-plan` precedent.

PASS_STATUS: REVIEW_COMPLETE — no new steps.md items added.
