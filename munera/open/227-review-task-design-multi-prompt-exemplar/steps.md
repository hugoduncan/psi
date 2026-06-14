# Steps — review-task-design multi-prompt exemplar

## Slice 1 — Pass-feedback routing validation

- [x] Read `components/agent-session/src/psi/agent_session/workflow/routing.clj` and `components/agent-session/src/psi/agent_session/workflow/core.clj` to confirm the existing `PASS_STATUS` parser and `workflow/pass-feedback-routing` registration shape.
- [x] Add or update focused routing tests proving `workflow/pass-feedback-routing` returns `DONE` when every supplied reply has `PASS_STATUS: REVIEW_COMPLETE`.
- [x] Add or update focused routing tests proving `workflow/pass-feedback-routing` returns `REPEAT` when any supplied reply has `PASS_STATUS: ACTIONABLE_FEEDBACK` and reports the actionable keys in details.
- [x] Add focused routing tests proving `workflow/pass-feedback-routing` returns deterministic operation errors for missing `PASS_STATUS`, duplicate `PASS_STATUS` lines, malformed status lines, and disallowed known statuses such as `IMPLEMENTATION_COMPLETE`.
- [x] Tighten `workflow/pass-feedback-routing` to validate every supplied reply with allowed statuses `ACTIONABLE_FEEDBACK` and `REVIEW_COMPLETE` before computing the pass-level route.
- [x] Ensure invalid `pass-feedback-routing` results include per-key validation failure details suitable for diagnosing a blocked or failed workflow run.

## Slice 2 — Merged workflow topology

- [x] Rewrite `.psi/workflows/review-task-design.edn` so the review loop contains `design-review`, `design-follow-up`, and `final-summary` only.
- [x] Configure `design-review` as a `:session` step with step-level tools `read`, `bash`, `edit`, `write`.
- [x] Configure `design-review` with step-level skills `work-independently`, `review-task-architecture`, and `task-design`.
- [x] Add three ordered `:prompts` groups to `design-review`: `architecture`, `ambiguity`, and `inconsistency`, each referencing the existing corresponding review prompt markdown file.
- [x] Attach a post-drain `workflow/pass-feedback-routing` judge to `design-review` with args sourced from each prompt's `:final-llm-reply` output.
- [x] Route `design-review` `REPEAT` to `design-follow-up` and `DONE` to `final-summary`.
- [x] Add `design-follow-up` as a single design-profile session step using `review-follow-up-design.md` and a constant `DONE` judge.
- [x] Route `design-follow-up` `DONE` back to `design-review` with `:max-iterations 6` on that transition.
- [x] Migrate `final-summary` source contributions from the removed per-phase step `:yield` refs to per-prompt output refs on `design-review` for `architecture`, `ambiguity`, and `inconsistency`.
- [x] Remove stale references in `review-task-design.edn` to `architecture-review`, `architecture-follow-up`, `ambiguity-review`, `ambiguity-follow-up`, `inconsistency-review`, `inconsistency-follow-up`, and `clarity-status`.

## Slice 3 — Prompt contracts

- [x] Update `review-task-design-architecture-review.md` to state that it is the first shared-session turn and must read the task `design.md` and consult `AGENTS.md`, `META.md`, and `doc/architecture.md` as needed before producing architectural feedback.
- [x] Update `review-task-design-ambiguity-review.md` to reuse the shared session's already-loaded design, architecture sources, and architecture-review reply by default, with only targeted re-reads for missing, ambiguous, or stale facts.
- [x] Update `review-task-design-inconsistency-review.md` to reuse the shared session context and prior review replies by default, with only targeted re-reads for specific missing or stale referenced material.
- [x] Preserve each review prompt's design-steps/implementation note instructions and terminal two-line `PASS_STATUS: ACTIONABLE_FEEDBACK|REVIEW_COMPLETE` menu.
- [x] Update `review-follow-up-design.md` to define the preceding review pass as the immediately preceding whole `design-review` batch when used by the merged workflow.
- [x] Update `review-follow-up-design.md` with the git/task-file evidence rule for identifying batch-added unchecked `design-steps.md` items.
- [x] Update `review-follow-up-design.md` to instruct the agent to leave ambiguous, stale, or unattributable unchecked items untouched and record a terse blocking note in `implementation.md`.

## Slice 4 — Definition and runtime test alignment

- [x] Resolve the plan/steps test-timing inconsistency: either move/add the definition, prompt-contract, and runtime test updates so they occur before or in the same slices as the workflow topology and prompt behavioral changes, or revise `plan.md` to remove the “before or with each behavioral change” requirement.
- [x] Update `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj` `review-task-design-test` to expect three steps: `design-review`, `design-follow-up`, and `final-summary`.
- [x] Add workflow-definition assertions that `design-review` carries the exact step-level tools and skills required by the merged prompt groups.
- [x] Add workflow-definition assertions that `design-review` contains the ordered prompt groups `architecture`, `ambiguity`, and `inconsistency` with the expected prompt-workflow filenames.
- [x] Add workflow-definition assertions that `design-review`'s judge uses `workflow/pass-feedback-routing` over the three per-prompt `:final-llm-reply` output refs.
- [x] Add workflow-definition assertions that `design-follow-up` uses `review-follow-up-design.md`, routes constant `DONE`, and returns to `design-review` with `:max-iterations 6`.
- [x] Add workflow-definition assertions that `final-summary` sources all three review texts through per-prompt output refs rather than removed step-yield refs.
- [x] Update prompt artifact/contract tests so the design review prompts and follow-up prompt lock the shared-session and batch-follow-up wording without accepting the old interleaved-only wording.
- [x] Update review-routing runtime tests to model the merged multi-prompt design-review batch and prove a clean batch reaches final summary.
- [x] Update review-routing runtime tests to prove actionable feedback from any prompt in the batch runs one `design-follow-up` and then starts the next `design-review` pass.
- [x] Update review-routing runtime tests to prove the sixth actionable design-review pass fails through the iteration guard on the attempted seventh `design-review` entry.
- [x] Keep or adjust `review-task-plan` tests so valid plan-review pass-feedback routing still works under the tightened validation semantics.

## Slice 5 — Documentation and changelog

- [x] Update `doc/workflows.md` to describe `review-task-design` as a batch-review-then-follow-up workflow using one multi-prompt `design-review` step.
- [x] Document in `doc/workflows.md` that the batch shape deliberately differs from the old interleaved per-phase follow-up topology.
- [x] Document in `doc/workflows.md` that the single design follow-up executes only unchecked items newly added by the immediately preceding review batch using the git/task-file evidence rule.
- [x] Update `doc/workflows.md` or nearby workflow grammar docs only if needed to reference the existing per-prompt output refs used by the exemplar.
- [x] Add a `CHANGELOG.md` `[Unreleased]` entry for the user-visible `review-task-design` workflow topology change and stricter pass-feedback validation.

## Slice 6 — Verification and cleanup

- [x] Run focused workflow-loader definition tests for `psi.workflow-loader.workflow-definitions-test` and fix any failures.
- [x] Run focused review-routing tests for `psi.agent-session.workflow-review-step-routing-test` and fix any failures.
- [x] Run focused deterministic-operation tests if any `workflow/pass-feedback-routing` coverage lives outside the review-routing namespace.
- [x] Run `clj-kondo --lint` on all touched Clojure source and test files and fix lint findings.
- [x] Re-read all touched workflow markdown/EDN files to verify no stale step names or illegal per-prompt `:yield` refs remain.
- [x] Append concise implementation notes to this task's `implementation.md` covering key decisions, verification commands, and any deviations from the plan.
