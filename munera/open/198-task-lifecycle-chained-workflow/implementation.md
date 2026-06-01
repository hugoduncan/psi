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

## 2026-06-01 — test review (task-test-review skill) (ψ)

Applied `task-test-review` to `task-lifecycle-test`
(`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
L437-460) against design acceptance criteria. Skill criteria:

- well_formed: ✓ — mirrors `review-task-implementation-test` `load-edn-only`
  shape; clean assertions.
- ¬mock ∧ ¬stub ∧ nullable infra: ✓ — uses the real loader
  (`loader/load-workflow-definitions`) over a temp `with-workflow-dir`; no
  mocks/stubs.
- ∀ behaviour(design) ∃ covering test: ✗ — **coverage gap** (actionable).

Coverage gaps (acceptance criteria not asserted by the test):

1. **Input-threading `:prompt-string` not asserted.** Design's central
   mechanism and an explicit acceptance criterion: each step must carry
   `:prompt-string {:type :map :fields {:input {:from :workflow-input :path
   [:input]}}}`. The test asserts only `:name`/`:type`/`:target`. A regression
   in `:prompt-string` (wrong `:path`, missing `:fields`, string instead of
   `:map`, dropped key) would leave the test green while breaking the whole
   workflow's identifier threading — the single highest-value invariant, and it
   is unguarded. `create-task-plan-test` precedent already asserts input wiring
   via `step-has-input-var-wired?`; this test makes no equivalent assertion.

2. **Per-step `:context` not asserted.** Acceptance criterion: each step must
   carry `:context [{:type :source :from :workflow-original}]` and no prior-step
   yield (input-only threading). The test does not assert `:context` on any
   step, so a missing/altered/yield-chained `:context` would pass undetected.

Both are explicit `design.md` acceptance criteria, both unverified. The prior
implementation-review note ("REVIEW_COMPLETE") assessed code-vs-design but did
not evaluate test coverage of these criteria.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-01 — test review follow-up execution (ψ)

Executed both task-test-review follow-up items; `task-lifecycle-test` now guards
the two previously-unverified acceptance criteria.

1. `:prompt-string` assertion — ADDED. `(every? ...)` asserts each step's
   `:prompt-string` equals `{:type :map :fields {:input {:from :workflow-input
   :path [:input]}}}`. Guards the central identifier-threading invariant.
2. `:context` assertion — ADDED. `(every? ...)` asserts each step's `:context`
   equals `[{:type :source :from :workflow-original}]` — input-only threading,
   no prior-step yield reference.

Shape rationale: verified `compiler.clj` `compile-edn-steps` passes
non-`:prompt-workflow` steps through UNCHANGED (no `target-ir-compiler`
nesting under `:delegate`), so `:steps` retain the raw authored EDN shape —
`:target`/`:prompt-string`/`:context` are top-level on each step (consistent
with the pre-existing `(mapv :target steps)` assertion). The new assertions
therefore compare against the authored `.edn` map shape directly.

Verification: focused `task-lifecycle-test` → 1 test, 8 assertions (was 6),
0 failures. `clj-kondo` on the edited test file: 0 errors / 0 warnings.

PASS_STATUS: follow-ups complete.

## 2026-06-01 — test review pass 2 (task-test-review skill) (ψ)

Re-applied `task-test-review` to `task-lifecycle-test`
(`workflow_definitions_test.clj`) after the prior pass-1 follow-ups landed
(`:prompt-string` + `:context` assertions, now 8 assertions, green). Re-read
design acceptance criteria, the `.edn`, and the test.

Skill criteria:
- well_formed: ✓ — clean `load-edn-only` shape, mirrors siblings.
- ¬mock ∧ ¬stub ∧ nullable infra: ✓ — real loader over temp `with-workflow-dir`.
- ∀ behaviour(design) ∃ covering test: one residual gap (actionable).

Already-covered (no duplication): definition presence, five `:name`/`:type`/
`:target` in order, `:prompt-string` `:map` form, `:context` input-only. The
two pass-1 follow-ups closed the highest-value gaps.

Residual coverage gap (NEW, not a duplicate of pass-1):

1. **Terminal-step `:yields` / `:terminal-contract` absence unguarded.**
   Design's "Final-stage surfacing" makes the terminal output contract depend on
   the terminal `review-task-implementation` step declaring **no** explicit
   `:yields` and **no** `:terminal-contract` (it relies on the propagated
   session default yield). `steps.md` Slice 1 has an explicit verification item
   for this ("Confirm the terminal … step declares no explicit `:yields` and no
   `:terminal-contract`"), and the implementation-review note asserts it ✓ — but
   that confirmation is by inspection, not by the test. `task-lifecycle-test`
   asserts `:prompt-string` and `:context` equality per step but never asserts
   the absence of `:yields`/`:terminal-contract`. A regression adding `:yields`
   or `:terminal-contract` to any step (especially the terminal) would pass the
   test green while changing the design's terminal-surfacing contract. Add an
   assertion that no step declares `:yields` or `:terminal-contract` (or
   specifically that the terminal step omits both).

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-01 — test review pass-2 follow-up execution (ψ)

Executed the single pass-2 task-test-review follow-up item; `task-lifecycle-test`
now guards the terminal-surfacing contract.

1. `:yields` / `:terminal-contract` absence — ADDED. New `testing` block asserts
   `(every? ...)` that no step declares `:yields` or `:terminal-contract` (via
   `(not (contains? step :yields/:terminal-contract))`), plus an explicit
   `(last steps)` terminal check that the terminal `review-task-implementation`
   step omits both. Guards the design's "Final-stage surfacing" contract: the
   terminal output relies on the propagated session default yield, so any
   regression adding `:yields`/`:terminal-contract` (especially on the terminal
   step) now fails the test instead of silently changing the contract.

Shape rationale: same as the pass-1 follow-ups — `compiler.clj` passes
non-`:prompt-workflow` `:delegate` steps through unchanged, so the loaded steps
retain the authored EDN shape and `:yields`/`:terminal-contract` (if present)
would be top-level step keys. The authored `task-lifecycle.edn` declares neither
on any step, so `contains?`-absence assertions hold.

Verification: focused `task-lifecycle-test` → 1 test, 11 assertions (was 8),
0 failures. `clj-kondo` on the edited test file: 0 errors / 0 warnings.

PASS_STATUS: follow-up complete.

## 2026-06-01 — test review pass 3 (task-test-review skill) (ψ)

Re-applied `task-test-review` to `task-lifecycle-test`
(`workflow_definitions_test.clj` L437-482) after the pass-1/2 follow-ups landed
(now 11 assertions, green). Re-read design acceptance criteria, the `.edn`, and
the test.

Skill criteria:
- well_formed: ✓ — clean `load-edn-only` shape, mirrors `review-task-implementation-test`.
- ¬mock ∧ ¬stub ∧ nullable infra: ✓ — real loader (`loader/load-workflow-definitions`)
  over temp `with-workflow-dir`; no mocks/stubs/fakes.
- ∀ behaviour(design) ∃ covering test: ✓ — no new actionable gap.

Behavioural acceptance criteria now all covered (no duplication):
- definition presence (keyed off top-level `:name "task-lifecycle"`) ✓
- exactly five `:delegate` steps, `:name`=`:target`, in prescribed order ✓
- `:prompt-string {:type :map ...}` input threading (central invariant) ✓ (pass-1)
- `:context [{:type :source :from :workflow-original}]` input-only ✓ (pass-1)
- terminal `:yields`/`:terminal-contract` absence ✓ (pass-2)

Considered residual gap and rejected as non-actionable: the only acceptance-criterion
field the test does not assert is the top-level `:description` presence. Judged
NOT actionable — `:description` is registry-listing metadata, not behaviour;
the skill's coverage criterion ranges over `behaviour(design)`, and no sibling
exemplar (`review-task-implementation-test`, `create-task-plan-test`) asserts
`:description`. `(contains? definitions "task-lifecycle")` already establishes
the top-level `:name` (the only behaviourally load-bearing top-level key, since
the definition is keyed by it). Asserting a free-text blurb would add brittle
low-signal coverage diverging from the established sibling surface.

Registry-presence-after-reload criterion is covered by design scope: isolated
parse/compile is the required test surface; the combined-load/registry check is
optional (and was performed once manually, recorded in Slice 3+4).

Verification: focused `task-lifecycle-test` → 1 test, 11 assertions, 0 failures.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — test review (test-shaper skill) (ψ)

Applied `test-shaper` to `task-lifecycle-test`
(`workflow_definitions_test.clj` L437-482, 11 assertions, green). Unlike the
three prior `task-test-review` passes (which addressed *coverage* gaps), this
pass evaluates the existing assertions for clarity / signal / robustness /
economy. The test is well-shaped overall: single concern, real loader over temp
`with-workflow-dir` (no mocks), deterministic, behaviour-focused (asserts loaded
definition shape, not internals), mirrors the sibling `*-test` `load-edn-only`
surface. Two NEW actionable shaping issues found (neither a coverage gap, so not
a duplicate of the prior passes):

1. **Low-signal per-step assertions (`meaningful_failures`).** The
   `:prompt-string`, `:context`, and `:yields`/`:terminal-contract` checks all
   use `(is (every? pred steps))`, which collapses to a bare `false` on failure
   — the message names neither the offending step nor the divergent value, so a
   regression in step 3's `:prompt-string` reports only "expected true, got
   false". test-shaper's `meaningful_failures` wants a failing test to explain
   the contract violation. Reshaping each to a projected-collection equality —
   e.g. `(is (= (repeat 5 <expected>) (mapv :prompt-string steps)))` (or
   `(mapv #(select-keys % [:yields :terminal-contract]) steps)` for the absence
   check) — surfaces the offending step's actual value in the failure diff
   while asserting the identical contract. The already-present `(mapv :name
   steps)` / `(mapv :target steps)` assertions are exactly this shape, so this
   also restores intra-test assertion-style consistency
   (`consistent(assertion_style)`).

2. **Incidental literal duplication (`economical` / `minimal_incidental_variation`).**
   The five-element vector `["review-task-design" "create-task-plan"
   "review-task-plan" "implement-task" "review-task-implementation"]` is written
   verbatim twice (for `:name` and again for `:target`). The name=target
   invariant is the *point* of the assertion, yet it is currently encoded as two
   independent copy-pasted literals that could silently drift. A single
   `let`-bound `expected-targets` referenced by both the `:name` and `:target`
   assertions compresses the ceremony and makes name=target explicit rather than
   coincidental. (If the projected-equality reshape in #1 is taken, the same
   binding can seed the `(repeat 5 ...)` expectations too.)

Both are pure test-quality improvements; the workflow `.edn` and its behavioural
coverage are unchanged. Neither overlaps the prior coverage follow-ups.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-01 — test-shaper follow-ups executed (ψ)

Executed the two newly added test-shaper follow-ups in
`task-lifecycle-test` (`workflow_definitions_test.clj`):

1. **Projected-collection equalities (`meaningful_failures`).** Replaced the
   three `(is (every? pred steps))` assertions with projected-collection `=`
   checks so a failure names the offending step and its actual value:
   - `:prompt-string` → `(= (repeat 5 <expected>) (mapv :prompt-string steps))`
   - `:context` → `(= (repeat 5 <expected>) (mapv :context steps))`
   - `:yields`/`:terminal-contract` absence → `(= (repeat 5 {})
     (mapv #(select-keys % [:yields :terminal-contract]) steps))`.
   The now-redundant `terminal`-only `last` sub-check was dropped: the
   per-step `select-keys` equality already covers the terminal step (index 4),
   so the explicit terminal re-assertion added no independent signal.
   Style now matches the existing `(mapv :name …)` / `(mapv :target …)`
   assertions (`consistent(assertion_style)`). `(= lazy-seq vector)` is
   value-equal for sequential collections in Clojure, so `repeat`/`mapv`
   comparison is valid.
2. **De-duplicated step-name vector (`economical`).** Bound the five-element
   name/target vector once as `expected-targets` in the enclosing `let` and
   referenced it from both the `:name` and `:target` assertions, making the
   name=target invariant explicit instead of two copy-pasted literals.

Verification: `clj-paren-repair` clean; `clojure -M:test --focus
psi.workflow-loader.workflow-definitions-test` → 10 tests, 113 assertions,
0 failures; `clj-kondo --lint` on the test file → 0 errors, 0 warnings.
Both `steps.md` test-shaper items checked.

## 2026-06-01 — test review (test-shaper skill) pass 2 (ψ)

Re-applied `test-shaper` to `task-lifecycle-test`
(`workflow_definitions_test.clj` L437-476) after the prior pass-1 test-shaper
follow-ups landed (projected-collection equalities + `let`-bound
`expected-targets`). Focused run green: 1 test, 9 assertions, 0 failures.

Evaluation vs test-shaper criteria — all satisfied:
- simple / single_concern: ✓ one workflow's loaded shape; clean AAA.
- consistent(assertion_style): ✓ now uniformly `(= (repeat 5 …) (mapv …))` /
  `(mapv :name/:target …)` projections, matching the sibling `*-test` surface.
- meaningful_failures: ✓ projected equalities name the offending step + actual
  value on failure (the pass-1 fix to the former `(every? pred steps)` checks).
- economical / minimal_incidental_variation: ✓ name=target literal bound once
  as `expected-targets` (pass-1 fix), referenced by both assertions.
- robust: ✓ real loader (`loader/load-workflow-definitions`) over temp
  `with-workflow-dir`; no mocks/stubs; deterministic; behaviour-focused
  (asserts loaded definition shape, not internals).

Considered residual and rejected as NON-actionable: the count `5` is encoded in
four `(repeat 5 …)` forms independently of `(count expected-targets)`. Not
actionable — the explicit `(= 5 (count steps))` assertion fails first on any
count change, so a wrong-length `repeat` cannot yield a false green; deriving the
count from `expected-targets` would add low-signal ceremony that diverges from
the established sibling assertion style. The two prior-pass test-shaper fixes
already resolved the only genuine clarity/signal/economy issues.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — user-facing docs review (review-task-docs, ψ)

Applied the `review-task-docs` checklist (README, `doc/`, CHANGELOG;
accuracy ∧ completeness ∧ consistency) to the implemented task.

What is correct:
- CHANGELOG `[Unreleased]` → `### Added` carries the `task-lifecycle` entry.
  Its named/ordered chain (`review-task-design`, `create-task-plan`,
  `review-task-plan`, `implement-task`, `review-task-implementation`) and the
  `/delegate task-lifecycle` invocation match `.psi/workflows/task-lifecycle.edn`
  (`:target` order and `:description`) exactly. Follows the established
  `review-task-design` / `create-task-plan` precedent. ✅
- No removed behaviours; no stale doc references. ✅
- `README.md` carries no per-workflow enumeration to update; `doc/workflows.md`
  is the example-led authoring guide (line 14 "primary example-led guide";
  line 58 "authoritative example set"), not an exhaustive list — design's
  no-`doc/workflows.md`-edit decision is accurate. ✅

One actionable finding (new):
1. **`doc/workflow-grammar-concepts.md` contradicts the `:map` prompt-string
   form this workflow ships on.** The concepts doc states `:prompt-string` "is
   rendered to a final string before delegation" (≈line 146), that the delegated
   workflow "treats the final rendered prompt string as its local workflow input
   surface" (≈line 155), that `:workflow-input` "is the delegated step's fully
   rendered `:prompt-string`" (≈line 197), and that a delegate `:prompt-string`
   "may also use this same template shape before rendering to a final string"
   (≈line 221) — all string-only framing. The runtime
   (`source-resolution/render-delegate-prompt-string`) returns a *map* for
   `{:type :map}`, and `task-lifecycle.edn` (plus shipping exemplars
   `gh-issue-implement.edn`, `review-task-implementation.edn`) relies on that map
   shape so the sub-workflow's `:workflow-input` resolves `:path [:input]`. This
   is a genuine user-facing doc accuracy/completeness gap: the concepts doc does
   not document the `:map` form and textually contradicts it, while a now-shipped
   workflow depends on it. The task design flags this as a "known gap" and scopes
   the fix out; this docs review records it as actionable because the docs-review
   lens is exactly what surfaces cross-doc inaccuracy, and the fix is *additive*
   (a short note documenting the `:map` prompt-string form and its map-shaped
   `:workflow-input`), not the broad authoring-guide restructure the design
   feared. Minimal-scope follow-up added to `steps.md`.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Docs review follow-up executed (2026-06-01)

Completed the docs-review follow-up: documented the `:map` `:prompt-string`
form in `doc/workflow-grammar-concepts.md`. Three additive edits, no
restructure:

1. § "Delegation boundary" — added a paragraph after the string-only
   `:prompt-string` framing describing the `{:type :map :fields {...}}` form
   rendering to a **map** (not a string), each field resolved from its
   source-spec, becoming the delegated workflow's `:workflow-input` so
   `{:from :workflow-input :path [<key>]}` resolves. Cites the shipping
   exemplars `gh-issue-implement.edn` / `review-task-implementation.edn`.
2. Same section — generalized the "treats the final rendered prompt string as
   its local workflow input surface" line to "— or, for the `:map` form, the
   rendered map —".
3. § "Workflow input and original request" — added a clarifying note that
   `:workflow-input` is a string for literal/`:template` forms and a **map** for
   the `:map` form, so `:path` selectors resolve against it.

Verification of accuracy against runtime: re-read
`source-resolution/render-delegate-prompt-string` — `{:type :map}` returns
`(into {} (map ... (:fields prompt-string)))`, i.e. a map. Both exemplars
(`gh-issue-implement.edn`, `review-task-implementation.edn`) confirmed to use
`:type :map`. The doc note now matches runtime authority; the design's
"known gap" / textual contradiction is closed. steps.md item marked done.

## 2026-06-01 — user-facing docs review pass 2 (review-task-docs, ψ)

Re-applied the `review-task-docs` checklist after the prior pass's `:map`
prompt-string doc follow-up landed (`c5083b3ce`). Re-verified all five checklist
items against the committed state:

- **CHANGELOG**: `task-lifecycle` Added entry is present and accurate (named
  five-stage chain + `/delegate task-lifecycle` invocation match
  `task-lifecycle.edn` `:target` order and `:description` exactly). It now sits
  under released `[0.1.2166] - 2026-06-01` (a release was stamped after the
  entry was added to `[Unreleased]`; `[Unreleased]` is now empty) — correct
  per `bb release:tag` semantics, not a regression. ✅
- **`doc/workflow-grammar-concepts.md`** `:map` prompt-string note (lines 148,
  157, 203-205): re-verified accurate against runtime —
  `source_resolution/render-delegate-prompt-string` returns a map via
  `(into {} … (:fields …))` for `{:type :map}` (confirmed at L188-192). The
  edit is additive, minimal, cites the shipping exemplars, and closes the prior
  doc-contradiction gap. ✅
- **`doc/workflows.md`**: correctly NOT updated — "authoritative example set"
  (L58) is a curated teaching list (`planner`/`builder`/`reviewer`, `plan-build`,
  `plan-build-review`, `delegate-build-review`, `gh-bug-triage-modular`), not an
  exhaustive workflow enumeration; design's no-edit decision holds. ✅
- **README**: no per-workflow enumeration to update (L127 points to workflow
  usage docs generically). ✅
- **Removed behaviours / stale references**: none. ✅

No new actionable docs feedback. The single prior actionable finding (the `:map`
prompt-string doc gap) is fully and accurately resolved. No `steps.md` items
added.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — code-shaper review (code-shaper, ψ)

Applied the code-shaper lens (`simplicity ∧ consistency ∧ robustness`) to the
in-scope artifacts: `.psi/workflows/task-lifecycle.edn` and the
`task-lifecycle-test` deftest. No production Clojure source is in scope (the
task ships only a declarative workflow `.edn`, a loader test, a CHANGELOG entry,
and an additive doc note).

- **`task-lifecycle.edn`** — simple (single responsibility: sequence five
  delegate stages), consistent (uniform step shape, `:name` = `:target`,
  identical `:prompt-string` / `:context` across all five steps), robust (loads
  clean, `clj-kondo` 0/0, valid EDN, all five steps `:delegate`; shape mirrors
  shipping exemplars `review-task-implementation.edn` / `gh-issue-implement.edn`).
  The verbatim repetition of the identical `:prompt-string` and `:context` maps
  across the five steps is *intrinsic* to the declarative workflow grammar —
  there is no abstraction/templating primitive at the step level to DRY it, and
  every exemplar workflow repeats per-step context likewise. Not a code-shaping
  defect; introducing a non-grammatical indirection would *reduce* robustness
  and local comprehensibility.
- **`task-lifecycle-test`** — already shaped by the prior test-shaper pass:
  projected-collection equalities (`(= (repeat 5 …) (mapv …))`,
  `(mapv #(select-keys % [:yields :terminal-contract]) …)`) for meaningful
  named failures, single `expected-targets` `let` binding referenced by both the
  `:name` and `:target` assertions (no duplicated literal). Consistent assertion
  style, single responsibility, locally comprehensible. No actionable gap.

Verification: `clj-kondo --lint …workflow_definitions_test.clj` → 0 errors /
0 warnings; `task-lifecycle.edn` reads as valid EDN with 5 delegate steps.

No new actionable code-shaping feedback. No `steps.md` items added.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — implementation review pass 2 (task-implementation-review skill) (ψ)

Re-applied `task-implementation-review` to the committed implementation
(`.psi/workflows/task-lifecycle.edn`, the `task-lifecycle-test` deftest, the
CHANGELOG `[Unreleased]→[0.1.2166]` Added entry, and the additive
`doc/workflow-grammar-concepts.md` `:map` note) after the intervening
test-shaper / docs / code-shaper passes landed. Independent re-verification:

1. matches(design): `.edn` is byte-for-byte the design's "Concrete step and file
   shape" — top-level `:name "task-lifecycle"` + `:description`, five
   `:type :delegate` steps in order, `:name` = `:target`, uniform
   `:prompt-string {:type :map :fields {:input {:from :workflow-input :path
   [:input]}}}` and `:context [{:type :source :from :workflow-original}]`,
   terminal step with no `:yields`/`:terminal-contract`. ✓ (re-read the file)
2. follows(architecture): existing converged `:delegate` grammar only; no new
   step shape, operation, prompt, or grammar. ✓
3. central mechanism re-verified against code (not doc): re-read
   `source_resolution/render-delegate-prompt-string` (L182-195) — `{:type :map}`
   returns `(into {} (map … (:fields …)))`, a map; so `{:from :workflow-input
   :path [:input]}` resolves in the four explicit-ref targets and `{{input}}`
   auto-wires for `create-task-plan`'s `:session`. ✓
4. no reusable-pattern duplication, no unnecessary abstraction, no structural /
   performance issue: minimal pure-delegate chain; per-step map repetition is
   intrinsic to the declarative grammar (no step-level templating primitive),
   matching every exemplar.
5. proof: focused `task-lifecycle-test` re-run green (1 test, 9 assertions, 0
   failures); CHANGELOG `task-lifecycle` Added entry present and accurate.

No new actionable feedback; no new `steps.md` items. All prior review passes
(implementation, three test-review, two test-shaper, two docs, code-shaper) plus
this pass converge on complete. Implementation is ready for closure.

PASS_STATUS: REVIEW_COMPLETE
