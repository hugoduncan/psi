# Implementation

Created task only.

Initial shaping decisions:
- keep omitted-scope behavior unchanged for existing `/model <provider> <model-id>` and RPC callers
- expose explicit `session|project|user` scope rather than inventing new persistence semantics
- make workflow-owned model changes explicitly `:session` scoped so workflow execution remains transient and local to the created child session

Expected likely owners:
- command parsing/help in `psi.agent-session.commands`
- helper threading in `psi.agent-session.session-settings` and `psi.agent-session.core`
- RPC transport in `psi.rpc.session.ops`
- workflow execution adapter/runtime seam in `psi.agent-session.context` and workflow runtime fallback paths

## Implementation execution — 2026-05-12

Completed slices and commits:

1. Helper/API and RPC scope threading
- threaded optional scope through `psi.agent-session.session-settings/set-model-in!`
- exposed matching arity in `psi.agent-session.core/set-model-in!`
- extended RPC `set_model` op to accept optional `:scope`
- added RPC validation for `session|project|user`
- aligned Emacs direct setter transport call so it can carry optional scope without requiring new UI
- commit: `8c85bd27` — `⚒ 146 thread model scope through helpers and RPC`

2. `/model` grammar + help/documentation
- extended `/model` command to accept:
  - `/model`
  - `/model <provider> <model-id>`
  - `/model <provider> <model-id> <scope>`
- added explicit invalid-scope error text
- updated backend help text and Emacs help text to show `[session|project|user]`
- kept omitted-scope behavior unchanged
- commit: `dea5b195` — `⚒ 146 extend model command scope grammar`

3. Workflow-owned transient model scoping
- extended workflow execution adapter `set-session-model!` seam to carry optional scope
- changed adapter assembly in `psi.agent-session.context` to forward scope unchanged to `:session/set-model`
- changed workflow runtime attempt-session model updates to call the seam with `:session`
- updated fallback-path tests to assert explicit `:scope :session`
- added focused proof for the initial execution-session model-set helper path being explicitly session-scoped
- commit: `5c805513` — `⚒ 146 make workflow model changes session-scoped`

## Audit notes

Authoritative model-set surfaces audited during execution:
- backend command parser: `components/agent-session/src/psi/agent_session/commands.clj`
- canonical helper surface: `components/agent-session/src/psi/agent_session/session_settings.clj`
- public façade: `components/agent-session/src/psi/agent_session/core.clj`
- RPC op handler: `components/rpc/src/psi/rpc/session/ops.clj`
- Emacs direct setter: `components/emacs-ui/psi-session-commands.el`
- TUI direct picker submit path: `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj`
- RPC picker submit path: `components/rpc/src/psi/rpc/session/command_pickers.clj`
- workflow execution adapter seam: `components/agent-session/src/psi/agent_session/context.clj`
- workflow runtime model switching owner: `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
- workflow-owned judge session creator reviewed for classification: `components/agent-session/src/psi/agent_session/workflow_judge.clj`

Notable implementation choices:
- direct interactive picker submit paths remain omitted-scope/default paths in this task; they are now compatible with the same canonical helper/API contract without adding new scope-picking UI
- workflow runtime model switching now expresses transient intent explicitly at the seam instead of relying on lower defaults
- judge sessions remain governed by the same no-persistence rule if they later set models through the shared helper/API path, but no new judge-specific model selection behavior was added

## Focused proof added

Command/RPC/helper coverage:
- backend command tests for:
  - explicit session scope
  - invalid scope rejection
  - updated usage/help text
- RPC tests for:
  - explicit session scope acceptance
  - invalid scope rejection
- model dispatch tests for:
  - explicit session scope does not persist project prefs or user config
  - explicit user scope persists user config only
  - existing default/project persistence behavior retained
- Emacs transport tests for:
  - direct setter can send optional scope
  - slash command coverage updated for third-argument form

Workflow transient-scoping coverage:
- initial workflow execution-session model helper path asserts `:scope :session`
- ranked fallback model switching tests assert model-set seam calls carry `:scope :session`

## Verification — 2026-05-12

Focused suites run successfully after implementation:
- `clojure -M:test --focus psi.rpc-test` → `14 tests, 96 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.commands-test` → `50 tests, 183 assertions, 0 failures`
- `clojure -M:test --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-statechart-runtime-test --focus psi.agent-session.model-dispatch-test` → `24 tests, 174 assertions, 0 failures`

Lint:
- pre-commit `cljfmt` + `clj-kondo` passed on each implementation commit

## Remaining administrative item

- `munera/plan.md` was not updated by this implementation because the task already exists in the backlog and no closure/reordering decision has been made yet.

## Implementation follow-up execution — 2026-05-12

Reviewed the preloaded implementation-review result and executed the newly added unchecked implementation step for the initial workflow child-session setup persistence regression.

Completed:
- added end-to-end workflow execution regression coverage in `components/agent-session/test/psi/agent_session/workflow_execution_test.clj`
- new proof executes a real workflow session step with an authored concrete model and asserts:
  - child session completes with the expected concrete model
  - project shared prefs remain unchanged
  - no project-local prefs file is created
  - no user config file is written
  - no user-config persistence hook is invoked

Verification:
- targeted suites covering this task’s scope still pass:
  - `clojure -M:test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.workflow-statechart-runtime-test --focus psi.workflow-runtime.attempts-test` → `24 tests, 174 assertions, 0 failures`
- broader workflow execution focused suite remains pre-existing red on unrelated dynamic delegate failure assertions:
  - `clojure -M:test --focus psi.agent-session.workflow-execution-test` → `3 existing failures`
  - same unrelated failures also appear when run alongside the new workflow persistence proof and point at dynamic delegate failure-message expectations returning `nil`

Notes:
- left `munera/plan.md` unchecked; this is still an administrative/open-state decision rather than an implementation follow-up item.

## Design ambiguity review — pass 1 (2026-05-12)

Three actionable ambiguities found:

1. **Canonical helper/API surface boundary is underspecified for direct picker setters.** The design says the “canonical public helper/API surface used by commands and RPC can carry optional scope”, but current direct interactive setters (`components/emacs-ui/psi-session-commands.el:457`, `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj:19`) call `set_model` / `session/set-model-in!` directly outside the slash-command parser. It is ambiguous whether these surfaces must also be updated to the new canonical scope-carrying helper shape for consistency, or whether they are intentionally out of scope so long as omitted-scope compatibility holds.

2. **Workflow-owned judge sessions are not explicitly classified under the transient-model rule.** The design’s authoritative workflow rule talks about “workflow runtime creates or updates the model for a workflow-owned child session” and cites execution child sessions and ranked fallback switching, but the likely owners and examples focus on execution sessions only. `components/agent-session/src/psi/agent_session/workflow_judge.clj` also creates workflow-owned child sessions. The task should explicitly say whether judge sessions are included in the session-scoped/no-persistence rule whenever they ever gain model-setting behavior, or whether this slice is intentionally restricted to actor/execution sessions.

3. **Acceptance does not explicitly require proof for both initial and fallback workflow model paths.** The design text says the transient rule includes the initially resolved concrete model and ranked fallback switching, but the acceptance/test bullets collapse this to one workflow regression assertion. Existing workflow tests already cover fallback switching (`components/agent-session/test/psi/agent_session/workflow_statechart_runtime_test.clj`) and would not by themselves prove that the initial child-session model setup path is also non-persistent. The task should make that proof obligation explicit.

## Design ambiguity follow-up execution (2026-05-12)

Completed all newly added ambiguity design-steps by refining task artifacts only; no implementation `steps.md` items were executed.

Resolutions recorded into `design.md` and `plan.md`:
- direct interactive setters that already bypass slash-command parsing are in scope for alignment with the canonical scope-carrying helper/API contract, specifically `psi-emacs-set-model` and TUI submit handling; no new scope-picking UI is required and omitted-scope compatibility remains intact
- workflow-owned judge child sessions are classified under the same transient/no-persistence model rule if they set models through the shared helper/API path, without expanding this task into new judge-specific model-selection behaviour
- workflow persistence regression proof must explicitly cover both the initial workflow child-session concrete-model setup path and ranked fallback switching path

Evidence used for the clarifications:
- Emacs direct setter calls RPC `set_model` directly: `components/emacs-ui/psi-session-commands.el:457`
- TUI submit handling calls `session/set-model-in!` directly: `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj:19`
- workflow judge creates workflow-owned child sessions: `components/agent-session/src/psi/agent_session/workflow_judge.clj`
- existing workflow tests already name ranked fallback behaviour while initial concrete-model proof obligation was only implied: `components/agent-session/test/psi/agent_session/workflow_statechart_runtime_test.clj`, `components/workflow-step-session-config/test/psi/workflow_step_session_config/core_test.clj`

## Inconsistency review — 2026-05-12

No new actionable inconsistency feedback found across `design.md`, `plan.md`, `steps.md`, and existing `implementation.md`. The current artifacts are aligned on scope threading, direct interactive setter alignment, workflow-owned execution/judge transient scoping, and explicit proof obligations for both initial child-session setup and ranked fallback switching.

## Design-step follow-up execution — 2026-05-12

Reviewed the preloaded inconsistency-review result and task artifacts before execution. There were no newly added actionable follow-up items in `design-steps.md` from the preceding review pass, so no design-step checkboxes changed and no task-artifact refinements were required beyond recording this no-op follow-up pass.

Per request, `steps.md` implementation items were not executed.

## Ambiguity review — 2026-05-12 (follow-up)

No new actionable ambiguity feedback found across `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and the cited command/RPC/workflow code/test surfaces. Existing notes already cover the previously underspecified direct setter parity, workflow judge-session classification, and the need to prove both initial child-session model setup and ranked fallback switching remain non-persistent.

## Ambiguity design-step execution request — 2026-05-12

Reviewed the preloaded ambiguity-review result plus current `design-steps.md`, `design.md`, `plan.md`, `steps.md`, and `implementation.md` before acting. There were no newly added unchecked ambiguity follow-up items to execute: all entries in `design-steps.md` are already complete, and no additional ambiguity actions were introduced after the recorded follow-up pass.

Per request, `steps.md` implementation items were not executed. No blocking ambiguity design-step remained to record.

## Inconsistency review — 2026-05-12 (follow-up 2)

No new actionable inconsistency feedback found after re-reading `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and the cited command/RPC/workflow code+test surfaces. Existing task artifacts remain aligned on optional scope threading, direct setter parity, workflow-owned transient scoping, and explicit proof coverage for both initial child-session model setup and ranked fallback switching.

## Design-step follow-up execution — 2026-05-12 (requested no-op)

Reviewed the preloaded inconsistency-review result and current `design-steps.md`, `steps.md`, `implementation.md`, `design.md`, and `plan.md` before acting. There were no newly added actionable unchecked follow-up items in `design-steps.md`, so no design-step checkboxes changed and no task-artifact updates were needed beyond recording this requested no-op pass.

Per request, `steps.md` implementation items were not executed.

## Implementation review — 2026-05-12

One actionable implementation gap remains.

- Workflow fallback switching is covered as a persistence-sensitive path and the lower execution seam is asserted to receive `:scope :session`, but the initial workflow child-session concrete-model setup path still lacks an end-to-end persistence regression proving that creating the child session with its initial model performs no project-local or user-config writes. Current proof for that path is seam-level only (`components/workflow-runtime/test/psi/workflow_runtime/attempts_test.clj`), while the task acceptance explicitly calls out initial child-session setup and ranked fallback switching as separate persistence regression targets.

## Test review — 2026-05-12

One actionable test gap remains.

- The new workflow initial-model persistence regression currently lives in `components/agent-session/test/psi/agent_session/workflow_execution_test.clj`, but the task’s recorded focused verification does not include that namespace. `implementation.md` still lists only `psi.agent-session.model-dispatch-test`, `psi.agent-session.workflow-statechart-runtime-test`, and `psi.workflow-runtime.attempts-test` as the targeted proof set, even though the acceptance now relies on the new end-to-end workflow proof. Add the new namespace to the task’s focused verification command/results so the claimed regression target is actually exercised by the task-local verification surface.
