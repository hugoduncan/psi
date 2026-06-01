# Implementation notes — 199

## Design review — ambiguity pass (2026-06-01)

Reviewed `design.md` against the workflow grammar/compiler and the existing
follow-up prompts. Actionable ambiguities found (see `design-steps.md`):

- **A1 — sharing mechanism unresolved & recommended option infeasible.** Open
  Decision 1 recommends a shared `:prompt-workflow` `.md` receiving per-step
  constant `:vars`. `compiler/compile-prompt-workflow-step` wires the `.md`
  body's `{{var}}` tokens only against the `.md`'s own frontmatter `vars:`
  (which bind via `source-spec` to `:workflow-input`/`:workflow-original`) plus
  `standard-vars` (`{{input}}`,`{{original}}`). No path lets a referencing
  `.edn` step inject a per-step literal profile. AC#1 ("parameterized only by
  scope profile") has no feasible mechanism as written.
- **A2 — out-of-scope vs parameterization conflict.** "Any workflow-engine /
  pass-status-routing / constant-routing changes" is out of scope, yet
  per-step profile injection appears to require compiler/grammar work. The
  design does not reconcile this.
- **A3 — `steps` profile collapses two different artifact scopes.** The
  `review-step` follow-up currently reads/updates `steps.md, implementation.md,
  design.md, and plan.md`; the design's `steps` profile writable set omits
  `design.md`. Unclear whether unification drops `design.md` access (behaviour
  change for implementation review) or the table is incomplete.
- **A4 — "predate" exclusion clause unspecified.** Plan follow-ups carry "Do
  not execute items from steps.md that predate the preceding review pass";
  design follow-ups and the `review-step` template do not. The unified §Concepts
  contract ("execute newly-added unchecked items") does not state whether this
  clause is preserved, and if dropped for plan, that is an unflagged behaviour
  change.

## Ambiguity follow-up execution (2026-06-01)

Verified the workflow grammar/compiler and host wiring (read
`components/workflow-loader/src/psi/workflow_loader/compiler.clj` —
`compile-prompt-workflow-step`, `markdown-body->contribution`, `standard-vars` —
plus `.psi/workflows/review-task-design.edn`, `review-task-plan.edn`,
`review-step.edn`, and all four follow-up `.md` prompts). All four items resolved
in `design.md`:

- **A1 (done).** Confirmed per-step constant `:vars` is infeasible: the compiler
  wires a `:prompt-workflow` `.md`'s `{{var}}` tokens only against that `.md`'s
  own frontmatter `vars:` (-> `:workflow-input`/`:workflow-original`) + standard
  vars; host-step `:vars` are not merged in. Removed the infeasible
  recommendation; pinned the concrete mechanism = one shared follow-up `.md`
  per profile (two files), referenced via `:prompt-workflow`. Added new
  "Sharing mechanism (resolved)" section; updated Intent, In-scope, ACs #1-#3,
  and Architectural alignment.
- **A2 (done).** Stated explicitly that the chosen mechanism stays within the
  current grammar (existing `:prompt-workflow` + `{{input}}` only) -- no
  engine/compiler/grammar/routing changes -- so the out-of-scope boundary holds.
- **A3 (done).** Profile table now distinguishes writable vs read-only context
  vs forbidden: `steps` profile keeps `design.md` as read-only context (no
  write), matching current plan/`review-step` behaviour (not a behaviour
  change); `design` profile writable set now explicitly includes
  `design-steps.md`. Documented both as non-changes.
- **A4 (done).** Unified contract preserves and generalizes the "do not execute
  items that predate the preceding review pass" guard to both profiles. For
  `steps`/plan it is identical to current behaviour; for `review-step` it is a
  small intentional tightening (flagged in design.md); for `design` it makes
  explicit the already-relied-on behaviour.

No blockers. Design is now unambiguous on sharing mechanism, scope boundary,
artifact-scope semantics, and the predate guard.

## Design review — inconsistency pass (2026-06-01)

Reviewed `design.md` against the four per-aspect follow-up `.md` prompts, the
three host `.edn` workflows, the compiler (`compile-prompt-workflow-step`,
`markdown-body->contribution`, `standard-vars`), and `doc/workflows.md`. The
A1/A2 grammar claims and the A3/A4 scope/predate claims all hold against the
referenced artifacts. One actionable inconsistency found (see `design-steps.md`):

- **I1 — "aspect-agnostic / cosmetic" framing contradicts the named-step
  reference in the actual follow-up prompts.** The design (Problem §obs 1 and
  Architectural alignment) asserts the follow-up is "aspect-agnostic" and the
  design/plan follow-ups "only mention the aspect *cosmetically*." But each
  per-aspect follow-up `.md` names the *specific preceding review step* it acts
  after — `review-task-design-ambiguity-follow-up.md` says "preceding
  **ambiguity-review** pass" and `...-inconsistency-follow-up.md` says
  "preceding **inconsistency-review** pass" (same for the plan pair). Since one
  shared `design`-profile (and one shared `steps`-profile) follow-up file is
  referenced by **both** the ambiguity-follow-up and inconsistency-follow-up
  host steps, the shared file cannot name a specific preceding step and must
  generalize to "the preceding review pass." The design never states this
  generalization, and the named-step reference is functional context (it tells
  the agent which review step's just-added items to execute), not purely
  cosmetic. Resolution needed: state in `design.md` that the shared follow-up
  uses generic "preceding review pass" wording (no named review step), and that
  this is the deliberate generalization of the current per-step named
  references.

## Inconsistency follow-up execution (2026-06-01)

Confirmed the four per-aspect follow-up `.md` prompts against the I1 claim
(`grep` for "preceding"/"review pass"): the design pair names "preceding
ambiguity-review pass" / "preceding inconsistency-review pass", and the plan
pair likewise. The named-step reference is functional (identifies which review
step's just-added items to execute). I1 resolved in `design.md`:

- Rewrote Problem §obs 1: behaviour does not branch on aspect, but the named-step
  reference is **functional, not cosmetic**; the shared file cannot name one step
  and generalizes to "the preceding review pass" — a deliberate generalization.
- Corrected the "cosmetically" claim in Architectural alignment.
- Added new "Aspect generalization (resolved)" section stating the shared
  follow-up uses generic "preceding review pass" wording, why a shared file
  cannot name a specific step (one file referenced by both ambiguity and
  inconsistency host steps), and why generalization is safe (host wires follow-up
  immediately after its review step, so "preceding review pass" is unambiguous at
  runtime). Pinned that both profile files use this wording.

No blockers. I1 done.

Lower-confidence observation (not added as a follow-up step): AC#7 references a
user-facing "review-workflow reference" describing follow-up steps, but
`doc/workflows.md` (and other docs) do not currently document the
`review-task-design`/`review-task-plan`/`review-step` family or its follow-up
steps at all. AC#7 is defensible as "add new doc content," so no inconsistency
follow-up was added.

## Implementation — Slices 1–2 (2026-06-01)

Created `review-follow-up-design.md` and `review-follow-up-steps.md`; rewired
all three hosts; removed the four per-aspect follow-up `.md` files.

**Deviation D1 — `review-step` `:source` contributions dropped.** plan.md
Slice 2 said to convert the inline `review-step` follow-up to
`:prompt-workflow` *preserving its two `:source` contributions*
(`:workflow-original` and `{:step "review", :yield :text}`). The compiler
(`compiler.clj` `prompt-source-conflict?`) **forbids** combining
`:prompt-workflow` with `:contributions`/`:system-prompt` on the same step, and
`compile-prompt-workflow-step` overwrites `:contributions` from the referenced
`.md` body. So the `:source` contributions cannot coexist with
`:prompt-workflow`. Resolution: dropped both `:source` contributions. This is
safe and consistent with the design/plan follow-up hosts, which already use
`:prompt-workflow` with no `:source` contributions: the follow-up's
authoritative item source is `steps.md` (the review step writes added items
there), so the prior-step text yield was supplementary context only. The
`:tools`/`:skills` previously inline on the step now come from the markdown
frontmatter via `merge-markdown-session-config`. Behaviour-preserving for the
contract; minor reduction in prior-review-text context only.

## Slices 3–5 (2026-06-01)

- Tests: updated `workflow_definitions_test.clj` — design/plan tests now
  reference the two shared files; added `step-template-text` helper and
  profile-body assertions (design uses `design-steps.md` and forbids
  plan/steps.md; steps profile uses `steps.md`, never `design-steps.md`);
  `review-step-test` converted to `load-edn-with-md-refs` so the referenced
  follow-up md resolves, with a steps-profile body assertion;
  `review-task-prompt-artifact-targets-test` and
  `review-workflow-set-loads-together-test` filename lists updated. Suite green
  (14 tests, 173 assertions across compiler/core/definitions; the definitions
  ns alone is 9 tests / 111 assertions).
- Docs: added "Shared review follow-up steps" section to `doc/workflows.md`
  (profile table, host mapping, generic "preceding review pass" wording,
  predate guard, routing-unchanged note).
- Changelog: added an Unreleased `Changed` entry flagging the `review-step`
  predate-guard tightening (only the immediately preceding pass's items run).
- All 7 ACs verified against final state (see steps.md Slice 5).

## Implementation review — task-implementation-review pass (2026-06-01)

Reviewed the shared follow-up files, the three rewired hosts, tests, docs, and
changelog against design/plan. Confirmed: two profile files present and correct
(match the design profile table), all three hosts rewired, four per-aspect files
removed with no orphans (grep clean outside git history), doc section + changelog
entry present, lint clean, and the workflow-definitions suite green (9 tests, 111
assertions). D1 (dropped `review-step` `:source` contributions) is design-aligned
— the design/plan follow-up hosts already use `:prompt-workflow` with no `:source`
contributions, so D1 brings `review-step` into consistency; `steps.md` is the
authoritative item source.

One actionable issue found (see steps.md):

- **R1 — `steps`-profile follow-up wording narrows the writable surface for
  implementation-review follow-ups, risking degraded behaviour.** The unified
  `review-follow-up-steps.md` says "Read and update the task's plan.md, steps.md,
  and implementation.md as needed... updating plan.md and steps.md as you work."
  But this same `steps`-profile file is used by `review-step` for
  *implementation* review (skill `task-implementation-review`), where follow-up
  items routinely require editing **actual source code, tests, and docs** — not
  just munera task files. The **old** inline `review-step` follow-up template
  said "updating task artifacts as you work" (broad). The new wording explicitly
  lists only `plan.md`/`steps.md` as the files to update, which can mislead the
  follow-up agent into not editing code/tests/docs when fixing implementation
  follow-up items. Tools still permit it (`edit`/`write` from frontmatter), so
  this is a **prose/guidance regression**, not a hard block — but it is a real
  behaviour-affecting narrowing that AC4 ("behaviour preserved per host... for
  implementation follow-ups") does not actually preserve. The design profile
  table also omits code/test/doc from both profiles' writable sets, so the
  `steps` profile conflates plan-review follow-ups (task-files-only) with
  implementation-review follow-ups (must edit real code). Resolution: the
  `steps`-profile follow-up should explicitly permit editing the code/tests/docs
  the follow-up items reference (e.g. "update the task's code, tests, docs, and
  task artifacts as needed"), and the design/doc profile tables should reflect
  that the `steps` profile, when hosting implementation review, writes the
  referenced source artifacts — restoring the old template's broader scope.

## Implementation-review follow-up execution — R1 (2026-06-01)

Executed both R1 items from the preceding implementation-review pass:

- **R1a (steps-profile wording broadened).** `review-follow-up-steps.md` body
  now explicitly permits editing referenced source: added "When a follow-up item
  requires it, also update the code, tests, and docs the item references" and
  changed the working-scope clause to "updating the task's code, tests, docs, and
  task artifacts as you work". Restores the prior inline `review-step` template's
  broad scope; the design-profile (`review-follow-up-design.md`) is unchanged —
  design follow-ups never touch real source. The body still contains "steps.md"
  and never "design-steps.md", so the steps-profile assertions still hold.
- **R1b (writable set documented).** design.md `steps`-profile writable column now
  reads "...plus the referenced code/tests/docs (implementation follow-ups)"; added
  an explicit "`steps` profile writes referenced code/tests/docs (resolves R1)"
  note; broadened AC4 to state implementation follow-ups may write the referenced
  code/tests/docs. doc/workflows.md profile table writable column updated likewise
  with an accompanying note distinguishing plan-review (no code items) from
  implementation review (must edit real code).

Verification: workflow-definitions suite green (9 tests, 111 assertions, 0
failures); only `.md` files changed (no Clojure to lint). No blockers.

## Implementation-review pass — independent re-review (2026-06-01)

Independent task-implementation-review against design/plan. Verified end-to-end:
two profile follow-up files (`review-follow-up-design.md`,
`review-follow-up-steps.md`) match the design profile table (items file,
writable, forbidden/read-only) and use generic "preceding review pass" +
predate-guard wording; all three hosts rewired (`review-task-design` →
design-profile both follow-ups; `review-task-plan` + `review-step` →
steps-profile); `review-task-implementation` inherits via `:delegate
review-step` (skill `task-implementation-review`); host routing/looping unchanged
(design/plan forward-advance; `review-step` `REPEAT→review` `:max-iterations 6`);
four per-aspect files removed, no orphans (grep clean outside git). Tests green
(workflow-definitions: 9 tests, 111 assertions, 0 failures); test ns lints clean.
Doc "Shared review follow-up steps" section and Unreleased CHANGELOG entry present
and accurate. All 7 ACs satisfied. Prior feedback (A1–A4, I1, R1, D1) resolved.

No new actionable issues. Only a trivial, non-actionable prose redundancy in
`review-follow-up-design.md` ("Do not execute items from steps.md" is subsumed by
the following "Do not touch plan.md or steps.md") — cosmetic, behaviour-neutral,
no follow-up step added.

PASS_STATUS: REVIEW_COMPLETE

## Test review — task-test-review pass (2026-06-01)

Applied task-test-review (well-formed ∧ behaviour-coverage ∧ infra-deps
nullable/¬mock) to `workflow_definitions_test.clj`. Reran the suite green (9
tests, 111 assertions, 0 failures). Infra-deps criterion satisfied: the
`with-workflow-dir` fixture uses the real loader against a real temp filesystem
via `with-redefs` of `global-workflow-dirs`/`project-workflow-dir` — no
mocks/stubs. Most ACs are well covered (AC2 host→profile refs; AC5 routing +
load-together; AC6 all three hosts). Found three behaviour-coverage gaps where
the task's *flagged behaviour-defining* clauses are not asserted, so a future
edit could silently regress them with tests still green:

- **T1 — predate-exclusion guard is unasserted.** The design Concepts section
  flags the predate guard as an *intentional behaviour change* for `review-step`
  ("execute only items the immediately preceding pass added"). Both profile
  bodies carry "Do not execute items ... that predate the preceding review
  pass", but no test asserts this string in any follow-up step body. The guard
  is the central behaviour-preservation point of the task; it should be locked
  in. Actionable.
- **T2 — R1 steps-profile code/tests/docs broadening is unasserted.** R1
  (AC4 "behaviour preserved for implementation follow-ups") deliberately
  broadened `review-follow-up-steps.md` to permit editing referenced
  code/tests/docs. No test asserts the "code, tests, and docs" clause; a regress
  to the narrow plan/steps-only wording (the exact bug R1 fixed) would pass.
  Actionable.
- **T3 — AC3 (no orphan references to removed per-aspect files) is unasserted.**
  AC3 requires the four removed `*-follow-up.md` files leave no orphans; this was
  only verified by a manual one-time grep in steps.md, with no regression guard.
  `review-task-prompt-artifact-targets-test` already enumerates the live review
  prompt filenames, so a sibling assertion that the four removed filenames are
  absent from the host `.edn`s (or unreferenced repo-wide outside git/munera) is
  cheap and closes the gap. Actionable.

Lower-confidence, non-actionable note (no step added): the positive substring
assertion `(.contains text "steps.md")` in the plan/`review-step` tests is
satisfied even by `"design-steps.md"`; correctness relies on the paired negative
`(not (.contains text "design-steps.md"))`. Functionally correct as a pair —
flagged for awareness only.

## Test review follow-up execution — T1/T2/T3 (2026-06-01)

Executed the three test-review follow-up items in
`workflow_definitions_test.clj`:

- **T1 (predate guard).** Added an assertion that each host's follow-up step
  body contains "predate the preceding review pass" — in
  `review-task-design-test` (both follow-ups), `review-task-plan-test` (both
  follow-ups), and `review-step-test` (the single follow-up). Locks in the
  design Concepts predate-guard behaviour change.
- **T2 (R1 broadening).** Added a "code, tests, and docs" substring assertion to
  the `steps`-profile follow-up bodies in `review-task-plan-test` and
  `review-step-test`, guarding the R1 implementation-follow-up scope from
  regressing to plan/steps-only wording.
- **T3 (AC3 orphan guard).** Extended `review-task-prompt-artifact-targets-test`
  with a nested `doseq` asserting none of the four removed per-aspect
  follow-up filenames appear in the three rewired host `.edn`s
  (`review-task-design.edn`, `review-task-plan.edn`, `review-step.edn`).

Verification: `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`
green (9 tests, 131 assertions, 0 failures — up from 111); `clj-kondo` on the
test ns clean. Only the test file changed. No blockers.

## Test review — task-test-review second pass (2026-06-01)

Independent re-application of task-test-review to
`workflow_definitions_test.clj`. Suite green (9 tests, 131 assertions, 0
failures). Infra-deps criterion holds: real loader + real temp filesystem via
`with-redefs` of dir resolvers, no mocks/stubs. Verified the prior T1/T2/T3
gaps are now closed (predate guard, R1 code/tests/docs broadening, AC3 orphan
guard asserted across all three hosts). Behaviour coverage re-mapped against
ACs: AC2 host→profile refs, AC4 forbidden/code-tests-docs scopes, AC5
routing/looping (`:judge`/`:on`, `:max-iterations 6`) + load-together, AC6 all
three hosts — all asserted.

No new actionable issues. Two non-actionable observations (no steps added):
- The `steps`-profile "design.md as read-only context" clause (A3 resolution) is
  unasserted, but a string-presence assertion would be brittle and would not
  actually prevent design.md writes; the materially important design-profile
  forbidden side (plan.md/steps.md) is covered. Net negative signal — skipped.
- Design-profile writable set (design.md/implementation.md) is unasserted, but
  carries no behaviour-change flag and writes only task files. Low value.

PASS_STATUS: REVIEW_COMPLETE

## Test review — test-shaper pass (2026-06-01)

Applied test-shaper (clarity ∧ signal ∧ robustness ∧ economical) to
`workflow_definitions_test.clj`. Suite green (9 tests, 131 assertions, 0
failures). New profile-body assertions (T1/T2/T3) are well-targeted and
behaviour-focused; the prose-substring coupling is acceptable because the
follow-up's prose *is* its contract. One actionable signal gap:

- **TS1 — `(.contains text "steps.md")` is a dead positive assertion.** In
  `review-task-plan-test` (line ~237) and `review-step-test` (line ~380) the
  steps-profile positive assertion `(.contains text "steps.md")` can never fail
  independently: `"design-steps.md"` *contains* `"steps.md"` as a substring, so
  the positive passes for both profiles. All real discriminating signal lives in
  the paired negative `(not (.contains text "design-steps.md"))`. The positive
  therefore violates `meaningful_failures` (a failure would not explain a
  contract violation the negative does not already catch) and is redundant
  (`economical`). Prior pass logged this as "awareness only / net negative
  signal — skipped"; under test-shaper it is actionable: the positive should
  either be removed as redundant or strengthened to a steps-profile-unique
  anchor that cannot be satisfied by the design profile (e.g. assert the
  steps-profile-only "read-only context" / plan.md+steps.md wording, or assert
  `steps.md` appears as a standalone item-file reference distinct from
  `design-steps.md`). Actionable.

Non-actionable observations (no steps added):
- **Wording coupling.** T1/T2 anchor on exact phrases ("predate the preceding
  review pass", "code, tests, and docs"); a benign rewording would break the
  test with no contract change. Accepted: the prose is the contract, and these
  phrases are the design-flagged behaviour clauses, so coupling is intentional.
- **Cross-deftest duplication.** `review-task-design-test` and
  `review-task-plan-test` duplicate ~80 lines of identical routing/judge/wiring
  assertions (incidental variation per `economical`). Predates this task's
  changes; out of this follow-up's scope.

## Test-shaper follow-up execution — TS1 (2026-06-01)

Executed the single test-shaper follow-up item in
`workflow_definitions_test.clj`:

- **TS1 (dead positive assertion).** Replaced `(.contains text "steps.md")` —
  which `"design-steps.md"` trivially satisfies, carrying no independent signal
  — in both the `review-task-plan-test` steps-profile body block and the
  `review-step-test` follow-up body block with the steps-profile-unique anchor
  `(.contains text "design.md as read-only context")`. The design profile
  (`review-follow-up-design.md`) writes design.md and never uses the
  "read-only context" clause (verified: grep finds it only in
  `review-follow-up-steps.md`), so the positive now fails iff a non-steps
  profile is wired in — a meaningful profile-contract violation. The paired
  negative `(not (.contains text "design-steps.md"))` is retained as the
  belt-and-braces discriminator.

Verification: focused suite green (9 tests, 131 assertions, 0 failures —
unchanged count, the assertion was strengthened in place, not added);
`clj-kondo` on the test ns clean. Only the test file changed. No blockers.

## Test review — test-shaper second pass (2026-06-01)

Re-applied test-shaper to `workflow_definitions_test.clj`. Suite green (9
tests, 131 assertions). The deftest-body steps-profile positives were already
strengthened (TS1). One new actionable signal gap found in the sibling
`review-task-prompt-artifact-targets-test` — the same dead-positive defect TS1
fixed in the deftest bodies, but left unaddressed here:

- **TS2 — dead positive `(.contains content "steps.md")` in
  `review-task-prompt-artifact-targets-test`.** The plan block asserts
  `(.contains content "steps.md")` over the three plan-family prompts, paired
  with `(not (.contains content "design-steps.md"))`. Because `"design-steps.md"`
  *contains* `"steps.md"` as a substring, the positive cannot fail independently
  of the negative — all discriminating signal lives in the negative
  (`meaningful_failures` / `economical` violation). This is structurally the
  same dead positive TS1 corrected in `review-task-plan-test`/`review-step-test`;
  the fix did not propagate here. Verified all three plan-family files contain a
  standalone `steps.md` occurrence (preceded by a non-`-` character), so the
  positive can be strengthened to a word-boundary / standalone match (e.g.
  `(re-find #"(^|[^-])steps\.md" content)`) that `design-steps.md` cannot
  satisfy — giving the positive genuine independent signal. Actionable.

Non-actionable (no step added):
- The steps-profile-block paired negative `(not "design-steps.md")` in the
  deftest bodies is now belt-and-braces alongside the strong "design.md as
  read-only context" positive, but still directly forbids the wrong items file
  — retained, carries some independent signal.

## Test-shaper follow-up execution — TS2 (2026-06-01)

Executed the single test-shaper second-pass follow-up item in
`workflow_definitions_test.clj`:

- **TS2 (dead positive in sibling test).** Replaced the bare
  `(.contains content "steps.md")` positive in
  `review-task-prompt-artifact-targets-test`'s plan block with
  `(re-find #"(^|[^-])steps\.md" content)`. A bare substring check passes
  trivially on `"design-steps.md"` (which contains `"steps.md"`), so all signal
  previously lived in the paired negative `(not (.contains content
  "design-steps.md"))`. The `[^-]`-anchored regex matches only a standalone,
  non-`design-` `steps.md`, which `design-steps.md` cannot satisfy — giving the
  positive genuine independent signal (the same dead-positive defect TS1 fixed
  in the deftest bodies, now propagated to this sibling test). Verified all
  three plan-family prompts carry a space-preceded `steps.md` occurrence; paired
  negative retained.

Verification: `clojure -M:test --focus
psi.workflow-loader.workflow-definitions-test` green (9 tests, 131 assertions,
0 failures — unchanged count, strengthened in place); `clj-kondo` on the test
ns clean. Only the test file changed. No blockers.

## Test review — test-shaper third pass (2026-06-01)

Re-applied test-shaper (clarity ∧ signal ∧ robustness ∧ economical) to
`workflow_definitions_test.clj`. Suite green (9 tests, 131 assertions). The
steps-family dead positives (TS1/TS2) are fixed. One new actionable signal gap,
distinct from TS1/TS2 — those addressed dead *positives* in the steps-family
tests; this is a missing *negative* discriminator in the design-family test:

- **TS3 — design-profile follow-up body lacks a negative guard against the
  steps-profile-only broadening, so a wrong-profile / over-broad design body
  passes silently.** In `review-task-design-test` the two follow-up bodies are
  asserted only by positives (`design-steps.md`, `Do not touch plan.md or
  steps.md`, predate guard). The R1/A3 design decision is that the **design**
  profile *never* edits real source: `review-follow-up-design.md` deliberately
  omits the steps-profile "code, tests, and docs" broadening and the
  "design.md as read-only context" clause. But no design-test assertion forbids
  those steps-profile-only clauses. Consequently, if the steps-profile body were
  accidentally wired into the design host, or the design body were broadened to
  permit code/test/doc edits (the exact contract R1 split the profiles to keep
  apart), `review-task-design-test` would still pass — `meaningful_failures` and
  `economical` are both violated, and the design profile lacks the symmetric
  negative that the steps profile already carries (`(not (.contains text
  "design-steps.md"))`). Resolution: add a negative assertion to
  `review-task-design-test`'s follow-up body block that the design-profile body
  does **not** contain the steps-profile-only broadening clause `"code, tests,
  and docs"` (and optionally `"design.md as read-only context"`), giving the
  design-profile contract a discriminating negative symmetric with the
  steps-profile tests. Actionable.

Non-actionable (no step added):
- The design test's `Do not touch plan.md or steps.md` positive *is* a genuine
  discriminator (the steps profile cannot satisfy it, since it updates
  plan.md/steps.md), so the design block is not wholly signal-free — TS3 closes
  only the broadening-direction gap, not a fully dead assertion.

## Test-shaper follow-up execution — TS3 (2026-06-01)

Completed TS3: added the symmetric negative discriminator to
`review-task-design-test`'s design-profile follow-up body `doseq`
(`ambiguity-follow-up` / `inconsistency-follow-up`):

- `(not (.contains text "code, tests, and docs"))` — design profile never
  carries the steps-profile-only code/test/doc broadening (R1/A3).
- `(not (.contains text "design.md as read-only context"))` — design profile
  *writes* design.md and never treats it as read-only context.

Verified the design profile body (`review-follow-up-design.md`) contains neither
clause (grep count 0) while the steps profile body (`review-follow-up-steps.md`)
carries them, so the negatives now discriminate a wrong-profile wiring or an
over-broad design body. This makes the design-profile contract negative
symmetric with the steps-profile tests' `(not (.contains text
"design-steps.md"))` guard.

Verification: focused `psi.workflow-loader.workflow-definitions-test` green
(9 tests, 135 assertions, 0 failures — +4 from TS2's 131); `clj-kondo` on the
test ns clean. Only the test file changed; production prompts/EDN unchanged.

## Test review — test-shaper fourth pass (2026-06-01)

Independent re-application of test-shaper (clarity ∧ signal ∧ robustness ∧
economical) to `workflow_definitions_test.clj`. Suite green (9 tests, 135
assertions, 0 failures).

Verified the task-199-authored assertions now carry genuine, symmetric signal
(TS1/TS2/TS3 all closed). Confirmed by grep that each profile-discriminator
anchor is present in exactly one profile body and absent in the other:
`design-steps.md`, `Do not touch plan.md or steps.md` → design-only;
`design.md as read-only context`, `code, tests, and docs` → steps-only. The
`predate the preceding review pass` positive is present in both profiles —
correctly a behaviour-removal lock (T1), not a profile discriminator; its
failure means the guard text was deleted (meaningful). The artifact-targets
`(re-find #"(^|[^-])steps\.md" content)` correctly avoids the `design-steps.md`
substring trap (TS2). Infra-deps satisfied: real loader + real temp filesystem
via `with-redefs` of dir resolvers, no mocks.

No new actionable issues.

Two `economical`/`simple` candidate defects considered and rejected as
out-of-scope (both predate task 199, introduced in be16dd244 tasks 188/189;
199 only updated filename lists within them — confirmed via `git log -S`):
- Cross-deftest duplication: `review-task-design-test` and `review-task-plan-test`
  duplicate ~80 lines of identical routing/judge/wiring assertions (incidental
  variation). Pre-existing; flagged for awareness only by the prior pass too.
- Dead nested setup in `review-workflow-set-loads-together-test`: the two outer
  `load-edn-with-md-refs` calls discard their results `(fn [_] ...)` and the
  innermost `with-workflow-dir` re-slurps and re-loads every file itself, so the
  outer two calls are pure dead nesting (violates `minimal_incidental_setup`).
  Pre-existing structure; only its filename lists were touched by 199.

PASS_STATUS: REVIEW_COMPLETE

## Docs review — review-task-docs pass (2026-06-01)

Applied review-task-docs (README ∧ doc/ ∧ CHANGELOG; accuracy ∧ completeness ∧
consistency) to the user-facing docs touched by this task.

Verified:
- **doc/workflows.md "Shared review follow-up steps"** — present and accurate.
  Profile table (files, items file, writable, forbidden/read-only) matches the
  two shipped prompts: `review-follow-up-design.md` (design profile;
  design-steps.md; forbids plan.md/steps.md) and `review-follow-up-steps.md`
  (steps profile; steps.md; design.md read-only context; permits referenced
  code/tests/docs). Host mapping matches the `.edn` wiring (design host → both
  follow-ups design-profile; plan + review-step → steps-profile;
  review-task-implementation inherits via review-step). Routing/looping prose
  ("REPEAT → review", `:max-iterations`) matches `review-step.edn`
  (`:max-iterations 6`, `REPEAT {:goto "review"}`).
- **Removed behaviours** — grep across `doc/`, `README.md`, `CHANGELOG.md` finds
  zero references to the four removed per-aspect follow-up `.md` files; no stale
  doc references (AC3 doc side clean).
- **CHANGELOG.md** — Unreleased `Changed` entry present and accurate; correctly
  flags the only genuine user-visible behaviour change (review-step predate-guard
  tightening). The R1 steps-profile code/tests/docs broadening restores the prior
  inline review-step template's scope (behaviour-preserving vs the old template),
  so it correctly warrants no separate changelog entry.
- **README.md** — does not document individual workflows; the workflow reference
  lives in `doc/workflows.md`, so no README change is required.

No actionable doc issues. AC7 (user-facing workflow docs describe the shared
per-profile follow-ups) satisfied.

PASS_STATUS: REVIEW_COMPLETE

## Code-shaper review pass (2026-06-01)

Applied code-shaper (simplicity ∧ consistency ∧ robustness) to the shipped
artifacts — the two profile prompt `.md` files, the three rewired host `.edn`s,
and `workflow_definitions_test.clj`. The two profile files are the whole point
of the task (DRY/`one_way`); they should read as structurally parallel
realizations of one contract. They mostly do, but one consistency defect
survives between them:

- **CS1 — the two profile prompts' "read and update" clauses are asymmetric
  about the items file.** `review-follow-up-steps.md` lists its items file in
  the writable clause ("Read and update the task's **plan.md, steps.md**, and
  implementation.md as needed"), but `review-follow-up-design.md` **omits** its
  items file from the parallel clause ("Read and update the task's **design.md**
  and implementation.md as needed" — no `design-steps.md`). Both files must in
  fact write their items file (to tick completed items, covered separately by
  the "mark it done in {items}.md" sentence), so this is behaviour-correct — but
  the asymmetry violates `consistent(idioms)`/`consistent(data_shapes)` across
  the two shared-contract files: a reader comparing them cannot tell whether
  design's omission is deliberate or a drift, which is exactly the
  copy-divergence this task exists to eliminate. Cheap fix: add `design-steps.md`
  to the design file's "read and update" clause so the two files are
  structurally parallel ("Read and update the task's design.md, design-steps.md,
  and implementation.md as needed"), making the shared contract self-evidently
  consistent. Actionable.

Considered and **not** re-raised (already noted, behaviour-neutral): the
design file's "Do not execute items from steps.md" prohibition is subsumed by
the following "Do not touch plan.md or steps.md" (flagged cosmetic in the
test-shaper fourth pass; not re-flagged here). No simplicity defect in the
EDN hosts (routing is host-local and intentionally preserved) or the test file
(signal already shaped across four test-shaper passes).

PASS_STATUS: ACTIONABLE_FEEDBACK

## CS1 follow-up executed (2026-06-01)

Applied the code-shaper CS1 fix: edited `.psi/workflows/review-follow-up-design.md`
writable clause to "Read and update the task's design.md, design-steps.md, and
implementation.md as needed", making it structurally parallel to
`review-follow-up-steps.md` (which already lists its items file `steps.md`).
Behaviour-neutral — the design body already wrote `design-steps.md` (the
"mark it done in design-steps.md" sentence); this just removes the
copy-divergence asymmetry. No test change needed: design-profile body
assertions (positive `design-steps.md`; negatives `code, tests, and docs`,
`design.md as read-only context`) all still hold. Focused workflow-definitions
suite green (9 tests, 135 assertions, 0 failures). Only the design prompt
file changed.

## Code-shaper review — second pass (2026-06-01)

Independent re-application of code-shaper (simplicity ∧ consistency ∧
robustness) to the shipped artifacts (two profile prompts, three rewired host
`.edn`s, `workflow_definitions_test.clj`) after the CS1 fix.

Verified post-CS1 the two profile prompts read as structurally parallel
realizations of one contract: identify items → read/update writable set →
complete-while-updating → mark-done-in-items-file → record blockers in
implementation.md → predate guard → commit. The only divergence is the
irreducible profile variation (items file; writable/forbidden set; the
steps-profile code/tests/docs breadth) — exactly the variation the task names
as legitimate. `consistent(idioms)`/`consistent(data_shapes)` now hold across
the pair. Robustness is sound: profile is encoded by *which* file a host
references (an invalid items-file/artifact-set combination is unrepresentable),
not by a runtime-resolved value. Host routing/looping is host-local and
intentionally preserved; the test file's signal was shaped across four prior
test-shaper passes.

Re-examined the design body's two `steps.md` prohibitions and confirmed they
are **not** redundant: "Do not execute items from steps.md" forbids the wrong
item *source*, while "Do not touch plan.md or steps.md" forbids the wrong write
*target* — distinct contracts, both behaviour-correct. (Prior passes' "cosmetic
subsumption" note referred to the predate guard, a separate sentence.) No
edit warranted.

No new actionable code-shaper defects.

PASS_STATUS: REVIEW_COMPLETE
