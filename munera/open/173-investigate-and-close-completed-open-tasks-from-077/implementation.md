# Implementation notes

## 2026-05-26

Started execution of task 173.

- Added `plan.md` to define the audit method, evidence standard, per-task disposition flow, and synchronization surfaces.
- Added `steps.md` to track the first review slice starting with task 077 and continuing in numeric order.
- Confirmed the repository currently contains open tasks from `077` upward that are not all reflected in `munera/plan.md`, so this audit needs to check both directory state and orchestration state.

### Task 077 disposition

Reviewed `077-custom-provider-string-provider-auth-normalization` against its design, plan, steps, implementation notes, current code, targeted tests, and git history.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the intended normalization fix and focused verification
- current code contains the shared `normalize-provider-id` boundary in `components/provider-auth/src/psi/provider_auth/core.clj`
- current code uses that shared normalization in prompt request shaping and runtime auth resolution
- current tests include both keyword- and string-provider coverage for request shaping and `resolve-api-key-in`
- git history contains a dedicated implementation commit: `ff6d5716 Preserve custom-provider auth for string provider ids (#71)`

Disposition:

- task 077 is materially complete and should be closed
- closure is justified by the task's own acceptance evidence, not merely by adjacent later work

### Task 105 disposition

Reviewed `105-agent-session-component-extraction-map` against its design, plan, steps, implementation notes, current extracted component surface, related child tasks, and current references from later extraction tasks.

Evidence found:

- all task `steps.md` items are checked
- the task is explicitly an architectural umbrella/mapping task rather than an implementation task
- the design and implementation notes record the candidate component map, residual `agent-session` core, extraction ordering, and child-task relationships
- the child-task outcomes named by the task are now materially landed in the repository, including extracted components such as `provider-auth`, `project-nrepl`, `shared-config`, `prompt-registry`, `skill-registry`, `command-registry`, `tool-registry`, `turn-runtime`, and `workflow-registry`
- superseded child `102` is closed, and the cited child tasks `100`, `104`, `106`, `107`, `109`, `111`, `112`, `113`, `114`, `115`, `116`, and `119` are all closed, matching the umbrella's recorded reconciliation work
- later tasks continue to cite `105` as the umbrella architectural map, so its intended framing/reference role is in active use

Disposition:

- task 105 is materially complete and should be closed
- the task's acceptance was to establish and reconcile the extraction map, not to perform every extraction itself
- later citations confirm it remains useful as a closed reference artifact rather than requiring continued open status

### Task 108 disposition

Reviewed `108-project-nrepl-testing-without-mocks` against its design, plan, steps, implementation notes, current `project-nrepl` component-local tests, and git history.

Evidence found:

- task `steps.md` remains entirely unchecked
- current component-local tests still contain multiple `with-redefs` seams in exactly the areas the task set out to reshape, including `config_test.clj`, `client_test.clj`, `attach_test.clj`, `started_test.clj`, `commands_test.clj`, and also `ops_test.clj`
- the recorded 2026-05-13 follow-up improved missing-config behavior and added focused proofs, but did not complete the broader testing-without-mocks reshaping described by the task acceptance
- there is no evidence in current repository state that the mock-style seams have been removed or converted to nullable production-owned wrappers

Disposition:

- task 108 remains open
- current evidence shows real incremental progress, but the task's own acceptance is still materially unmet

### Task 124 disposition

Reviewed `124-turn-execution-contract-extraction` against its design, plan, steps, implementation notes, current repository state, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the extracted `turn_execution_contract` boundary, the routing of workflow actor/judge execution through it, the chosen boundary decisions, and focused verification
- git history shows the implementation landed in commit `2949310e`
- the task directory exists under `munera/open/`, not `munera/closed/`

Disposition:

- task 124 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 141 disposition

Reviewed `141-workflow-child-session-non-streaming-execution` against its design, plan, steps, implementation notes, and current repository state.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the workflow `:response-mode` propagation, the lower turn-runtime non-streaming execution seam, OpenAI non-streaming support, capture shaping, and repeated focused verification
- the task describes a completed vertical slice whose acceptance is evidenced in its own notes and green focused proof runs
- despite that, the task directory still remains under `munera/open/` and is still listed in `munera/plan.md`

Disposition:

- task 141 is materially complete and should be closed
- closure is justified by its own acceptance evidence and repository state

### Task 143 disposition

Reviewed `143-workflow-session-inherit-delegating-session-preferences` against its design, plan, steps, implementation notes, and current repository state.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the root-cause inventory, persisted `:parent-session-id` fix, step-session-config precedence correction, create/run/resume propagation, delegated sub-workflow preservation, and repeated focused verification
- the task's own notes show acceptance coverage for the motivating two-session inheritance case, explicit override precedence, nil-parent compatibility fallback, and resume-path preservation
- despite that, the task directory still remains under `munera/open/`

Disposition:

- task 143 is materially complete and should be closed
- closure is justified by the task's own acceptance evidence and repository state

### Task 144 disposition

Reviewed `144-workflow-model-query-execution-fallback` against its design, plan, steps, implementation notes, and current repository state.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the chosen ranked-sequence carrier, fallback-worthy failure classification seam, workflow-local ranked iteration, exhaustion contract, shaping follow-up, and focused verification
- the task's own notes show acceptance coverage for ranked fallback success, concrete-model no-fallback behavior, terminal non-fallback failure, and empty/no-winner handling
- despite that, the task directory still remains under `munera/open/`

Disposition:

- task 144 is materially complete and should be closed
- closure is justified by the task's own acceptance evidence and repository state

### Task 146 disposition

Reviewed `146-model-scope-for-command-and-workflow-sessions` against its design, plan, steps, implementation notes, current repository state, and task-directory location.

Evidence found:

- all substantive implementation/testing `steps.md` items are checked
- the only remaining unchecked item is administrative (`Update munera/plan.md to include this task in backlog order if it should remain open after creation`)
- implementation notes record shipped command/RPC scope handling, workflow transient scoping, focused proofs, picker-path parity, runtime parity review, and verification results
- current repository state shows the task directory exists under `munera/open/`, not `munera/closed/`
- `munera/plan.md` currently does not list task `146`, so the remaining administrative checkbox is stale rather than evidence of incomplete implementation

Disposition:

- task 146 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 147 disposition

Reviewed `147-workflow-child-session-creation-contract` against its design, plan, steps, implementation notes, and current repository state.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the explicit `workflow-runtime.child-session-contract` owner, lower and higher boundary validation, attempt/judge proof ownership, realization-edge integration tests, and focused verification
- the task's own notes show acceptance coverage for malformed request/result failures, attempt forwarding invariants, judge request semantics, and real child-session realization/runtime readiness
- despite that, the task directory still remains under `munera/open/`

Disposition:

- task 147 is materially complete and should be closed
- closure is justified by the task's own acceptance evidence and repository state

### Task 148 disposition

Reviewed `148-runtime-reload-discovery-and-guidance` against its design, plan, steps, implementation notes, current docs/prompt guidance, current reload implementation surface, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the policy correction to worktree-authoritative source selection, warning-only mismatch diagnostics, removal of the temporary public attr, and focused verification
- current repository surfaces reflect the described behavior: docs and prompt guidance mention worktree-authoritative reload and mismatch warnings, and the reload implementation includes loaded-source-path vs target-source-path warning reporting
- the task directory exists under `munera/open/`, not `munera/closed/`
- `munera/plan.md` still contains stale note text claiming `141` is open, which confirms the remaining cleanup burden is plan-note hygiene rather than task-148 product work

Disposition:

- task 148 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 149 disposition

Reviewed `149-reload-fixup-inventory-and-safety` against its design, plan, steps, implementation notes, explicit inventory artifact, current reload fixup code, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- the task includes the required explicit `inventory.md` classifying reload-sensitive surfaces, statuses, severities, and preferred fixup owners
- implementation notes record the identified `breaks-psi` cases, the implemented fixups, focused proof, and updated guidance
- current repository code contains the described query-env invalidation helpers and reload refresh calls
- the task directory exists under `munera/open/`, not `munera/closed/`

Disposition:

- task 149 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 150 disposition

Reviewed `150-explicit-runtime-vs-persisted-session-graph-surface` against its design, plan, steps, implementation notes, current graph/docs guidance, and current repository state.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the explicit runtime/persisted attr split, resolver naming changes, caller/doc migrations, graph discoverability updates, and review follow-up fixes
- current repository surfaces teach the explicit names in docs and prompt/introspection guidance, and the task notes record green focused verification after the review follow-up
- despite that, the task directory still remains under `munera/open/`

Disposition:

- task 150 is materially complete and should be closed
- closure is justified by the task's own acceptance evidence and repository state

### Task 152 disposition

Reviewed `152-retry-header-aware-backoff-and-ui-rate-limit-surfacing` against its design, plan, steps, implementation notes, current retry/state/projection code surfaces, and task-directory location.

Evidence found:

- all substantive retry behavior, projection, UI, and proof `steps.md` items are checked
- the only remaining unchecked items are explicitly conditional cleanup notes (`If touched while evolving this task...`) rather than unmet acceptance work
- implementation notes record the canonical retry metadata shape, header propagation, provider-aware delay selection, projection/RPC surfacing, Emacs/TUI rendering, review follow-ups, and green focused verification
- current repository code contains the described retry normalization and display helpers (`compute-retry-metadata`, `retry-display-data`, `:provider-error/headers`, nested `:retry` projection)
- the task directory exists under `munera/open/`, not `munera/closed/`

Disposition:

- task 152 is materially complete and should be closed
- the remaining conditional cleanup checkboxes should not be interpreted as incomplete acceptance work, and the earlier task-173 note that described it as already closed was incorrect

### Task 156 disposition

Reviewed `156-tui-resume-session-discovery-follow-up` against its design, plan, steps, implementation notes, current TUI/tmux resume code surfaces, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record both the harness discovery root cause and the live resume rehydration fix, plus the re-enabled tmux scenario
- current repository code contains the described absolute launcher fix and canonical navigation rehydration path for `:select-resume-session`
- the task directory exists under `munera/open/`, not `munera/closed/`
- `munera/plan.md` still lists task 156 as open, which is consistent with directory state even though implementation notes strongly suggest it is ready for closure

Disposition:

- task 156 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 164 disposition

Reviewed `164-tui-idle-polling-elimination` against its design, plan, steps, implementation notes, current TUI runtime/update code surfaces, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the proof-first inventory, explicit refresh-boundary trigger rules, idle self-poll removal, focused verification, and review follow-ups
- current repository state reflects the described post-change shape: explicit refresh boundaries and no idle self-reschedule behavior remain the authoritative rule
- the task directory exists under `munera/open/`, not `munera/closed/`

Disposition:

- task 164 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 167 disposition

Reviewed `167-emacs-tool-details-show-full-call` against its design, plan, steps, implementation notes, current Emacs tool-detail rendering/tests, and task-directory location.

Evidence found:

- all Emacs-scoped task `steps.md` items are checked, including focused rendering edge cases and dedicated e2e coverage
- implementation notes record the scoped shift away from TUI parity, the completed Emacs raw-fallback and nil/invalid-argument coverage, canonical parsed/raw comparison, dedicated e2e harness, and CI wiring
- current repository state contains the described Emacs call-detail rendering, focused tests, and `bb emacs:tool-details:e2e` task/workflow wiring
- the task directory exists under `munera/open/`, not `munera/closed/`
- `munera/plan.md` still lists task 167 as open, which is consistent with directory state even though implementation notes strongly suggest it is ready for closure

Disposition:

- task 167 is materially complete and should be closed
- the earlier task-173 note that described it as already closed was incorrect; directory state shows this audit should close it explicitly

### Task 168 disposition

Reviewed `168-workflow-structured-output-schemas` against its design, plan, steps, implementation notes, current workflow structured-output code/docs surfaces, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the completed runtime slice, broad verification, invalid-output fail-fast fix, test-review follow-ups, single-JSON-object boundary proof, and duplicate-helper consolidation
- current repository state contains the described reusable schema ownership, structured-output runtime helpers, source-resolution contract, and aligned docs
- the task directory already exists under `munera/closed/`, not `munera/open/`
- `munera/plan.md` still lists task 168 as open, so the remaining issue is orchestration drift rather than incomplete structured-output implementation

Disposition:

- task 168 is already closed and needs no further action from this audit
- stale plan ordering should be corrected separately as backlog hygiene

### Task 170 disposition

Reviewed `170-workflow-provider-native-structured-output` against its design, plan, steps, implementation notes, current workflow/turn-runtime structured-output code surfaces, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the completed provider-neutral request wiring, bounded turn-result seam, fallback-forbidden preflight, session-step/judge failure surfaces, persisted metadata coverage, and success-path seam coverage
- current repository state contains the described `:missing-json-schema` handling, fallback-forbidden `:unsupported-structured-output` behavior, top-level bounded `:structured-output` seam, and workflow/judge focused tests
- the task directory already exists under `munera/closed/`, not `munera/open/`
- `munera/plan.md` still lists task 170 as open, so the remaining issue is orchestration drift rather than incomplete implementation

Disposition:

- task 170 is already closed and needs no further action from this audit
- stale plan ordering should be corrected separately as backlog hygiene

### Task 172 disposition

Reviewed `172-emacs-command-move-point-to-prompt-end` against its design, plan, steps, implementation notes, current Emacs command/docs/tests surfaces, and task-directory location.

Evidence found:

- all task `steps.md` items are checked
- implementation notes record the completed command, focused test coverage, user-doc updates, and repeated no-action review passes after green verification
- current repository state contains the described autoloaded command, keybinding/docs references, `user-error` behavior, helper delegation, and focused tests for point movement plus post-command send smoke
- the task directory already exists under `munera/open/`, not `munera/closed/`
- `munera/plan.md` still lists task 172 as open, which is at least consistent with directory state even if implementation notes strongly suggest readiness for closure

Disposition:

- task 172 should remain open for now in this audit because its actual directory state is still open
- this exposed an audit-method correction: earlier passes incorrectly inferred some open tasks were already closed from implementation notes and nearby evidence without first checking the authoritative `munera/open/` directory state

### Audit method correction

Rechecked actual `munera/open/` directory state after noticing inconsistencies between prior audit notes and the filesystem.

Findings:

- several tasks previously recorded in task 173 as already closed are in fact still present under `munera/open/`
- this means the earlier audit method over-trusted implementation notes and code evidence when deciding already-closed status
- directory state is authoritative for Munera open/closed classification and must be checked before recording a task as already closed

Corrective rule going forward:

- only treat a task as already closed when its directory is under `munera/closed/`
- if a task appears implemented/completed but still lives under `munera/open/`, either close it in this audit or explicitly leave it open; do not describe it as already closed
