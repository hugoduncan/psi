# Steps

## Slice 1 — Implementation-pass blocked contract

- [x] Update `.psi/workflows/implement-task-implement-pass.md` to permit exactly `MORE_WORK_REMAINS`, `IMPLEMENTATION_COMPLETE`, and `IMPLEMENTATION_BLOCKED`, and require the exact complete two-field `IMPLEMENTATION_BLOCKER` append before emitting the blocked status.
- [x] Extend workflow definition tests to verify the implementation-pass prompt owns the three-status contract, exact blocker delimiters and fields, non-empty field requirement, and append-before-status requirement.
- [x] Run the focused workflow-loader definition tests for the implementation-pass contract.
  - `bb clojure:test:scry --namespace psi.agent-session.workflow-migration-validation-test` — 6 tests, 40 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj` — no findings.
- [x] Commit the Slice 1 prompt and proof changes without staging unrelated worktree changes.

## Slice 2 — Standalone implement-task routing and terminal handbacks

- [x] Update `.psi/workflows/implement-task.edn` so `implement-pass` uses `workflow/exact-marker-routing` for the three authored raw routes, preserving the 20-pass `MORE_WORK_REMAINS` loop and normal completion destination.
- [x] Split the terminal summaries into explicit normal and blocked steps; make each inspect the task artifacts and end with exactly one matching column-zero `IMPLEMENTATION_STATUS` line.
- [x] Define the blocked summary contract to select and reproduce the last complete `IMPLEMENTATION_BLOCKER` block's `blocker` and `required-human-action`, report completed work and verification, distinguish blocked from completed, and require resolution plus fresh re-invocation.
- [x] Extend loader/definition tests to prove the three-route table, separate terminal branches, branch-specific exported statuses, and summary blocker-selection instructions.
  - `bb clojure:test:scry --namespace psi.agent-session.workflow-migration-validation-test` — 7 tests, 49 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj` — no findings.
- [x] Extend execution-routing tests to prove valid repeat, completion, and blocked outcomes; blocked does not re-enter `implement-pass`; malformed, duplicated, and unsupported markers fail; and the repeat limit remains bounded.
  - `workflow-review-step-routing-test` now proves malformed, duplicate, and unsupported `PASS_STATUS` replies fail before either summary, and 20 `MORE_WORK_REMAINS` passes exhaust the existing iteration guard before a twenty-first pass.
- [x] Add observable execution tests with multiple complete and malformed/incomplete blocker blocks to prove the final complete record alone is summarized and absent valid records do not yield an invented blocked handback.
  - Added the resolver-backed `workflow/final-complete-block-routing` seam; its state-based proof selects the final valid record and rejects missing/incomplete records.
- [x] Add terminal-result tests that declare terminal steps in a non-authoritative order and prove `:terminal-outcome :step-id` projects the executed normal or blocked summary for standalone output and delegated yielded text.
  - Standalone projection is covered with the blocked terminal declared before the complete terminal; delegated yield remains.
- [x] Run focused workflow-loader and agent-session routing/execution tests for standalone `implement-task`.
  - `bb clojure:test --focus psi.agent-session.workflow-review-step-routing-test` — 13 tests, 122 assertions passed.
  - `bb clojure:test --focus psi.agent-session.workflow-migration-validation-test` — 7 tests, 49 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_review_step_routing_test.clj` — no findings.
- [x] Commit the Slice 2 workflow and proof changes without staging unrelated worktree changes.
  - `67dc1df04 ⚒ Prove blocked implementation marker failures` and the focused standalone routing proof added in this pass.

## Slice 3 — Task-lifecycle implementation gate

- [x] Insert a gate immediately after the `implement-task` delegate in `.psi/workflows/task-lifecycle.edn` that reads its yielded text and uses `workflow/exact-marker-routing` for exactly `IMPLEMENTATION_COMPLETE` and `IMPLEMENTATION_BLOCKED`.
- [x] Route only `IMPLEMENTATION_COMPLETE` to `review-task-implementation`; route `IMPLEMENTATION_BLOCKED` to a dedicated lifecycle blocked handback terminal step.
- [x] Author the lifecycle blocked handback to inspect the final complete blocker record, summarize completed work and verification, state that review and extraction did not run, and instruct the human to resolve the blocker and freshly re-invoke `task-lifecycle`.
- [x] Extend task-lifecycle loader/definition tests to prove gate placement, marker source, allowed routes, route destinations, and blocked handback contract.
  - `bb clojure:test --focus psi.workflow-loader.task-lifecycle-definitions-test` — 1 test, 56 assertions passed.
  - `clj-kondo --lint components/workflow-loader/test/psi/workflow_loader/task_lifecycle_definitions_test.clj` — no findings.
- [x] Extend observable lifecycle execution tests to prove completion reaches implementation review and blocked execution reaches the blocked handback without starting implementation review or `extract-task-knowledge`.
- [x] Add lifecycle tests proving malformed, duplicate, missing, and unsupported exported `IMPLEMENTATION_STATUS` markers fail rather than route as blocked or complete.
- [x] Run focused task-lifecycle loader and agent-session execution tests.
  - `bb clojure:test --focus psi.agent-session.workflow-task-lifecycle-implementation-gate-test` — 3 tests, 32 assertions passed.
  - `bb clojure:test --focus psi.workflow-loader.task-lifecycle-definitions-test` — 1 test, 56 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_task_lifecycle_implementation_gate_test.clj` — no findings.
- [x] Commit the Slice 3 lifecycle workflow and proof changes without staging unrelated worktree changes.
  - Committed in `e3c3e5894 ⚒ Prove blocked lifecycle implementation gate`.

## Slice 4 — Documentation and integrated verification

- [x] Update `doc/workflows.md` wherever it describes `implement-task` or the task-lifecycle implementation boundary so it documents the third pass outcome, exact blocker record, exported terminal statuses, blocked handback, fresh re-invocation, and no-review/no-extraction lifecycle gate.
- [x] Run all affected workflow-loader, routing, execution, migration-validation, and lifecycle test namespaces through the repository's focused Scry/test commands.
  - Focused routing, artifact-gate operation, migration-validation, standalone implementation, lifecycle gate, lifecycle-definition, and review-step routing tests pass.
- [x] Run `clj-kondo` on every changed Clojure test or source file and repair formatting/delimiters where required.
  - `clj-paren-repair` and `clj-kondo` passed for all changed Clojure files.
- [x] Inspect the final diff against every acceptance criterion, confirming generic runtime loop semantics and `workflow/pass-status-routing` remain unchanged unless a separately recorded defect required reconciliation.
  - The new resolver-backed validation operation is generic syntax/artifact validation; status vocabulary and branch topology remain authored. Loop and pass-status routing are unchanged.
- [x] Update `implementation.md` with concise implementation decisions, verification commands/results, and any remaining risks; mark completed checklist items accurately.
- [x] Commit the documentation and final task-artifact synchronization without staging unrelated worktree changes.
  - `4fd82c8ca ⚒ Validate blocked implementation handbacks`.

## Implementation review follow-up

- [x] Make `IMPLEMENTATION_BLOCKED` validation prove that the current `implement-pass` appended a new complete `IMPLEMENTATION_BLOCKER` record, rather than accepting a stale final-complete record from an earlier blocked attempt; add an execution-level regression test for a blocked pass that leaves an existing valid record unchanged.
  - Captures `implementation.md` before every pass and requires a new complete record after a blocked reply; checked-in workflow execution rejects an unchanged persisted record.

- [x] Add an execution-level proof using the checked-in `implement-task` definition that supplies a persisted complete `IMPLEMENTATION_BLOCKER` record, emits `PASS_STATUS: IMPLEMENTATION_BLOCKED`, and proves the validation gate runs before only `final-summary-blocked`; keep the existing missing/malformed-record failure cases observable from that same workflow route rather than only testing the operation and a synthetic topology separately.
  - `bb clojure:test --focus psi.agent-session.workflow-implementation-routing-test` — 6 tests, 54 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_implementation_routing_test.clj` and `git diff --check` — passed.

- [x] Remove the extra trailing blank line from `components/agent-session/test/psi/agent_session/workflow_implementation_routing_test.clj` so the task diff passes `git diff --check`.
- [x] Reject whitespace-only `IMPLEMENTATION_BLOCKER` field values in `workflow/final-complete-block-routing` and its parser, so the required non-empty `blocker` and `required-human-action` contract cannot validate a blank handback.
  - `bb clojure:test --focus psi.agent-session.workflow.routing-test` — 11 tests, 257 assertions passed.
  - `bb clojure:test --focus psi.agent-session.scope-question-gate-operation-test` — 8 tests, 20 assertions passed.
  - `clj-kondo --lint` and `git diff --check` — passed.

- [x] Read `implementation.md` once in `workflow/fresh-final-complete-block-routing` and use that same resolved content for both final-record and freshness validation; the current two resolver reads can validate the final record from one artifact revision and freshness from another.
  - `bb clojure:test --focus psi.agent-session.scope-question-gate-operation-test` — 9 tests, 22 assertions passed.
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/workflow/core.clj components/agent-session/test/psi/agent_session/scope_question_gate_operation_test.clj` and `git diff --check` — passed.

- [x] Add checked-in `implement-task` execution coverage for `MORE_WORK_REMAINS → IMPLEMENTATION_COMPLETE`, including its artifact-capture loop edge and the normal terminal summary projection/export, rather than proving those outcomes only through the synthetic topology; acceptance criterion 8 requires execution-routing proof for all three implementation outcomes.
  - `bb clojure:test --focus psi.agent-session.workflow-implementation-routing-test` — 7 tests, 63 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_implementation_routing_test.clj` and `git diff --check` — passed.

- [x] Add an execution-level lifecycle regression test compiled from the checked-in `.psi/workflows/task-lifecycle.edn`, proving its `IMPLEMENTATION_BLOCKED` branch reaches only the blocked handback and never starts implementation review or knowledge extraction; the current execution test uses a synthetic topology, so it cannot detect drift in the authored workflow definition.
  - `bb clojure:test --focus psi.agent-session.workflow-task-lifecycle-implementation-gate-test` — 4 tests, 40 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_task_lifecycle_implementation_gate_test.clj` and `git diff --check` — passed.

- [x] Add an integration execution test that delegates the checked-in `implement-task` workflow from the checked-in `task-lifecycle` workflow for both terminal branches, proving the executed normal/blocked terminal summary—not a synthetic child yield—is exported through delegation as the lifecycle gate's `IMPLEMENTATION_STATUS` input and selects the corresponding lifecycle branch.
  - `bb clojure:test --focus psi.agent-session.workflow-task-lifecycle-implementation-gate-test` — 5 tests, 50 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_task_lifecycle_implementation_gate_test.clj` and `git diff --check` — passed.

## Implementation review follow-up

- [x] Make fresh blocker validation enforce the implementation-pass contract's exactly-one newly appended complete `IMPLEMENTATION_BLOCKER` record. `workflow/final-complete-block-appended?` currently accepts any suffix containing a complete record, so a blocked pass that appends two complete records is accepted despite the prompt's “exactly once” requirement; add a regression test for that suffix.
  - `workflow/final-complete-block-appended?` now accepts exactly one complete appended block; parser and operation-seam regression tests reject a two-record suffix. `bb clojure:test --focus psi.agent-session.workflow.routing-test` — 12 tests, 259 assertions passed; `bb clojure:test --focus psi.agent-session.scope-question-gate-operation-test` — 9 tests, 24 assertions passed; clj-kondo and `git diff --check` passed.

- [x] Reconcile `design-steps.md` with the implemented blocker-validation seam: its still-unchecked validation/terminal-handling design follow-up is satisfied by `workflow/fresh-final-complete-block-routing`, the blocked route, and their tests. Mark it complete or restore the missing implementation work, with an accurate resolution note.
  - Marked the satisfied design follow-up complete with its implemented validation route, failure result, and existing checked-in proof coverage.

## Implementation review follow-up

- [x] Gate every remaining workflow caller of `implement-task` on its exported `IMPLEMENTATION_STATUS` before downstream work. `implement-task-in-worktree.md`, `reduce-architectural-complexity.edn`, and `reduce-incidental-complexity.edn` currently continue to their normal summary, validation, or review paths unconditionally, so an `IMPLEMENTATION_BLOCKED` handback can be misrepresented as successful work or trigger review/validation despite the explicit blocked boundary. Add branch-specific blocked handbacks and observable routing tests that prove neither downstream path starts on blocked output.
  - Added exact-marker gates and terminal blocked handbacks; routing proof covers normal-summary, validation-capture, and implementation-review downstream paths.

## Implementation review follow-up

- [x] Make the `implement-task-in-worktree` caller gate executable rather than editing an artifact the workflow loader rejects. `.psi/workflows/implement-task-in-worktree.md` is an EDN-bodied markdown migration blocker explicitly rejected by `workflow_migration_validation_test.clj` and documented as non-loadable in `doc/workflows.md`, while the new execution test uses only a synthetic topology. Migrate the wrapper to the supported multi-step `.edn` form (or otherwise resolve the loader contract), reconcile the migration inventory/docs, and add checked-in definition/execution proof that a blocked delegated `implement-task` reaches only the wrapper handback.
  - Migrated the wrapper to loadable `.edn`, removed it from the blocker inventory/docs, and proved its checked-in blocked route skips the normal summary.

- [x] Propagate the implementation terminal status through `implement-task-in-worktree` and gate its loadable caller `.psi/workflows/gh-issue-implement.edn` before `review` and `push`. The wrapper's complete and blocked summaries currently export no `IMPLEMENTATION_STATUS`, and `gh-issue-implement` unconditionally reviews and pushes the wrapper result, so a blocked implementation can still cross the outer orchestration boundary and be treated as reviewable work. Add branch-specific outer handback routing and observable proof that blocked output starts neither review nor push.
  - Wrapper summaries export branch-matching status; the outer exact-marker gate routes blocked output to a terminal handback before review, push, or label editing, proven from the checked-in definition.

## Implementation review follow-up

- [x] Make the blocked handback consume the blocker record from the same artifact snapshot that passed `workflow/fresh-final-complete-block-routing`, then propagate that validated record through caller handbacks instead of independently re-reading `implementation.md`. The current gate validates one revision, while `final-summary-blocked`, lifecycle, wrapper, and complexity-workflow prompts read the artifact again, so a concurrent or intervening edit can make the presented blocker differ from the record that authorized the blocked route. Add an observable checked-in execution proof that changes the artifact after validation and verifies every handback still reports the originally validated `blocker` and `required-human-action` values.
  - Validation is now an addressable invoke-step result; `implement-task` binds that snapshot's record into its blocked terminal export, and all direct caller handbacks preserve the exported blocker/action lines without selecting from task artifacts. Checked-in execution proof mutates `implementation.md` after validation and covers every direct caller.

## Implementation review follow-up

- [x] Enforce and prove the accepted terminal handback contract rather than only placing required lines in session prompts. The checked-in `implement-task` execution tests currently accept `"complete handback"` and `"blocked handback"` without the required `IMPLEMENTATION_STATUS` export, and the caller snapshot test accepts `"blocked caller handback"` without the validated `IMPLEMENTATION_BLOCKER` or `IMPLEMENTATION_REQUIRED_HUMAN_ACTION` lines while asserting only that those values appeared in the prompt. Make completion fail for missing, malformed, duplicate, branch-mismatched, or snapshot-mismatched terminal fields, and add observable checked-in execution tests that assert the actual standalone and delegated yielded text contains exactly the branch status and, on blocked routes, the validated blocker/action values.
  - Exact-marker routing now validates branch-owned required fields, standalone terminal judges reject invalid exports, and direct caller handbacks validate blocker/action values against the delegated snapshot; checked-in execution proof asserts accepted standalone and delegated yielded text.

## Implementation review follow-up

- [x] Enforce the blocked terminal handback contract in `.psi/workflows/gh-issue-implement.edn`. Its `implementation-blocked` step still uses `workflow/constant-routing` and does not require or export `IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED`, so it can accept a handback that omits or changes the validated blocker/action values even though its delegated wrapper output supplied them. Use deterministic exact-marker/required-field validation against the `implement` yield, export the blocked status, and add checked-in execution coverage proving invalid outer handbacks fail while accepted output preserves the validated fields and never reaches review, push, or label editing.
  - The outer blocked step now validates its exact status and snapshot-matching blocker/action fields against the wrapper yield; checked-in execution proof accepts the preserved export, rejects invalid handbacks, and confirms review, push, and label editing remain unreachable.

## Implementation review follow-up

- [x] Enforce the normal terminal handback contract in `.psi/workflows/implement-task-in-worktree.edn`. Its `summary` step instructs the session to export `IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE` but accepts any reply through `workflow/constant-routing`, so a standalone wrapper run can complete with a missing, malformed, duplicate, or branch-mismatched status despite the documented exact terminal-export contract. Use deterministic exact-marker validation and add checked-in execution coverage for accepted and rejected normal handbacks.
  - The wrapper's normal summary now exact-marker-validates only `IMPLEMENTATION_COMPLETE`; checked-in execution proof accepts and projects a valid handback and rejects missing, malformed, duplicate, and branch-mismatched status exports.

## Implementation review follow-up

- [x] Reject blocked-only `IMPLEMENTATION_BLOCKER` and `IMPLEMENTATION_REQUIRED_HUMAN_ACTION` fields from every `IMPLEMENTATION_COMPLETE` terminal handback. The normal `implement-task` and `implement-task-in-worktree` summary judges currently validate only the completion status, so they accept branch-mismatched blocker/action lines despite `doc/workflows.md` stating that branch-mismatched terminal fields fail. Extend deterministic field validation and checked-in standalone/delegated execution coverage so a clean completion export is accepted while either blocked-only field on a completion branch fails before downstream work.
  - Added authored forbidden-field labels to both completion judges and generic exact-marker enforcement; checked-in standalone and delegated execution proofs accept clean completion and reject either blocked-only field before completion/downstream work. Focused routing, implementation, lifecycle/wrapper, and migration-validation tests, clj-kondo, and `git diff --check` pass.

## Implementation review follow-up

- [x] Reconcile `munera/plan.md` with the implemented task state. Task 256 is still listed under `Not yet started` and described as `Design-only`, even though all implementation slices and review follow-ups are complete; update the orchestration entry so future agents are not directed by stale status.
  - Moved task 256 to an implemented-awaiting-closure section and removed the stale design-only description.

## Test review follow-up

- [x] Replace task-added `with-redefs` of `psi.agent-session.turn/prompt-execution-result-in!` in the implementation-routing and lifecycle execution tests with the existing injectable `:workflow-prompt-execution-result-fn` context boundary (using a nullable/state-controlled implementation), so the tests inject infrastructure state rather than mock global production behavior.
  - Both namespaces now inject deterministic responses through the context callback and rebuild the context's named workflow execution adapter from that callback; no global production Vars are redefined. Focused Scry: 20 tests, 219 assertions passed.

## Test review follow-up

- [x] Extract the duplicated `with-workflow-prompt-execution-result-fn` / `with-workflow-prompt-execution-result` fixture from `workflow_implementation_routing_test.clj` and `workflow_task_lifecycle_implementation_gate_test.clj` into shared agent-session test support, then use that single context/adapter injection seam in both namespaces. Keep response setup local to each test so the shared helper compresses infrastructure ceremony without hiding test intent.
  - Moved the context/adapter injection seam to `psi.agent-session.test-support`; both namespaces retain local response functions. Focused Scry: 20 tests, 219 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Replace the `reply-number = 4` response dispatch in `execute-checked-in-lifecycle-with-implement-task!` with a locally comprehensible prompt or step-identity match. The integration test currently assumes exactly three upstream prompt executions precede `implement-pass`, so an unrelated lifecycle-stage insertion or retry can feed an implementation status to the wrong session and fail for incidental call order rather than the blocked/completed delegation contract.
  - Response dispatch now matches the implementation-pass procedure text rather than global prompt ordinal. Focused Scry: 12 tests, 141 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Replace global callback-ordinal response dispatch in the checked-in `implement-task` execution tests with prompt/step-identity dispatch wherever the pass and terminal-summary prompts are distinguishable. In the repeat-then-complete case, count only matching implementation-pass prompts; an unrelated inserted session step must not consume a pass or summary response and make these tests fail for incidental workflow order.
  - Checked-in execution responses now match implementation-pass and branch-summary procedure text; only implementation-pass prompts advance the repeat counter. Focused Scry: 8 tests, 78 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Make the checked-in `implement-task` prompt-response dispatchers fail immediately with the unexpected prompt in the error data when no known pass or branch-summary predicate matches. Their current `cond` expressions return `nil`, turning authored-topology drift into an indirect execution failure with poor diagnostic signal; add a focused regression assertion for the fail-fast response boundary.
  - Added a shared local prompt classifier that throws with `:unexpected-prompt`; all checked-in response dispatchers use it, and focused boundary proof covers the diagnostic data. Focused Scry: 9 tests, 80 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Make the checked-in lifecycle prompt-response dispatchers fail immediately on unknown prompts instead of returning `prompt` from their fallback branches. In particular, cover `execute-checked-in-blocked-lifecycle!` and `execute-checked-in-lifecycle-with-implement-task!` with explicit expected stage/prompt classification and a focused diagnostic-boundary assertion, so an inserted or renamed authored session step cannot receive its own prompt as a plausible reply and let topology drift pass indirectly.
  - Both checked-in lifecycle helpers now classify every expected stage prompt and throw with `:unexpected-prompt` on topology drift. Focused Scry: 13 tests, 143 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Make the remaining checked-in wrapper and outer-orchestration prompt-response dispatchers fail immediately on unknown prompts instead of returning `prompt`, `summary-reply`, or a branch handback from catch-all fallbacks. Cover the `implement-task-in-worktree`, direct-caller snapshot, and `gh-issue-implement` helpers with explicit expected prompt classification and one focused diagnostic-boundary assertion, so an inserted or renamed session step cannot receive a plausible terminal reply and let authored-topology drift pass indirectly.
  - Wrapper, direct-caller, and outer-PR helpers now classify exact delegated yields and authored session prompts, throwing with `:unexpected-prompt` on topology drift. Focused Scry: 14 tests, 145 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Complete the in-progress caller-test namespace split: `workflow_implementation_callers_test.clj` references `lifecycle-definition` and `execute-lifecycle!` that remain private in `workflow_task_lifecycle_implementation_gate_test.clj`, while caller-only helpers remain unused in the lifecycle namespace. Move each fixture/helper with the tests that use it (or extract genuinely shared support), then prove both namespaces load and pass and run `clj-kondo` without unresolved symbols or unused private vars.
  - Caller-only fixtures and tests now live in `workflow_implementation_callers_test.clj`; lifecycle fixtures and invalid-export coverage remain in `workflow_task_lifecycle_implementation_gate_test.clj`. Focused Scry: callers 8 tests/93 assertions, lifecycle 6 tests/52 assertions; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Make `checked-in-implement-task-blocked-route-rejects-invalid-blockers-test` append its malformed blocker during the implementation pass, after the workflow captures the prior artifact. Its current malformed fixture exists before capture and is left unchanged, so it exercises only the missing-fresh-append path already covered by the stale-blocker test rather than proving a newly appended malformed record is rejected by the checked-in route.
  - The malformed case now starts with ordinary implementation notes and appends its incomplete record only in response to the checked-in implementation-pass prompt; the route rejects that fresh malformed suffix.
- [x] Extract the duplicated workflow execution fixtures shared by `workflow_implementation_callers_test.clj` and `workflow_task_lifecycle_implementation_gate_test.clj`—routing-operation registration, session/terminal child definition builders, checked-in workflow compilation, and run creation—into focused shared test support. Keep workflow-specific topology and response setup local so the helper compresses ceremony without hiding intent.
  - Added `workflow-implementation-test-support`; workflow-specific definitions and response classification remain local. Focused Scry: 23 tests/225 assertions passed; clj-kondo and `git diff --check` passed.

## Test review follow-up

- [x] Replace the duplicate routing-operation registration, checked-in workflow compilation, and run-creation fixtures in `workflow_implementation_routing_test.clj` with `workflow-implementation-test-support`. Keep the synthetic implementation topology and response classification local; use the same shared execution-fixture abstraction across all three blocked-workflow execution namespaces to prevent fixture drift.
  - The implementation-routing namespace now uses shared operation registration, checked-in compilation, and run creation while retaining local synthetic topology and prompt classification. Focused Scry: 9 tests/80 assertions passed; clj-kondo and `git diff --check` passed.

## Documentation review follow-up

- [x] Update the `README.md` workflow overview to state that a blocked implementation is a clean human handback, that `task-lifecycle` stops before implementation review and knowledge extraction, and that the human must resolve the recorded action and freshly re-invoke the workflow.
- [x] Update `ramora/IMPLEMENTED.md` to record the implemented three-outcome `implement-task` contract, deterministic fresh blocker-record validation, branch-specific terminal exports, and caller gates that prevent blocked implementations from reaching downstream review, validation, extraction, or publication work.
- [x] Add an `[Unreleased]` changelog entry for the user-visible `IMPLEMENTATION_BLOCKED` outcome and lifecycle/caller stop behavior. The existing entry covers only the later validated-snapshot preservation fix and does not announce the new blocked termination behavior itself.

## Code-shaper review follow-up

- [x] Validate `:required-fields-by-route` even when `:required-field-labels-by-route` is also present in `workflow/exact-marker-routing` arguments (or reject the combination explicitly). The current `cond` in `required-route-fields-errors` stops after validating source-derived labels, so malformed direct-field data can reach the parser and violate its structured invalid-argument contract; add combined-shape regression coverage proving malformed arguments return `:invalid-route-marker-args` without throwing.
  - Required-field argument forms are now validated independently and their errors accumulated. Focused Scry: 13 tests/271 assertions passed; clj-kondo and full task-range `git diff --check` passed.
- [x] Remove the extra trailing blank line from `components/agent-session/test/psi/agent_session/workflow_implementation_callers_test.clj`; the full task range currently fails `git diff --check` at line 424.
