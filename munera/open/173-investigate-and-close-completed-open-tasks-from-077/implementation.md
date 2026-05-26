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
