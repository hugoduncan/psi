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
- the task directory already exists under `munera/closed/`, not `munera/open/`

Disposition:

- task 124 is already closed and needs no further action from this audit
- this review also confirms the audit must inspect directory state directly rather than relying only on `munera/plan.md` or memory notes

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
- current repository state shows the task directory already exists under `munera/closed/`, not `munera/open/`
- `munera/plan.md` currently does not list task `146`, so the remaining administrative checkbox is stale rather than evidence of incomplete implementation

Disposition:

- task 146 is already closed and needs no further action from this audit
- the stale administrative unchecked step should not be interpreted as incomplete product work

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
- the task directory already exists under `munera/closed/`, not `munera/open/`
- `munera/plan.md` still contains stale note text claiming `141` is open, which confirms the remaining cleanup burden is plan-note hygiene rather than task-148 product work

Disposition:

- task 148 is already closed and needs no further action from this audit
- related stale plan notes should be cleaned up separately as orchestration hygiene
