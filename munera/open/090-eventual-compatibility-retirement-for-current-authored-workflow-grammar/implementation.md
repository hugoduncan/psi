2026-05-06 review
- Reviewed `design.md`, `plan.md`, and `steps.md` for ambiguity and inconsistencies after the repo-specific retirement planning pass.
- Found actionable ambiguity: the task says all checked-in workflows must be target-authored before retirement, but it does not distinguish project-local checked-in workflows under `.psi/workflows/` from loader-discovered global workflow directories. For execution, the gate should be interpreted as applying to repository-owned checked-in workflows in this project.
- Found actionable ambiguity: `remove or archive` for `doc/workflow-grammar-current.md` and `doc/workflow-grammar-migration.md` leaves two materially different end states. The task should choose one explicit documentation outcome so implementers do not satisfy it with incompatible historical/documentation shapes.
- Found actionable ambiguity: `compat-oriented seams in step prep / statechart runtime that only exist to preserve current-authored behavior` is too broad as an execution target. The task needs an explicit inventory of which seams are expected to be removed versus retained as target-runtime implementation details after compiler retirement.
- Found actionable ambiguity: `Run focused verification` and `Run broader verification` do not name authoritative command sets. Without explicit commands or suites, completion remains subjective.
- Found actionable inconsistency: `steps.md` has a concrete repo-specific inventory for remaining current-authored workflows, but the code/test retirement section still uses partially abstract phrasing (`prune compatibility-only helper/seam tests`) rather than naming the expected proofs or files that replace them.
- Found actionable inconsistency: `design.md` says remove or disable current-authored grammar support once prerequisites are satisfied, while `plan.md`/`steps.md` read as full removal. The task should prefer one explicit end state for this repo: disable temporarily behind a gate or delete outright.
- Recommended resolution direction:
  1. define repository scope explicitly as checked-in `.psi/workflows/*.md` in this repo
  2. choose one doc end state (`delete` or `retain as historical note but not linked as live guidance`)
  3. add an explicit code-path inventory for retirement targets
  4. name the focused and broader verification commands
  5. choose one final compatibility outcome (`remove`, not merely `disable`, if that is the intended repo end state)

2026-05-06 implementation slice 1
- Migrated `lambda-build.md` and `prompt-build.md` from current multi-step `:workflow` entries to target-authored `:type :delegate` steps.
- Preserved the original ask/reference material explicitly with ordered delegate `:context` entries sourced from `:workflow-original`.
- Switched downstream chaining to canonical prior-step yielded-text refs (`{:from {:step ... :yield :text}}`) so these workflows now exercise the same delegate result contract already taught by `delegate-build-review.md`.
- Kept the prompts explicit in `:prompt-string` template form instead of relying on `$INPUT` substitution.

2026-05-06 implementation slice 2
- Migrated `review-implementation.md` and `review-task-until-clear.md` from current multi-step `:workflow`/`:session` compatibility forms to target-authored inline `:type :session` steps.
- Replaced compatibility `:preload` usage with explicit ordered `:contributions`, carrying prior review/follow-up yields as text context into later steps.
- Re-expressed the review-loop routing stage as an explicit terminal status step plus typed LLM judge/routing instead of a current-grammar step-local judge surface.
- Kept builder-like capabilities explicit per inline session step so the workflows still describe their file-editing behavior through the target grammar rather than through inherited current-grammar workflow profiles.

2026-05-06 implementation slice 3
- Migrated `gh-issue-refine.md` and `gh-issue-implement.md` from current-authored orchestration steps to target-authored delegate/session flows.
- Kept cross-step machine context explicit by forwarding prior yielded text and original request material through ordered delegate/session context contributions.
- Preserved loop semantics by splitting the design-clarity checkpoint into a dedicated status step with typed LLM judge routing back to the refinement step.
- Kept the heavy operational bodies in builder delegates so the workflows preserve behavior while removing the current `:workflow`/`:session` compatibility grammar from the checked-in authored surface.

2026-05-06 implementation slice 4
- Migrated the remaining PR check-healing workflows (`gh-pr-heal-check-loop.md`, `gh-pr-fix-current-checks.md`, and `gh-pr-fix-checks.md`) to target-authored delegate/session forms.
- Replaced compatibility step-to-step handoff wiring with explicit delegate prompt/context flow and dedicated typed status steps for PASS/PENDING/FAIL/BLOCKED and WAIT/BLOCKED routing.
- Preserved the original operational prompts and loop bounds while removing checked-in uses of current-authored multi-step `:workflow` entries, compatibility `:session` maps, and current judge syntax from the repo-owned workflow set.

2026-05-06 implementation slice 5
- Migrated the remaining checked-in single-purpose helper workflows (`planner`, `builder`, `reviewer`, `lambda-compiler`, `lambda-decompiler`, `prompt-compiler`, `prompt-decompiler`, and `allium-check`) to target-authored single-step `:type :session` files.
- This removes the last repo-owned dependence on single-step current-authored file compilation, so repo checked-in `.psi/workflows/*.md` are now uniformly target-authored.
- Kept tool/skill behavior explicit in canonical session fields and moved the old prompt bodies into template contributions that read the full workflow input directly.

2026-05-06 implementation slice 6
- Removed compatibility step-prep fallbacks for current `:input-bindings`, `:prompt-template`, profile-derived workflow meta lookup, and `:session-overrides` in favor of canonical target-authored session fields only.
- Removed compatibility `:session-preload` merging from the Phase A statechart runtime so child-session preload now comes solely from canonical session contributions.
- Replaced the old preload/compat regression proofs with canonical contribution-order tests in workflow step-prep, statechart runtime, and workflow execution.

2026-05-06 implementation slice 7
- Deleted `components/agent-session/src/psi/agent_session/workflow_current_ir_compiler.clj` and its dedicated test file because repo-owned workflow loading and run creation are now target-authored only.
- Confirmed `workflow_runtime.clj` and `workflow_file_compiler.clj` already run target-only validation/compilation paths, so the remaining retirement step was removal of the dead current-grammar compiler artifact rather than further runtime branching changes.
- Identified remaining follow-on cleanup after this slice as target-only test/doc rewrites: retire lingering current-grammar fixture tests and remove live doc references to the deleted migration/current-grammar guidance pages.

2026-05-06 implementation slice 8
- Rewrote residual loader/runtime/compiler tests so they assert the target-only world explicitly instead of preserving current-grammar assumptions.
- `workflow_file_loader_test.clj` now proves two retirement-facing invariants: delegate targets can remain unresolved at load time as named boundary data, and old current-authored workflow files are rejected with target-authored-only compile errors.
- `workflow_ir_runtime_adoption_test.clj` now uses target-authored fixtures while still proving that non-canonical `:workflow-runtime` refs fail at the canonical IR validation seam.
- `workflow_target_ir_compiler_test.clj` now carries the non-canonical source-ref rejection proof directly, replacing reliance on deleted current-grammar compiler coverage.
- Focused verification green: `clojure -M:test --focus psi.agent-session.workflow-file-loader-test --focus psi.agent-session.workflow-target-ir-compiler-test --focus psi.agent-session.workflow-ir-runtime-adoption-test`.

2026-05-06 implementation slice 9
- Deleted `doc/workflow-grammar-current.md` and `doc/workflow-grammar-migration.md`; the live docs now treat git history as the historical record for the retired current-authored grammar.
- Rewrote `doc/workflows.md` so it teaches only the supported grammar and removed the transitional current→target mapping plus migration-era related-doc links.
- Rewrote `doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`, and `doc/workflow-ir.md` to remove references that presented the deleted current grammar as a live authored option.
- Clarified the remaining `workflow_ir.md` note on `:workflow-runtime`: it is now documented only as a rejected non-canonical source-ref, not as an active migration seam.

2026-05-06 implementation slice 10
- Rewrote the remaining legacy-targeted unit surfaces named in the last broad-suite failure set so they now exercise the target-authored-only world explicitly.
- `workflow_file_compiler_test.clj` no longer expects retired single-step/multi-step current-grammar compilation outputs; it now proves only target-authored file preservation, explicit rejection of legacy authored files, batch compilation accounting, and the retirement-era no-op loader validation helpers.
- `workflow_tools_test.clj`, `mutations/canonical_workflows_test.clj`, and `workflow_session_integration_test.clj` now register/create runs from target-authored definitions only, replacing old canonical-runtime fixture shapes that bypassed the target-authored registration seam.
- Focused verification for these rewritten surfaces is green: `clojure -M:test --focus psi.agent-session.workflow-file-compiler-test --focus psi.agent-session.workflow-tools-test --focus psi.agent-session.mutations.canonical-workflows-test --focus psi.agent-session.workflow-session-integration-test` (`17 tests, 126 assertions, 0 failures`).

2026-05-06 implementation slice 11
- Rewrote the next broader-suite legacy fixtures still calling `workflow-runtime/register-definition` with retired canonical map-shaped definitions.
- `workflow_progression_test.clj`, `workflow_execution_resume_test.clj`, `workflow_lifecycle_test.clj`, and `workflow_resolvers_test.clj` now construct runs from target-authored definitions only.
- For resolver coverage, kept the assertions aligned with current resolver projection reality instead of preserving removed prompt-template/capability-policy expectations from pre-retirement stored-step shapes.
- Focused verification for these follow-on rewrites is green: `clojure -M:test --focus psi.agent-session.workflow-progression-test --focus psi.agent-session.workflow-execution-resume-test --focus psi.agent-session.workflow-lifecycle-test --focus psi.agent-session.workflow-resolvers-test` (`11 tests, 69 assertions, 0 failures`).
- A fresh broad run still needs to be rerun after this slice to discover whether any further legacy canonical-definition fixtures remain outside this now-updated set.

2026-05-06 implementation slice 12
- Reran the full unit suite after the remaining runtime/resolver fixture rewrites.
- Full broader verification is now green: `bb clojure:test:unit` (`1513 tests, 11015 assertions, 0 failures`).
- This confirms the task-090 retirement end state now holds across the full unit suite: repo-owned checked-in workflows, loader/compiler/runtime seams, docs, and the remaining unit fixtures all converge on target-authored workflow definitions only, with no live current-authored registration path left in the exercised repo surfaces.
