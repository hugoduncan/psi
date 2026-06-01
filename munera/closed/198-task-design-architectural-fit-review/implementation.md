# Implementation notes — 198 task-design architectural-fit review

## 2026-06-01 — design ambiguity review (pass 1)

Reviewed `design.md` against the actual `review-task-design.edn`, the existing
ambiguity/inconsistency review/follow-up `.md` prompts, the shared
`review-follow-up-design.md` profile, the `task-design` skill, and the workflow
grammar docs. Recent git history shows task 199 already landed the shared
follow-up profile. Found new actionable ambiguities (see design-steps.md A1–A6):

- A1: Design's per-aspect follow-up premise is stale post-199. The existing
  ambiguity/inconsistency follow-ups already share `review-follow-up-design.md`;
  there are no dedicated per-aspect follow-up `.md` files. AC2/scope/"Adjacent
  task-like work" all assume a per-aspect follow-up shape that no longer exists.
  Whether `architecture-follow-up` reuses the shared profile or adds a new
  dedicated prompt is unresolved.
- A2: AC2a/scope name a follow-up prompt
  `review-task-design-architecture-follow-up.md`, but the existing follow-up steps
  use `review-follow-up-design.md`. Filename + reuse intent ambiguous.
- A3: AC3 describes the existing flow as "clarity-status → final-summary"
  terminating cleanly, but the current `clarity-status` step has **no `:on` map**
  and `final-summary` follows positionally. How termination/transition to
  `final-summary` actually occurs (implicit next, `:done`, fall-through) is
  unspecified, so "rewiring ... routing" is under-defined.
- A4: "rewire the workflow's entry so the loop starts at architecture-review" does
  not state how the entry/start step is determined (first vector element vs.
  explicit declaration). Ambiguous whether inserting a new first step suffices.
- A5: AC2 demands the new pair "mirror the existing review-aspect pattern," but the
  existing follow-up uses `workflow/constant-routing {:route "DONE"}` with
  `:on {"DONE" {:goto <next>}}`. The new `architecture-follow-up`'s DONE target is
  unspecified (ambiguity-review? next aspect?).
- A6: final-summary `:contributions` currently sources ambiguity-review +
  inconsistency-review yields. AC3 says final-summary should mention the
  architectural-fit pass, but whether architecture-review's yield is added as a
  contribution source is unspecified.

## 2026-06-01 — ambiguity follow-up (A1–A6 resolved)

Verified semantics against authoritative sources before editing design.md:
- `.psi/workflows/review-task-design.edn`: follow-ups already use shared
  `review-follow-up-design.md`; no per-aspect follow-up files exist (199). Existing
  follow-up steps route DONE→next review aspect via `:on {"DONE" {:goto ...}}`.
  `clarity-status` is a non-judged step with **no `:on`**; `final-summary` is the
  last step. `final-summary` `:contributions` explicitly source ambiguity-review +
  inconsistency-review yields.
- `components/workflow-runtime/src/.../statechart.clj`: `initial-step-id` =
  `(first (effective-step-order definition))` → start step is the first `:steps`
  element. `compile-leaf-step` transitions a non-judged step on `:actor/done` to
  `next-step-target` = next step in order, or `:completed` if last → confirms
  `clarity-status → final-summary` is **implicit positional fall-through**.

Resolutions written into design.md:
- A1/A2: retired stale per-aspect premise; `architecture-follow-up` reuses shared
  `review-follow-up-design.md`; only one new prompt (`architecture-review.md`).
- A3: termination is unchanged implicit positional fall-through (clarity-status has
  no `:on`; final-summary is last). Task does not alter it.
- A4: start step = first `:steps` element; inserting architecture-review first makes
  it the entry; no explicit start declaration.
- A5: `architecture-follow-up` DONE → `:goto "ambiguity-review"` (next aspect).
- A6: add `architecture-review` step yield to `final-summary` `:contributions`
  AND reference the architectural-fit pass in the template prose.

Updated design.md Scope, AC2/AC2a/AC3, Architectural alignment, Adjacent work, and
Resolved decisions (added items 5–9). All six A-items completed; none blocked.
PASS_STATUS for this follow-up pass: all newly added ambiguity items resolved.

## 2026-06-01 — design inconsistency review (pass 1)

Reviewed `design.md` for internal inconsistency and against the actual
`review-task-design.edn`, the shared `review-follow-up-design.md` profile, the
existing `review-task-design-*-review.md` prompts, the `review-task-docs` skill
shape, `doc/workflows.md` shared-follow-up section, the
`workflow_definitions_test.clj` review-task-design test, and the
`statechart.clj` start-step / leaf fall-through logic.

Verified consistent (no action): post-199 shared-profile reuse claims (AC2a,
decision 5); `architecture-review`/`architecture-follow-up` `:on` targets mirror
the existing `*-review`/`*-follow-up` DONE-to-next-aspect routing (decisions 7,
A5-resolved); `final-summary` `:contributions` `:yield :text` shape (AC3,
decision 9); start step = first `:steps` element and `clarity-status` non-judged
positional fall-through both confirmed against `initial-step-id` /
`compile-leaf-step` (decisions 6, 8); skill path/frontmatter convention (AC1);
no stale positive "per-aspect" language remains (the two surviving mentions are
negations retiring the premise).

New actionable inconsistency (see design-steps.md I1):

- I1: AC2a says the new `architecture-review` prompt "ends with exactly one
  `PASS_STATUS: ACTIONABLE_FEEDBACK | REVIEW_COMPLETE` line," but Scope and
  "Architectural alignment" require the prompt to be *consistent with the
  existing `review-task-design-*-review.md` files*, which end with a **two-line**
  menu ("End your final response with exactly one of:" / `PASS_STATUS:
  ACTIONABLE_FEEDBACK` / `PASS_STATUS: REVIEW_COMPLETE`). A builder following
  AC2a literally would author a single `A | B` line, diverging from the
  established convention AC2a's own surrounding text demands. Internal
  contradiction between AC2a's literal line prescription and the
  consistency-with-existing-files commitment.

PASS_STATUS for this review pass: ACTIONABLE_FEEDBACK.

## 2026-06-01 — inconsistency follow-up (I1 resolved)

Confirmed the existing convention against the live prompts: both
`review-task-design-ambiguity-review.md` and
`review-task-design-inconsistency-review.md` end with the two-line menu
("End your final response with exactly one of:" / `PASS_STATUS: ACTIONABLE_FEEDBACK`
/ `PASS_STATUS: REVIEW_COMPLETE`), not a single `A | B` line.

Resolved I1 by restating both single-line `PASS_STATUS: ACTIONABLE_FEEDBACK |
REVIEW_COMPLETE` occurrences in design.md (Scope step description + AC2a) to the
established two-line form: the prompt lists both options on separate lines and the
agent emits exactly one status line. Removed the internal contradiction between
AC2a's literal line prescription and the consistency-with-existing-files commitment
in Scope / Architectural alignment. I1 completed; not blocked.

## 2026-06-01 — implementation (slices 1–4 complete)

Implemented all four slices; all acceptance criteria satisfied.

- AC1: added `.psi/skills/review-task-architecture/SKILL.md` — thin lens
  (frontmatter name/description/lambda + minimal body framing architectural fit
  and pointing at in-context AGENTS.md/META.md/doc/architecture.md). No
  duplicated principle list. (slice 1)
- AC2/AC2a: added `.psi/workflows/review-task-design-architecture-review.md`
  modelled on the ambiguity-review prompt (tools read/bash/edit/write; skills
  work-independently + review-task-architecture; five-step body; two-line
  PASS_STATUS menu). (slice 2)
- AC2/AC3: prepended `architecture-review` + `architecture-follow-up` as the
  first two `:steps` of `review-task-design.edn`. architecture-review gated by
  pass-status-routing, `:on {"REPEAT" → architecture-follow-up, "DONE" →
  ambiguity-review}`; architecture-follow-up reuses shared
  `review-follow-up-design.md`, constant-routing DONE → ambiguity-review.
  Verified 8 steps; positional start = architecture-review; ambiguity →
  inconsistency → clarity-status → final-summary fall-through unchanged.
  final-summary now sources the architecture-review yield and the prose mentions
  the architectural-fit pass (both inline `.edn` and standalone
  final-summary.md). (slice 3)
- AC5: extended `workflow_definitions_test.clj` review-task-design-test (8
  steps, names/types, architecture judge + :on wiring, design-profile body +
  predate guard for architecture-follow-up, final-summary contribution) and the
  set-loads-together test md-refs; added the new prompt to the
  artifact-targets design list. workflow-definitions-test + routing-test green;
  lint clean. (slice 3)
- AC6: documented the architectural-fit aspect in `doc/workflows.md` (new
  "Architectural-fit design review" section + updated shared-follow-up bullet).
  No other review-workflow reference doc needed the aspect. (slice 4)

No deviations from the design. The task introduced no runtime code paths — only
skill/prompt/workflow config + tests + docs, exactly as designed.

## 2026-06-01 — implementation review (task-implementation-review)

Reviewed the landed implementation against design.md and architecture. Verified:
SKILL.md, architecture-review prompt, `review-task-design.edn` rewiring (8 steps,
positional start = architecture-review, REPEAT→follow-up, DONE→ambiguity-review,
shared `review-follow-up-design.md`, final-summary architecture-review yield +
prose), standalone `final-summary.md` in sync with the inline `.edn` template,
tests, and `doc/workflows.md`. Ran the workflow-loader suite:
`clojure -M:test --focus psi.workflow-loader.workflow-definitions-test` →
9 tests, 148 assertions, 0 failures.

Architectural fit: config/prompt-only change; no runtime/dispatch/resolver/
mutation paths touched; follows the existing review-aspect pattern (addition, not
modification) and reuses the shared follow-up profile (`follow`, not `introduce`).
No new patterns, unnecessary abstractions, or structural-performance concerns.

One actionable spec-fidelity discrepancy (see steps.md closeout R1): AC1 and
Resolved-decision 4 require the skill to be minimal with **"No duplicated
principle list"**, and this implementation note claims the same. But
`review-task-architecture/SKILL.md` does enumerate the principle set in both the
frontmatter `lambda` (`one_way ∧ dispatch_resolver_mutation_boundaries ∧
VSM_layering ∧ extension_isolation ∧ effects_as_data ∧ ¬silent_shims`) and the
body prose. Either trim the enumeration to satisfy AC1/decision-4 literally, or
reconcile AC1/decision-4 (and the "No duplicated principle list" claim above) to
permit an illustrative, non-normative list. Minor; no behavioural impact.

PASS_STATUS for this review pass: ACTIONABLE_FEEDBACK.

## 2026-06-01 — R1 follow-up resolved

Executed the implementation-review follow-up (steps.md R1). Trimmed
`review-task-architecture/SKILL.md` to satisfy AC1 / Resolved-decision 4 literally
rather than weaken the criterion:

- Frontmatter `lambda`: removed the enumerated
  `check(one_way ∧ dispatch_resolver_mutation_boundaries ∧ VSM_layering ∧
  extension_isolation ∧ effects_as_data ∧ ¬silent_shims)` conjunction; now
  `λtask. review(design_architectural_fit) ∧ judge(fit, ¬correctness ∧ ¬clarity)
  ∧ consult(in_context_architecture_sources)`.
- Body prose: dropped the example principle enumeration ("bypassing dispatch,
  reading state outside resolvers, …"); the body now frames the lens and directs
  the reviewing agent to the in-context architecture sources (`AGENTS.md`,
  `META.md`, `doc/architecture.md`) for the principles/boundaries.

`doc/workflows.md` retains a prose description of the principles the aspect judges
against — that is user-facing documentation describing the aspect, not a duplicated
checklist *in the skill*; AC1/decision-4 constrain the skill body, so it is left
as-is.

Reran `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test` →
9 tests, 148 assertions, 0 failures. Config/content-only change; no runtime paths.

PASS_STATUS for this follow-up: resolved.

## 2026-06-01 — implementation review (pass 2, post-R1)

Re-reviewed the landed implementation against design.md and architecture after the
R1 follow-up. Verified all six acceptance criteria hold on the current artifacts:

- AC1: `review-task-architecture/SKILL.md` is now minimal — frontmatter `lambda`
  and body carry no enumerated principle list (R1 resolved); body frames the lens
  and points at in-context AGENTS.md/META.md/doc/architecture.md.
- AC2/AC2a: `review-task-design.edn` has the architecture pair as the first two
  `:steps` (pass-status-routing on `architecture-review` reading its own
  `:final-llm-reply`, `:on {"REPEAT" → architecture-follow-up, "DONE" →
  ambiguity-review}`; `architecture-follow-up` reuses shared
  `review-follow-up-design.md` with constant-routing DONE → ambiguity-review). The
  `architecture-review.md` prompt loads the skill and ends with the two-line
  PASS_STATUS menu, matching the ambiguity/inconsistency prompts.
- AC3: positional start = first `:steps` element (architecture-review); existing
  ambiguity → inconsistency → clarity-status → final-summary fall-through
  unchanged; final-summary `:contributions` sources the architecture-review yield
  and prose mentions the architectural-fit pass; inline `.edn` template and
  standalone `review-task-design-final-summary.md` both updated and consistent.
- AC5: `workflow-definitions-test` recognises the steps; re-ran the suite
  (`clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`) →
  9 tests, 148 assertions, 0 failures. `review-task-prompt-artifact-targets-test`
  green (orphan-ref + design-steps.md target guards).
- AC6: `doc/workflows.md` describes the architectural-fit aspect.

Architectural fit: confirmed config/prompt-only; no runtime/dispatch/resolver/
mutation paths; addition-not-modification of the existing review-aspect shape;
shared follow-up profile reused (`follow`, not `introduce`). No new patterns,
unnecessary abstractions, or structural-performance concerns. The R1 actionable
item is correctly resolved and closed. No new actionable feedback.

PASS_STATUS for this review pass: REVIEW_COMPLETE.

## 2026-06-01 — test review (task-test-review)

Reviewed the task's tests against design behaviour (AC1–AC6) and the
task-test-review skill criteria: well-formed, behaviour-coverage, infra-dep
injectability. The deliverables are config/prompt/skill files; the tests are the
`workflow_definitions_test.clj` additions (`review-task-design-test`,
`review-task-prompt-artifact-targets-test`, `review-workflow-set-loads-together-test`).

Verified covered: AC2 (architecture-review/follow-up judges + `:on` wiring,
lines 149–156), AC2 step order/types and 8-step count, AC3 final-summary
architecture-review `:yield :text` contribution source (line 137), follow-up
shared design-profile body + predate guard, AC5 load-without-error + md-refs.
Infra-dep criterion satisfied: tests are pure EDN/markdown loading via
`load-edn-with-md-refs` / `with-workflow-dir` (in-memory temp dirs) — no
mocks/stubs, no infrastructure deps. Suite green: 9 tests, 148 assertions, 0
failures.

New actionable test-coverage gap (see steps.md TR1):

- TR1: AC2a mandates the new `review-task-design-architecture-review.md` prompt
  (a) load the `review-task-architecture` skill (not `task-design`) and (b) end
  with the **two-line** PASS_STATUS menu — and the design's own I1 review flagged
  this exact menu convention as a contradiction-prone fragility. Yet **no test
  guards either property**. A future edit could regress the menu to a single
  `A | B` line or swap the loaded skill and every test would still pass. The test
  infra to lock this in already exists and is used for the parallel
  `design-steps.md` ownership guard (`review-task-prompt-artifact-targets-test`:
  `slurp-workflow-file` + `.contains content`). Adding a content guard for the
  architecture-review prompt follows that established pattern (no new mechanism).
  The ambiguity/inconsistency prompts share this absent guard, but AC2a makes the
  menu + skill-loading an explicit contract for the *new* prompt, so the new
  behaviour warrants a regression guard.

Considered but not recorded (weaker test-review fit, avoid noise): AC1 skill
minimality is prose-shaped/subjective (poor substring-test fit; already churned by
R1) and AC3 final-summary prose / AC6 `doc/workflows.md` are conventionally
verified by docs review rather than unit tests.

PASS_STATUS for this review pass: ACTIONABLE_FEEDBACK.

## 2026-06-01 — TR1 follow-up executed

Added the AC2a regression guard as a new `testing` block inside
`review-task-prompt-artifact-targets-test` (workflow_definitions_test.clj),
mirroring the existing `design-steps.md` ownership guard (slurp-workflow-file +
`.contains`). The guard asserts that `review-task-design-architecture-review.md`
(a) references `review-task-architecture` (the loaded skill, not `task-design`)
and (b) ends with the two-line PASS_STATUS menu — the lead-in
("End your final response with exactly one of:") plus both
`PASS_STATUS: ACTIONABLE_FEEDBACK` and `PASS_STATUS: REVIEW_COMPLETE` lines.
This locks in the I1-flagged contradiction-prone menu convention.

Suite green: 9 tests, 152 assertions, 0 failures (+4 over prior 148).
clj-kondo on the changed test file: 0 errors, 0 warnings. No new mechanism.

## 2026-06-01 — test review (task-test-review, pass 2)

Fresh test-review pass over `workflow_definitions_test.clj` after the TR1 guard
landed. Re-ran the suite: `clojure -M:test --focus
psi.workflow-loader.workflow-definitions-test` → 9 tests, 152 assertions, 0
failures.

Re-verified against `λ review_tests`:
- well_formed ✓ — pure EDN/markdown loading.
- infra-dep injectability ✓ — `with-redefs` only redirects workflow-dir
  resolvers to real temp dirs; real loader exercised; no mocks/stubs.
- behaviour-coverage ✓ — AC2/AC3 wiring (judges, `:on`, 8-step order/types,
  final-summary architecture-review `:yield :text` contribution); AC2a prompt
  contract (skill load + two-line PASS_STATUS menu, TR1); AC5 loads; new
  `architecture-follow-up` shared-profile reuse covered transitively by the
  design-profile body-share + predate-guard + orphan-ref (T3) assertions, which
  iterate over `architecture-follow-up` — a swap to a non-shared follow-up prompt
  would fail those.

No new actionable test gap. The only prior gap (TR1) is recorded and resolved;
not duplicated here. Uncovered residue is prose-shaped and intentionally out of
unit-test scope: AC1 skill-body minimality (subjective prose, churned by R1),
AC3 final-summary prose, AC6 `doc/workflows.md` — docs-review territory, not
substring assertions. The "Do not review plan.md or steps.md" line in the
architecture-review prompt is shared convention across all three design-review
prompts (not new behaviour), so it warrants no new per-aspect guard.

PASS_STATUS for this review pass: REVIEW_COMPLETE.

## 2026-06-01 — test-shaper review

Applied test-shaper lens to the tests this task added/touched in
`workflow_definitions_test.clj` (`review-task-design-test`,
`review-task-prompt-artifact-targets-test`, `review-workflow-set-loads-together-test`).
Suite green (9 tests, 152 assertions, 0 failures) — these are quality/shape
findings, not failures.

Strengths (no action): deterministic, real-loader, no mocks/stubs (in-memory
temp dirs) — strong infra-dep injectability; behaviour-focused on the loaded
definition shape; follow-up profile guards pair positive + negative
discriminators with explanatory rationale (TS1/TS3, T1/T2).

New actionable shaping findings (see steps.md SH1–SH2):

- SH1 (meaningful_failures / behavior_focused): the TR1 AC2a guard asserts the
  two-line PASS_STATUS menu lines are *present* via `.contains`, but AC2a's
  contract is that the prompt **ends with** the menu. A regression that appends
  prose *after* the menu, or splits the lead-in from the status lines, passes the
  guard yet violates AC2a. The guard under-specifies the contract it claims to
  lock in. Strengthen to assert the menu is the trailing block (e.g. assert the
  three lines appear as a contiguous, terminal sequence after trimming trailing
  whitespace), so the failure actually maps to the AC2a "ends with" contract.

- SH2 (single_concern / meaningful_failures): the AC2a menu+skill guard lives
  inside `review-task-prompt-artifact-targets-test`, whose docstring and sibling
  `testing` blocks scope it to *artifact ownership* (design-steps.md vs
  steps.md). The AC2a prompt-contract is a distinct concern; co-locating it
  dilutes the test's single concern and makes a menu-regression failure surface
  under a misleading "artifact targets" test name. Move the AC2a guard to its own
  `deftest` (or a clearly-named block) so the failing-test identity describes the
  violated contract.

Considered but not recorded as actionable (weaker shaping fit / avoid noise):
- The 8-element whole-vector name/type equality in `review-task-design-test`
  couples the architecture test to unrelated future ambiguity/inconsistency
  ordering changes (economical/robust churn). Pre-existing convention the task
  merely extended; the architecture pair's own wiring is already asserted
  separately via `step-by-name`. Left as-is to preserve cross-workflow
  consistency with `review-task-plan-test`.
- `review-workflow-set-loads-together-test` re-lists the architecture md-refs,
  duplicating the list in `review-task-design-test` (minor sync risk). Pre-existing
  shape; not introduced by this task.

PASS_STATUS for this review pass: ACTIONABLE_FEEDBACK.

## 2026-06-01 — test-shaper follow-up (SH1, SH2 resolved)

- SH2: Relocated the AC2a menu+skill guard out of
  `review-task-prompt-artifact-targets-test` (artifact-ownership scope) into a
  new dedicated `architecture-review-prompt-contract-test`, so a menu/skill
  regression now fails under a name that names the violated AC2a contract.
- SH1: Strengthened the menu guard from mere `.contains` presence to an
  *ends-with* contract: a single `(?s)`-flagged regex requires the lead-in
  ("End your final response with exactly one of:") plus both `PASS_STATUS:`
  lines to form a contiguous, terminal block anchored to `\z` (trailing
  whitespace tolerated via `\s*`). Verified by construction that it passes the
  real prompt but fails (a) prose appended after the menu and (b) a blank line
  splitting the lead-in from the status lines.
- Skill-load assertion (`review-task-architecture`, not `task-design`) carried
  over into the new deftest unchanged.
- Workflow-loader suite green: 10 tests, 150 assertions, 0 failures
  (was 9 tests — the new deftest adds one). clj-kondo clean on the test file.

## 2026-06-01 — test-shaper review (pass 2, post-SH1/SH2)

Fresh test-shaper pass over the task's tests in `workflow_definitions_test.clj`
after SH1/SH2 landed. Re-ran the workflow-loader suite: 10 tests, 150
assertions, 0 failures.

Verified the prior findings are correctly resolved:
- SH2: the AC2a contract is now its own `architecture-review-prompt-contract-test`
  — a menu/skill regression fails under a name that describes the violated
  contract, not "artifact targets". single_concern satisfied.
- SH1: the menu guard is an *ends-with* contract. Confirmed by construction
  (REPL): the `(?s)…\z` regex matches the real prompt but rejects both
  prose-appended-after-menu and lead-in-split-from-status-lines regressions.
  meaningful_failures satisfied — failure now maps to the AC2a "ends with"
  contract.

Lens re-check: deterministic, real-loader, no mocks (in-memory temp dirs),
behaviour-focused on the loaded definition; follow-up profile guards pair
positive + negative discriminators with rationale; the skill-load assertion
discriminates `review-task-architecture` from `task-design`.

No new actionable test-shaping issue. Items previously considered-and-declined
(8-element whole-vector name/type equality coupling; md-refs duplication between
`review-task-design-test` and `review-workflow-set-loads-together-test`) remain
pre-existing cross-workflow conventions the task merely extended, not new
behaviour — not re-recorded to avoid duplication. The new contract-test's
hardcoded `\n` separators are correct against the repo-controlled file (minor,
non-actionable).

PASS_STATUS for this review pass: REVIEW_COMPLETE.

## 2026-06-01 — docs review (review-task-docs)

Reviewed the task's user-facing documentation (`README.md`, `doc/`, `CHANGELOG.md`)
against the implementation per the review-task-docs checklist.

Verified accurate/consistent (no action):
- `doc/workflows.md` "Architectural-fit design review" section: step order
  (architecture → ambiguity → inconsistency → clarity-status → final-summary),
  positional start step, `review-task-architecture` skill load, shared
  `design`-profile follow-up reuse, and final-summary architectural-fit reporting
  all match `review-task-design.edn` and the prompt files exactly. File paths,
  step names, and routing are correct.
- `doc/workflows.md` enumerates the principles the aspect judges against. This is
  intentional per the R1 follow-up note: AC1/decision-4 constrain the *skill body*
  (where the enumeration was removed), while the doc is user-facing description of
  the aspect — not a duplicated checklist in the skill. Consistent.
- The released `CHANGELOG.md [0.1.2166]` line describing `review-task-design` as
  reviewing "only for ambiguities and inconsistencies" is a historical record of
  what shipped in that version; per keep-a-changelog convention released entries
  are not rewritten. The new behaviour belongs in `[Unreleased]` (see D1), not in
  an edit to the released line. No action on the released line.

New actionable docs gaps (see steps.md D1–D2):

- D1: `CHANGELOG.md [Unreleased]` has no entry for this task's user-visible change.
  The new architectural-fit review aspect is a user-visible behaviour /
  extension-capability change to the `/delegate review-task-design` workflow
  (a third review aspect that now runs first). Per the AGENTS.md changelog policy
  (`user_visible(δ) ∈ {behaviours ∨ extension_capability}`), this warrants an
  `[Unreleased]` entry (Added or Changed) and was omitted.
- D2: `review-task-design.edn` `:description` still reads "Repeatedly review a
  Munera task design for ambiguities and inconsistencies …" — stale/inconsistent
  with the implemented three-aspect behaviour (architectural fit now runs first).
  This description is user-facing: it is surfaced verbatim in the `delegate`
  workflow capability listing. AC6 / review-task-docs checklist items 1 & 5
  (new behaviour reflected; documentation language matches implementation).

PASS_STATUS for this review pass: ACTIONABLE_FEEDBACK.

## Docs review follow-up execution (2026-06-01)

- D1 done: Added a `CHANGELOG.md [Unreleased] ### Added` entry for the new
  architectural-fit `review-task-design` review aspect (runs first, before
  ambiguity/inconsistency; invokable via `/delegate review-task-design`). Left
  the released `[0.1.2166]` entry and the existing `[Unreleased] ### Changed`
  shared-profile entry untouched.
- D2 done: Updated `review-task-design.edn` `:description` to read "Repeatedly
  review a Munera task design for architectural fit, ambiguities, and
  inconsistencies, …" so the verbatim `delegate` capability listing matches the
  implemented three-aspect behaviour. Re-ran the workflow-loader suite green
  (10 tests, 150 assertions, 0 failures).

## 2026-06-01 — docs review (review-task-docs, pass 2, post-D1/D2)

Fresh `review-task-docs` pass over user-facing docs (`README.md`, `doc/`,
`CHANGELOG.md`) after the D1/D2 follow-ups landed. Verified accurate/consistent
against the implementation; no new actionable feedback.

- `CHANGELOG.md [Unreleased] ### Added`: present and accurate — names the
  architectural-fit aspect, that it runs first, consults in-context architecture
  sources, loops on actionable feedback, and is invokable via
  `/delegate review-task-design` (D1 resolved).
- `review-task-design.edn` `:description`: now reads "architectural fit,
  ambiguities, and inconsistencies" — matches the verbatim `delegate` capability
  listing and the implemented three-aspect behaviour (D2 resolved).
- `doc/workflows.md` "Architectural-fit design review": step order, positional
  start (`architecture-review` = first `:steps` element), `review-task-architecture`
  skill load, shared `design`-profile follow-up reuse, `design-steps.md` items
  target, loop advance `architecture → ambiguity → inconsistency → clarity-status
  → final-summary`, and final-summary architectural-fit reporting all match
  `review-task-design.edn` + the prompt files exactly (verified line-by-line).
- `doc/workflows.md` shared-follow-up table: `architecture-follow-up` listed under
  the `design` profile with `design-steps.md` items and `plan.md`/`steps.md`
  forbidden — matches `review-follow-up-design.md`'s prompt body.
- The principle enumeration in `doc/workflows.md` (one-way / boundaries / VSM /
  isolation / effects-as-data / no-silent-shims) is user-facing aspect
  description, not a duplicated checklist in the skill body (AC1/decision-4
  constrain the skill body, where it was removed per R1) — intentional and
  consistent; re-confirmed, not a new gap.
- No `README.md` mention of `review-task-design` exists and none is required —
  workflows are documented under `doc/workflows.md`. No stale references found in
  `README.md` or `doc/`.

No new docs gaps; the only prior gaps (D1, D2) are recorded and resolved, not
duplicated here.

PASS_STATUS for this review pass: REVIEW_COMPLETE.

## 2026-06-01 — code-shaper review

Applied the code-shaper lens (simple ∧ consistent ∧ robust) to the task's
deliverables: `review-task-architecture/SKILL.md`, the
`review-task-design-architecture-review.md` prompt, the `review-task-design.edn`
architecture pair, and the `workflow_definitions_test.clj` additions.

Strong shape, no action:
- **simple**: SKILL.md is single-responsibility (frames the lens, no checklist);
  the architecture-review prompt reuses the established 5-step body verbatim.
- **consistent**: the architecture-review prompt matches
  `review-task-design-ambiguity-review.md` frontmatter/body/two-line PASS_STATUS
  menu modulo the aspect noun; the EDN architecture pair mirrors the
  ambiguity/inconsistency pairs (`follow`, not `introduce`).
- **robust/orthogonal**: config/prompt-only; the new step is an addition over the
  existing review-aspect shape; tests pin judges, `:on` wiring, 8-step order, the
  final-summary contribution, and the AC2a *ends-with* menu contract.

New actionable shaping finding (see steps.md CS1):

- CS1 (consistent — idioms/naming): `review-task-architecture/SKILL.md`'s **body**
  `λtask.` line reads `review(design_architectural_fit) ∧ judge(fit, ¬correctness
  ∧ ¬clarity)` while the **frontmatter** `lambda` reads the same plus
  `∧ consult(in_context_architecture_sources)`. The R1 follow-up updated the
  frontmatter lambda to add the `consult(...)` conjunct but left the body lambda
  line stale, so the two copies of the file's defining lambda now diverge by one
  conjunct. Sibling `review-task-docs/SKILL.md` repeats its frontmatter lambda
  **verbatim** in the body; this file looks like that verbatim convention but
  silently drops a conjunct, reading to a maintainer as either a stale copy or a
  spurious frontmatter term. Restore consistency: make the body `λtask.` line
  match the frontmatter lambda verbatim (append `∧
  consult(in_context_architecture_sources)`).

PASS_STATUS for this review pass: ACTIONABLE_FEEDBACK.

## Code-shaper follow-up execution (2026-06-01)

- CS1 executed: appended `∧ consult(in_context_architecture_sources)` to the
  body `λtask.` line of `.psi/skills/review-task-architecture/SKILL.md`. The
  body lambda (line 9) now matches the frontmatter `lambda` (line 4) verbatim,
  restoring the sibling `review-task-docs/SKILL.md` verbatim-repeat convention.
  SKILL.md-only change (no code/test/doc behaviour change). steps.md CS1 checked.

## 2026-06-01 — code-shaper review (pass 2, post-CS1)

Fresh code-shaper pass (simple ∧ consistent ∧ robust) over all deliverables
after CS1 landed: `review-task-architecture/SKILL.md`, the
`review-task-design-architecture-review.md` prompt, the `review-task-design.edn`
architecture pair + `final-summary`, the standalone `final-summary.md`, and the
`workflow_definitions_test.clj` additions.

Verified resolved / strong shape (no action):
- CS1 resolved: SKILL.md body `λtask.` line now matches the frontmatter `lambda`
  verbatim (both carry `∧ consult(in_context_architecture_sources)`), restoring
  the sibling `review-task-docs/SKILL.md` verbatim-repeat convention. consistent.
- **simple**: SKILL.md is single-responsibility (frames the lens, no checklist);
  the architecture-review prompt reuses the established 5-step body.
- **consistent**: architecture-review prompt matches
  `review-task-design-ambiguity-review.md` frontmatter/body/two-line PASS_STATUS
  menu modulo the aspect noun + loaded skill; the EDN architecture pair mirrors
  the ambiguity/inconsistency pairs; the `final-summary` inline `.edn` template
  and standalone `review-task-design-final-summary.md` are byte-for-byte in sync.
- **robust/orthogonal**: config/prompt-only addition over the existing
  review-aspect shape (`follow`, not `introduce`); tests pin judges, `:on`
  wiring, 8-step order/types, the final-summary architecture-review contribution,
  and the AC2a *ends-with* menu contract (SH1/SH2 resolved into a dedicated
  `architecture-review-prompt-contract-test`). Suite green: 10 tests, 150
  assertions, 0 failures.

No new actionable code-shaping issue. CS1 and all prior review findings are
recorded and resolved; not re-recorded here to avoid duplication.

PASS_STATUS for this review pass: REVIEW_COMPLETE.
