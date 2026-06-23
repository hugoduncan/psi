# Implementation notes

## Review notes
- architectural review: no architectural review feedback (design fits the existing prompt-contribution presentation pattern; advertise is orthogonal to registration/discovery/invocation; advertise-vs-disable-model-invocation overlap is already captured in design.md Open Question 1, so not a new finding)
- ambiguity review: no ambiguity review feedback (substantive ambiguities — disable-model-invocation overlap, interactive-listing scope, exact set to mark, false-coercion rule — are already enumerated as design.md Open Questions 1-4)
- plan ambiguity review: no plan ambiguity review feedback (no plan.md/steps.md yet — task in design phase; substantive ambiguities already captured as design.md Open Questions 1-4)
- plan inconsistency review: no plan inconsistency review feedback (no plan.md/steps.md yet; prior byte-identical inconsistency already reconciled in design.md and design-steps; task files mutually consistent)
- inconsistency review added 1 new design step (Constraints "byte-identical for all currently-advertised" contradicts the in-task flip of currently-advertised items to advertise:false; invariant should be scoped to advertise absent/true items)

## Context for addressing design-steps
- The flagged design-step is a design.md wording fix (Constraints section) — keep the byte-identical invariant scoped to `advertise` absent/`true`; do not widen/narrow the frozen scope while doing so.
- Relevant non-task source files (for later implementation, not the wording fix):
  - Skills: `components/prompt-assets/src/psi/prompt_assets/skills.clj` — frontmatter parse (~L137) and `format-skills-for-prompt`/`-lambda` (~L502/527); existing `disable-model-invocation` filter is the pattern to mirror for `advertise`.
  - Workflows: `components/agent-session/src/psi/agent_session/workflow/text.clj` — `build-prompt-contribution` (~L93) is the system-context listing to filter on `:advertise false`.
  - `/delegate list` / `action=list` listing also lives in `text.clj` (`available-workflows-text`/`delegate-list-text`); design Open Question 2 governs whether those are affected — confirm before touching them.

## Plan-review session outcome
- Plan-review (ambiguity + inconsistency) added no new design-steps; no unchecked design-steps remain — task is design-stable and ready to advance to plan.md/steps.md creation, where Open Questions 1-4 (esp. exact set to flip, Q3) must be resolved.

## Design-follow-up resolution (inconsistency step)
- Resolved the byte-identical inconsistency by scoping the Constraints invariant to items whose `advertise` remains absent/`true`, and explicitly stating the in-task `review-*`/`issue-*` + sub-only-workflow flip is the intended exception. No scope change to the design — the frozen scope (which items get flipped) is unchanged; only the invariant wording was reconciled. Exact enumeration of flipped items remains deferred to planning per Open Question 3.

## Slice 1 — Mechanism (implemented)
- Decisions on Open Questions recorded in plan.md (Q1=keep both fields; Q2=prompt-contribution only; Q3=enumerated in plan, applied slice 2; Q4=only literal `false` disables).
- Design refinement: `advertise` is also supported in **markdown** workflow frontmatter (not just EDN), because many sub-only workflows are `.md` files. Uniform concept across both file kinds.
- Skills (`prompt_assets/skills.clj`): `parse-skill-file` derives `:advertise` (default true; only `"false"` disables), `->skill` propagates it. New private `prompt-hidden?` = `disable-model-invocation OR (false? :advertise)`; both `format-skills-for-prompt` and `-lambda` use it. Absent `:advertise` ⇒ advertised (robust for skills built elsewhere).
- EDN workflows: `:advertise` already flows through `compile-edn-workflow-file` (config passthrough). `text/build-prompt-contribution` now removes `(false? (:advertise defn-map))`. User-facing `available-workflows-text`/`delegate-list-text` deliberately unchanged (Q2).
- Markdown workflows: `:advertise` added to `allowed-md-frontmatter-keys`; parsed with same false-coercion; `compile-markdown-workflow-file` sets `:advertise (if (nil? advertise) true advertise)`.
- Filter predicate uses `false?` (not `not`) so absent values stay advertised — byte-identical default behaviour preserved.
- Verified: clj-kondo clean; 30 tests / 269 assertions pass (skills, parser, compiler, text).

## Remaining (Slice 2 — Apply the field)
- Flip enumerated review-*/issue-* skills and sub-only workflows to `advertise: false` and verify drop-from-context + still-invocable.

## Slice 2 — Apply the field (implemented)
- Skills flipped (advertise: false): review-implementation-architecture, review-task-architecture, review-task-docs, task-implementation-review, task-test-review, issue-bug-triage, issue-feature-triage.
- Workflows flipped — auditable basis: sub-only iff referenced as :prompt-workflow or :target by another workflow, or described as a lower-level/handoff sub-workflow; top-level user-facing entries left advertised.
  - markdown (22): create-task-plan-create-plan, implement-task-implement-pass, implement-task-final-summary, implement-task-in-worktree, resolve-task-design-entities-resolve, review-follow-up-{design,plan,steps}, review-task-design-{ambiguity,architecture,inconsistency}-review, review-task-design-final-summary, review-task-note-info, review-task-plan-{ambiguity,inconsistency}-review, review-task-plan-final-summary, gh-bug-{discover-and-read,post-repro,reproduce}, gh-issue-{create-worktree,push-intent,task-intent}.
  - edn (8): review-task-{design,implementation,plan}-core, review-step, review-design-turn, gh-bug-request-more-info, review-implementation-in-worktree, task-lifecycle-in-worktree.
- Left advertised (shared sub-loops/ambiguous, conservative): gh-pr-heal-check-loop, resolve-task-design-entities (top-level wrapper), review-task-{design,plan,implementation} (standalone-summary wrappers).
- Live verification (loader + text/build-prompt-contribution on this worktree): all flipped workflows registered but absent from prompt contribution; plan-build-review/task-lifecycle still advertised. Pre-existing 7 markdown-body load errors are unrelated to this change.

## Implementation review (task-implementation-review)
- added 4 follow-up steps: EDN advertise passthrough test gap, missing automated invocability assertion, markdown/EDN default asymmetry, and untracked doc/agent-facets.md.

## Implementation review (second pass)
- no new actionable steps; mechanism + tests + docs verified (28 tests/265 assertions pass, clj-kondo clean). Prior 4 follow-ups confirmed addressed.

## Review follow-up execution
- addressed 4 review steps: added EDN `:advertise false` propagation + absent-stays-absent compiler tests; added automated registered-and-invocable assertions for non-advertised workflow (text-test) and skill (skills-test); documented the deliberate markdown(explicit-true)/EDN(passthrough-nil) default asymmetry in `compile-edn-workflow-file`; removed stray untracked `doc/agent-facets.md` (incomplete stub, not a task deliverable — `doc/workflows.md` is the task doc).
- verified: clj-kondo 0 errors; 26 tests / 229 assertions pass (compiler-test, text-test, skills-test).

## Test review (task-test-review)
- added 1 follow-up step: `format-skills-for-prompt-lambda` advertise-exclusion is untested despite being a named acceptance-criterion surface.

- addressed 1 test-review follow-up: added `format-skills-for-prompt-lambda-test` covering advertise-false / disable-model-invocation exclusion, lambda-description, absent-default, and nil-on-empty cases (21 tests, 167 assertions pass; kondo clean).

## Test review (task-test-review, second pass)
- added 1 follow-up step: workflow invocability is asserted only via a listing-presence proxy, not an actual run/sub-step, unlike the real `invoke-skill` skill test — gap against acceptance criterion 2.

## Test review follow-up execution
- addressed 1 test-review step: extracted `text/resolve-runnable-definition` (the by-name execution-resolution gate used by `delegate-run`), wired the `/delegate run` gate through it, and added `resolve-runnable-definition-test` asserting an `:advertise false` workflow is dropped from `build-prompt-contribution` yet still resolves-for-execution by name (and nil for unregistered names). Guards against a future change leaking the advertise filter into registration/execution.
- verified: clj-kondo 0 warnings/errors; text-test 3 tests / 13 assertions pass.

## Test review (task-test-review, third pass)
- no new actionable steps; tests well-formed, cover design behaviours (both skill formatters, workflow prompt-contribution, parse robustness, EDN/md propagation, registered-and-invocable, execution-resolution), real/nullable deps without mocks. text-test 3 tests/13 assertions pass.

## Test review (test-shaper pass)
- added 2 follow-up steps: untested `->skill` `:advertise` propagation (skill-construction-test gap), and name/assertion mismatch in `non-advertised-workflow-stays-listed-and-invocable-test` (overclaims "invocable", now redundant with resolve-runnable-definition-test).
- addressed 2 test-shaper review follow-up steps: added `:advertise` propagation assertions to `skill-construction-test` (explicit false + absent cases); renamed `non-advertised-workflow-stays-listed-and-invocable-test` -> `non-advertised-workflow-stays-listed-test` and dropped the redundant invocability inference (execution-resolution covered by `resolve-runnable-definition-test`).

## Test review (test-shaper pass 2)
- added 1 follow-up step: EDN-config advertise tests are grouped under the markdown-named `compile-markdown-workflow-file-test` deftest (naming/concern mismatch) despite an existing `compile-edn-prompt-workflow-test` deftest.
- addressed 1 test-shaper pass-2 follow-up step: moved the two EDN-config advertise tests ("advertise false in edn config propagates" / "advertise absent from edn config leaves advertise absent") out of `compile-markdown-workflow-file-test` into `compile-edn-prompt-workflow-test` so naming matches concern. Tests pass (63 assertions, 4 tests).

## Test review (test-shaper pass 3)
- added 1 follow-up step: lambda-formatter and workflow-listing exclusion tests use bare-substring negatives, inconsistent with the delimited `<name>…</name>` negatives in the XML formatter tests and not anchored on the entry contract.
- addressed 1 test-shaper pass-3 follow-up step: anchored the lambda-formatter negatives on the entry form (`"internal → "`, `"hidden → "`) and the workflow-listing negatives on `"- internal:"` in skills_test and text_test. Tests pass (text-test 13 assertions; skills-test 170 assertions; 0 failures).

## Test review (test-shaper pass 4)
- added 1 follow-up step: exclusion-test positives stay bare substrings while pass-3 anchored the negatives — internal assertion-style inconsistency within each exclusion test.
- addressed 1 test-shaper pass-4 follow-up step: anchored the exclusion-test positives on the entry form (`"<name>visible</name>"` for the XML formatter, `"visible → "` for the lambda formatter, `"  internal — "` for the workflow listing) in skills_test and text_test. Tests pass (24 tests, 183 assertions; 0 failures).

## Test review (test-shaper pass 5)
- no new actionable steps; tests well-shaped after passes 1-4 (anchored positives/negatives, concern-aligned EDN/markdown deftests, real/nullable deps, coverage of both skill formatters + workflow prompt-contribution + parse robustness + propagation + registered-and-invocable + execution-resolution). 28 tests / 246 assertions pass.

## Docs review (review-task-docs)
- added 1 follow-up step: CHANGELOG/doc/workflows.md describe flipped skills as `review-*`/`issue-*` but omit the two `task-*-review` skills actually flipped (accuracy/completeness gap).

- addressed 1 docs-review follow-up: corrected CHANGELOG flipped-skill description to `review-*`, `task-*-review`, `issue-*` (was only `review-*`/`issue-*`, omitting `task-implementation-review`/`task-test-review`). `doc/workflows.md` describes the field generically without enumerating flipped skills, so no change needed there.

## Docs review (review-task-docs, second pass)
- added 1 follow-up step: CHANGELOG combines skill+workflow invocation paths into one list ("the skill/workflow"), implying skills are reachable via `/delegate` and workflows via `/skill:name` (accuracy gap; `doc/workflows.md` already separates the two surfaces).

- addressed 1 docs-review (second pass) follow-up: split the CHANGELOG combined invocation-path sentence per surface — skills via `/skill:name` + direct file read; workflows via `/delegate <name>` + `/delegate list` + sub-step. `doc/workflows.md` already separates the surfaces, no change.

## Docs review (review-task-docs, third pass)
- added 1 follow-up step: CHANGELOG + `doc/workflows.md` list the user-facing `/delegate list` as an invocation path for hidden workflows, but it is a listing/discovery surface, not an invocation path (category error).
- addressed 1 docs review follow-up (third pass): separated `/delegate list` listing surface from invocation paths in CHANGELOG + doc/workflows.md

## Docs review (review-task-docs, fourth pass)
- added 1 follow-up step: `doc/workflows.md` sub-only-workflow example list omits the "modular `gh-*` sub-steps" category that the CHANGELOG includes (consistency/completeness gap between two user-facing docs for the same flipped set).

- addressed 1 docs-review follow-up (4th pass): added `gh-*` sub-step category to doc/workflows.md advertise example list, matching the CHANGELOG.

## Docs review (review-task-docs, fifth pass)
- no new actionable steps; CHANGELOG + doc/workflows.md accurate/complete/consistent (skill set `review-*`/`task-*-review`/`issue-*`, workflow sub-step set, surface-separated invocation vs `/delegate list` listing). README correctly defers detail to doc/workflows.md.

## Code-shaper review
- added 2 follow-up steps: skill-visibility partition (skill-summary/visible-skills/hidden-skills/enrich-skill) still keys off `:disable-model-invocation` only and diverges from the new canonical `prompt-hidden?` (advertise-false skills reported "visible"); and a third inline duplicate of the visibility filter in tui render `banner-rows`.

## Code-shaper review follow-up (execution)
- addressed 2 code-shaper follow-up steps. Decision: system-context visibility is the single concept; promoted `prompt-hidden?` + `visible-skills`/`hidden-skills` into `psi.skill-registry.registry` (shared by prompt-assets and tui; tui cannot depend on prompt-assets). Routed skill-summary counts, visible/hidden-skills, and enrich-skill `:is-available-to-model` through it; tui `banner-rows` now reuses `skill-registry/visible-skills`. Added registry tests (`prompt-hidden?-test`, `visible-hidden-skills-test`) and advertise-false assertions to skills introspection tests. clj-kondo clean; 30 tests / 233 assertions pass.

## Code-shaper review (second pass)
- added 1 follow-up step: formatters + skill-summary recompute the system-context partition inline rather than reusing the canonical `visible-skills`/`hidden-skills` helpers (single-source-of-truth residual after first code-shaper pass).

## Code-shaper review follow-up (second pass execution)
- addressed 1 code-shaper follow-up step. Routed `format-skills-for-prompt`, `format-skills-for-prompt-lambda`, and `skill-summary` counts through `skill-registry/visible-skills`/`hidden-skills` (canonical partition); removed the now-unused private `prompt-hidden?` alias (`enrich-skill` already uses the registry predicate directly). Single source for the partition rule. clj-kondo clean; skills-test 17 tests / 115 assertions pass.

## Code-shaper review (third pass)
- added 1 follow-up step: `parse-skill-file` uses divergent boolean-frontmatter coercion idioms for `disable-model-invocation` (case-sensitive, no trim) vs `advertise` (trim+lowercase), an in-function consistency gap and a secondary divergence from design Q4's "only the literal `false`" wording.

## Code-shaper review follow-up (third pass execution)
- addressed 1 code-shaper follow-up step. Extracted a shared `frontmatter-flag` helper in `parse-skill-file` recognising only literal `true`/`false` (case-insensitive, trimmed) and defaulting otherwise; routed both `disable-model-invocation` (default false) and `advertise` (default true) through it. Aligned design Q4 wording to the chosen case-insensitive/trimmed literal-`false` rule and noted the shared helper. clj-kondo clean; skills-test 17 tests / 115 assertions pass.

## Code-shaper review (fourth pass)
- added 2 follow-up steps: the workflow-markdown parser (`workflow_loader/parser.clj`) still uses the inline `(not (= "false" ...))` `advertise` coercion that the third pass replaced with the shared `frontmatter-flag` helper on the skill side (cross-surface idiom/rule divergence); and a dead `(if (nil? advertise) true advertise)` nil-default in `compile-markdown-workflow-file` (parser always yields boolean).

## Code-shaper review follow-up (fourth pass) — addressed
- Promoted `frontmatter-flag` to shared `prompt-templates`; skills.clj aliases it,
  workflow-loader `parser.clj` now coerces `:advertise` via `pt/frontmatter-flag`
  (one shape, symmetric true/false recognition across both surfaces).
- Dropped the dead `(if (nil? advertise) true advertise)` guard in
  `compile-markdown-workflow-file` (parser always supplies an explicit boolean);
  reconciled the EDN-path comment and updated `compiler-test` fixture/assertion
  to the parser-supplied-boolean contract.
- addressed 2 review steps.

## Code-shaper review (fifth pass)
- added 1 follow-up step: residual `frontmatter-flag` re-export alias in skills.clj (L124) adds an unnecessary public-var indirection over the canonical `pt/frontmatter-flag`; two internal call-sites can use `pt/` directly.
