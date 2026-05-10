Approach:
- treat retirement as the final cleanup after the representative target-authored path has already been proven by `089`, `092`, and `093`
- make the remaining blockers explicit and execute them in three ordered layers:
  1. migrate the remaining checked-in current-authored workflows
  2. remove the current-authored compiler/runtime/loader path and its compatibility-oriented tests/helpers
  3. remove or rewrite docs that still present the current grammar as a live authored option
- keep the work repo-specific and evidence-driven: only retire compatibility after the checked-in workflow set, tests, and docs no longer need it

Current concrete blockers identified from the repo:
1. Repository scope for workflow migration is the checked-in workflow set under `.psi/workflows/*.md` in this repo only; global workflow directories are out of scope for retirement gating here.
2. Remaining current-authored checked-in workflows:
   - `lambda-build.md`
   - `prompt-build.md`
   - `gh-issue-refine.md`
   - `gh-issue-implement.md`
   - `gh-pr-heal-check-loop.md`
   - `gh-pr-fix-current-checks.md`
   - `gh-pr-fix-checks.md`
   - `review-implementation.md`
   - `review-task-until-clear.md`
3. Remaining current-grammar compiler/runtime path to remove or explicitly justify retaining:
   - `components/agent-session/src/psi/agent_session/workflow_current_ir_compiler.clj`
   - current-grammar branch in `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
   - current-authored target detection / compilation / validation branches in `components/agent-session/src/psi/agent_session/workflow_file_compiler.clj`
   - compat binding / prompt-template / executor-profile fallbacks in `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
   - compat session-preload shaping in `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
4. Remaining current-grammar tests/docs:
   - `workflow_current_ir_compiler_test.clj`
   - current-authored/compat equivalence assertions that cease to matter after retirement
   - `doc/workflow-grammar-current.md`
   - `doc/workflow-grammar-migration.md`
   - live references from `doc/workflows.md`, `doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`, and `doc/workflow-ir.md`

Explicit retirement gates:
- Gate 1: all checked-in `.psi/workflows/*.md` files in this repo compile as target-authored workflows; no checked-in workflow depends on `:workflow` multi-step entries, `:kind :accepted-result`, or current-authored `:session` maps.
- Gate 2: no runtime code path used by workflow loading/run creation depends on `workflow_current_ir_compiler.clj`.
- Gate 3: active docs no longer rely on `doc/workflow-grammar-current.md` or `doc/workflow-grammar-migration.md`; for this task the chosen end state is deletion, with history preserved in git.
- Gate 4: targeted workflow compilation/execution verification is green after compatibility removal.

Likely execution order:
1. migrate the remaining current-authored checked-in workflows in the smallest coherent groups
   - compact builder/compiler loops (`lambda-build`, `prompt-build`)
   - review loops (`review-implementation`, `review-task-until-clear`)
   - PR/issue orchestration flows (`gh-issue-refine`, `gh-issue-implement`, `gh-pr-heal-check-loop`, `gh-pr-fix-current-checks`, `gh-pr-fix-checks`)
2. tighten migration validation tests so checked-in workflows are asserted target-authored only
3. remove current-authored file compilation/runtime support
4. delete compatibility-only tests/docs/helpers
5. delete the current-grammar docs and rewrite remaining workflow docs so target grammar + IR are the only live workflow story
6. run focused then broader verification using these authoritative commands:
   - focused: `clojure -M:test --focus psi.agent-session.workflow-file-loader-test --focus psi.agent-session.workflow-migration-validation-test --focus psi.agent-session.workflow-target-ir-compiler-test --focus psi.agent-session.workflow-execution-test`
   - broader: `bb clojure:test:unit`

Proof target:
- all checked-in workflows are target-authored
- workflow loading and run creation accept only the target-authored workflow surface
- docs and tests no longer preserve the old authored grammar as a live option

Risks:
- some remaining checked-in workflows may still rely on current-authored preload/reference semantics that need careful target-authored restatement
- some tests currently proving migration equivalence will need replacement rather than deletion so target-only invariants remain strong
- stale migration/history docs may preserve conceptual confusion if not rewritten decisively
