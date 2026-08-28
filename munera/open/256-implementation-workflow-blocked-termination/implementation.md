# Implementation notes

Task created from the request to add an explicit blocked termination path to `implement-task`, analogous in intent to the task-lifecycle unresolved scope-question handback.

No implementation has started. The existing implementation loop currently recognizes only `MORE_WORK_REMAINS` and `IMPLEMENTATION_COMPLETE`; the task must preserve their behavior while adding authored `IMPLEMENTATION_BLOCKED` routing and a lifecycle boundary before implementation review.

- architectural review added 1 new design step
- ambiguity review added 2 new design steps
- inconsistency review added 1 new design step
- For the design-step resolution, inspect `.psi/workflows/implement-task.edn`, `.psi/workflows/task-lifecycle.edn`, `components/agent-session/src/psi/agent_session/workflow/routing.clj`, and `components/agent-session/src/psi/agent_session/workflow/execution.clj`; preserve authored-policy ownership, deterministic routing, and the no-generic-runtime-blocker constraint.

- 2026-08-24 design follow-up: resolved all four items from review batch `d11b08d4a..495c8f2c2`. Existing `:terminal-outcome :step-id` selects the executed terminal branch for standalone text and delegate handoff; `workflow/exact-marker-routing` accepts authored raw routes, unlike the fixed DONE/REPEAT pass-status router. Implementation must prove that projection and use `IMPLEMENTATION_STATUS` in the terminal summaries as the lifecycle delegate contract. Blocked summaries must read only the final complete `IMPLEMENTATION_BLOCKER` block in `implementation.md`; absent or malformed records are invalid output, not a reason to infer a blocker.

- no architectural review feedback
- no ambiguity review feedback
- no inconsistency review feedback
- Blocker-record validation needs an explicit deterministic seam before either blocked summary: status-only `workflow/exact-marker-routing` cannot validate `implementation.md`. Keep record syntax/policy authored, fail missing or malformed records rather than synthesizing a handback, and distinguish that failure from authored `IMPLEMENTATION_BLOCKED`; inspect `components/agent-session/src/psi/agent_session/workflow/routing.clj` and `.psi/workflows/implement-task.edn`.

- Follow-up implementation seam: retain generic routing/terminal projection; confine the new status vocabulary and branch topology to `.psi/workflows/implement-task.edn` and `task-lifecycle.edn`. Runtime touchpoints are `components/agent-session/src/psi/agent_session/workflow/routing.clj` and `workflow_execution.clj`; definition validation coverage is in `components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj`.
- ambiguity review added 1 new design step
- no inconsistency review feedback

- 2026-08-24 plan-review follow-up: the confidently identified immediately preceding review batch is `3da18f361..0d03bc83e`, with baseline `6fecb083`. Its task-scoped `steps.md` diff adds no checklist lines, so there are no attributable current unchecked follow-ups to execute; pre-existing Slice 1–4 items remain untouched.
- no new ambiguity review feedback
- no new inconsistency review feedback
- Design-step implementation: validate the final `IMPLEMENTATION_BLOCKER` record deterministically before blocked summaries run; preserve authored status/topology ownership and keep malformed or missing records distinct from a clean `IMPLEMENTATION_BLOCKED` handback. Relevant non-task files: `components/agent-session/src/psi/agent_session/workflow/routing.clj`, `.psi/workflows/implement-task.edn`, and `components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj`.

- 2026-08-24 Slice 1: the implementation-pass prompt now owns the three-status contract and requires a complete non-empty `IMPLEMENTATION_BLOCKER` append before `IMPLEMENTATION_BLOCKED`. Its definition-level test reads the real prompt and proves exact status lines, delimiter/field syntax, non-empty requirement, and record-before-status instruction. Focused Scry test and clj-kondo pass. Slice 2 must replace the runtime routing operation with authored exact-marker routing and validate the final durable record before its blocked summary.

- 2026-08-24 Slice 2 (definition/topology): `implement-task.edn` now uses authored `workflow/exact-marker-routing` for the three raw pass statuses, keeps the 20-pass repeat edge, and has explicit complete/blocked terminal summaries that each export the matching `IMPLEMENTATION_STATUS`. The blocked summary is instructed to reproduce only the last complete two-field blocker block and fail rather than invent one. Definition proof passes (7 tests, 49 assertions) and clj-kondo is clean. Execution tests and deterministic blocker-record validation remain for the next Slice 2 pass; no generic runtime code changed.

- 2026-08-24 Slice 2 (terminal routing): both explicit summaries now use constant DONE routing, so either is a true terminal branch rather than falling through declaration order. State-based execution proof declares blocked before complete and verifies each branch: blocked executes one pass, reaches only its handback, and `:terminal-outcome :step-id` projects its yielded text; repeat then completion remains green. Focused routing (13 tests/122 assertions), migration validation (7/49), and clj-kondo pass. Malformed status, repeat-limit, delegate-yield, and blocker-record-validation proofs remain.

- 2026-08-24 Slice 2 (invalid-status and bound proof): added state-based execution coverage for malformed, duplicate, and unsupported authored `PASS_STATUS` markers. Each fails at `workflow/exact-marker-routing` before either terminal summary. A 20-pass `MORE_WORK_REMAINS` sequence still fails with `:iteration-exhausted` without entering a summary. `bb clojure:test --focus psi.agent-session.workflow-review-step-routing-test` passed (14 tests, 139 assertions); clj-kondo is clean. Blocker-record validation and delegated-yield proof remain.

- 2026-08-24 Slice 2 (standalone routing proof): the untracked focused `workflow-implementation-routing-test` characterizes the three authored routes, branch-specific terminal projection despite blocked-summary declaration order, invalid marker rejection, and the twenty-pass bound using real deterministic operations and observable run state. `bb clojure:test --focus psi.agent-session.workflow-implementation-routing-test` passed (4 tests, 35 assertions). The next Slice 2 pass still needs a deterministic validation seam for blocker records; prompt instructions alone cannot reject an absent or malformed artifact record.

- 2026-08-24 Slice 3 (lifecycle topology): inserted `check-implementation-status` directly after the `implement-task` delegate. It uses generic `workflow/exact-marker-routing` over the delegate’s yielded `IMPLEMENTATION_STATUS` and routes only `IMPLEMENTATION_COMPLETE` to implementation review; `IMPLEMENTATION_BLOCKED` terminates at a dedicated handback. That handback reads only the final complete blocker block, explicitly says review/extraction did not run, and requires human resolution plus fresh lifecycle invocation. Loader proof passes (1 test, 56 assertions) and targeted clj-kondo is clean.

- 2026-08-24 Slice 3 (execution proof): added `workflow-task-lifecycle-implementation-gate-test`, a state-based nested-delegation proof using real routing/runtime operations. Completion runs review and extraction; blocked runs only the handback. Missing, malformed, duplicate, and unsupported exported `IMPLEMENTATION_STATUS` values fail at the gate before either downstream delegate. Focused test: 3 tests/32 assertions; lifecycle loader proof: 1/56; clj-kondo clean.

- 2026-08-24 blocker-record validation and documentation: added generic `workflow/final-complete-block-routing`, a resolver-backed gate whose caller authors delimiters, field prefixes, and valid route. `implement-task` now validates `implementation.md` after `IMPLEMENTATION_BLOCKED` and before its handback; the parser selects only the final syntactically complete two-field record and fails absent/incomplete records. State-based operation proof uses real task-artifact resolution; routing, migration, standalone, lifecycle, definition, and review-step focused tests passed, as did clj-kondo. `doc/workflows.md` now documents blocker storage, terminal export, and lifecycle stopping. Generic loop semantics and `workflow/pass-status-routing` remain unchanged. Committed as `4fd82c8ca ⚒ Validate blocked implementation handbacks`.

- addressed 1 review step: removed the trailing blank lines from `workflow_implementation_routing_test.clj`; `git diff --check` passes.
- added 1 step to be addressed
- addressed 1 review step: `gh-issue-implement` now validates and exports the blocked terminal status plus snapshot-matching blocker/action fields; checked-in execution proof covers accepted and invalid outputs while downstream PR work remains skipped.
- addressed 1 review step: all remaining `implement-task` callers now exact-marker-gate its exported status, route blocked output to terminal handbacks, and have observable proof that blocked output skips normal summary, validation, and implementation review.
- addressed 1 review step: checked-in `task-lifecycle` now delegates the checked-in `implement-task` in an execution proof for both terminal branches; the lifecycle gate consumes each executed terminal `IMPLEMENTATION_STATUS` and selects the matching branch.
- addressed 1 review step: fresh blocker validation resolves `implementation.md` once, then derives both final-record validity and append freshness from that same content; focused operation test, clj-kondo, and `git diff --check` pass.
- addressed 1 review step: blocker-record parsing now rejects whitespace-only field values; focused parser and resolver-backed operation tests, clj-kondo, and `git diff --check` pass.
- added 1 step to be addressed
- addressed 1 review step
- addressed 1 review step
- added 1 step to be addressed
- addressed 1 review step: capture `implementation.md` before each implementation pass and require a fresh complete blocker record after `IMPLEMENTATION_BLOCKED`; checked-in workflow execution rejects an unchanged valid prior record. Focused routing and migration-validation tests, clj-kondo, and `git diff --check` pass.
- added 1 step to be addressed
- added 2 steps to be addressed
- addressed 1 review step

- addressed 1 review step: checked-in `implement-task` execution now proves the capture → `MORE_WORK_REMAINS` loop → capture → `IMPLEMENTATION_COMPLETE` route, normal terminal projection, and skipped blocked branch; focused routing test, clj-kondo, and `git diff --check` pass.
- added 1 step to be addressed
- addressed 1 review step: compiled the checked-in `task-lifecycle.edn` and proved `IMPLEMENTATION_BLOCKED` reaches only its handback, never implementation review or knowledge extraction.
- added 1 step to be addressed
- addressed 2 review steps: fresh blocker validation now requires exactly one complete appended record, and the satisfied `design-steps.md` validation/terminal-handling follow-up is marked complete. Focused routing and operation tests, clj-kondo, and `git diff --check` pass.
- added 1 step to be addressed
- added 2 steps to be addressed
- added 1 step to be addressed
- addressed 1 review step: blocked handbacks now propagate the blocker/action from the validated artifact snapshot; checked-in execution proof covers an intervening edit and every direct caller.
- added 1 step to be addressed
- addressed 1 review step: terminal handback judges now enforce exact branch status and validated blocker/action exports; checked-in standalone and delegated execution proofs assert actual yielded text and reject invalid contracts.
- added 1 step to be addressed
- addressed 1 review step: `gh-issue-implement` now validates and exports the blocked terminal status plus snapshot-matching blocker/action fields; checked-in execution proof covers accepted and invalid outputs while downstream PR work remains skipped.
- added 1 step to be addressed
