2026-05-07

Task created from workflow component-extraction review.

Creation rationale:
- the workflow runtime cluster is now the strongest remaining cohesive below-dispatch extraction candidate in `agent-session`
- current runtime ownership looks like historical placement rather than true session-core ownership
- extracting the runtime core should materially reduce workflow dependence on `agent-session` while preserving `agent-session` as the higher session orchestration layer
- the extraction should consume lower seams for judge/routing, step execution, and deterministic operation runtime rather than re-owning them

Initial boundary notes:
- authoritative extracted namespace family is expected to live under `psi.workflow-runtime.*`
- runtime ownership should include execution/progression/statechart coordination and attempt/runtime context behavior
- mutations, resolvers, and `psi-tool` stay above the boundary
- this task should remain tightly focused on below-dispatch workflow runtime ownership rather than broad workflow API redesign
- do not leave compatibility shims unless implementation proves a very small temporary seam is necessary

2026-05-08 implementation

Delivered a new lower component `components/workflow-runtime/` with authoritative namespaces:
- `psi.workflow-runtime.model`
- `psi.workflow-runtime.ir`
- `psi.workflow-runtime.target-ir-compiler`
- `psi.workflow-runtime.statechart`
- `psi.workflow-runtime.source-resolution`
- `psi.workflow-runtime.core`
- `psi.workflow-runtime.progression-recording`
- `psi.workflow-runtime.attempts`
- `psi.workflow-runtime.step-prep`
- `psi.workflow-runtime.terminal-contract`
- `psi.workflow-runtime.statechart-runtime`

Rewired higher owners under `agent-session` to depend downward on that component:
- `context`, `workflow-execution`, workflow mutations/resolvers, and `psi_tool_workflow` now consume `psi.workflow-runtime.*`
- removed authoritative runtime-core ownership from `components/agent-session/src/psi/agent_session/{workflow_model,workflow_ir,workflow_target_ir_compiler,workflow_statechart,workflow_source_resolution,workflow_runtime,workflow_progression_recording,workflow_attempts,workflow_step_prep,workflow_terminal_contract,workflow_statechart_runtime}.clj`
- added root/component deps and test-path wiring for `psi/workflow-runtime`

Runtime-core membership decisions:
- `workflow_runtime.clj` → moved into runtime core as `psi.workflow-runtime.core`; it owns canonical workflow run creation/list/update/resume/cancel/remove semantics and is below public workflow entrypoints
- `workflow_step_prep.clj` → moved into the new component as `psi.workflow-runtime.step-prep`; final decision: sibling lower workflow helper inside the extracted component rather than above-boundary session orchestration, because it is runtime-facing step/session shaping consumed by workflow execution paths but not a public entrypoint owner
- `workflow_terminal_contract.clj` → moved into the new component as `psi.workflow-runtime.terminal-contract`; final decision: sibling lower workflow helper inside the extracted component because delegate/runtime terminal shaping is lower workflow-domain behavior, not session-public orchestration

Statechart-runtime decomposition decision:
- kept `workflow_statechart_runtime.clj` largely intact as `psi.workflow-runtime.statechart-runtime`
- did not split it further in this slice because the component extraction already made ownership clear without changing behavior; further decomposition remains possible follow-on cleanup, but was not required to establish the lower owner boundary

Lower-seam consumption status:
- judge/routing seam from task 123 is consumed directly: runtime orchestration still calls the higher impure `psi.agent-session.workflow-judge/execute-judge!`, while pure routing/projection stays below that in the already-extracted `psi.workflow-judge`
- bounded step-execution seam from task 124 is consumed directly via `psi.workflow-runtime.turn-execution-contract`
- deterministic operation runtime remains consumed through the already-lower deterministic runtime/registry seams

Residual dependency treatment:
- to keep `workflow-runtime` pointed downward rather than back up into session-owned workflow namespaces, `psi.workflow-runtime.statechart-runtime`, `psi.workflow-runtime.step-prep`, and `psi.workflow-runtime.attempts` now take a small callback surface from ctx for session-owned concerns:
  - `:execute-workflow-judge-fn`
  - `:resolve-workflow-step-session-config-fn`
  - `:materialize-workflow-step-session-conversation-fn`
  - `:split-workflow-step-session-conversation-fn`
  - `:get-session-data-fn`
  - `:list-context-sessions-fn`
  - `:find-skill-fn`
- `agent-session.context` and `psi_tool_workflow` now supply/backfill those callbacks
- this preserves the extracted ownership boundary without requiring a broader redesign of child-session creation or judge-session execution in the same slice

Focused proof movement:
- copied lower runtime-focused tests into `components/workflow-runtime/test/psi/workflow_runtime/`
- retained higher integration tests under `components/agent-session/test/psi/agent_session/`
- added runtime test-path wiring so the new component is exercised through top-level test aliases

Verification:
- focused tests green:
  - `clojure -M:test --focus psi.workflow-runtime.core-test --focus psi.workflow-runtime.statechart-runtime-test --focus psi.workflow-runtime.attempts-test --focus psi.workflow-runtime.step-prep-test --focus psi.workflow-runtime.source-resolution-test --focus psi.workflow-runtime.target-ir-compiler-test --focus psi.workflow-runtime.ir-test --focus psi.workflow-runtime.model-test --focus psi.workflow-runtime.progression-recording-test --focus psi.workflow-runtime.ir-runtime-adoption-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.workflow-judge-test`
  - result: `57 tests, 275 assertions, 0 failures`
- lint green:
  - `clojure -M:lint --lint components/workflow-runtime/src components/workflow-runtime/test components/agent-session/src components/agent-session/test`
  - result: `0 errors, 0 warnings`

Follow-on candidates:
- `psi.agent-session.turn-execution-contract` remains outside `workflow-runtime`; that is acceptable for this task because task 124 intentionally introduced it as a lower bounded seam, but a later slice could relocate it further downward if the project wants all workflow-bounded execution helpers co-located
- child-session creation and judge execution still originate from session-owned callbacks; further extraction would need a separate task to decide whether those session-boundary effects deserve a dedicated lower execution adapter surface

2026-05-08 review

Terse review note:
- fix required: `components/workflow-runtime/deps.edn` is missing the dependency path for `psi.agent-session.turn-execution-contract`
- follow-up: rewire step-prep callbacks in `agent-session.context` directly to `psi.workflow-runtime.step-prep/*` instead of bouncing through `psi.agent-session.workflow-execution`

Findings:
- issue: `components/workflow-runtime/deps.edn` does not declare a dependency that provides `psi.agent-session.turn-execution-contract`, even though `psi.workflow-runtime.statechart-runtime` requires that namespace directly. Top-level tests passed because the root classpath includes `components/agent-session/src`, but the extracted component metadata is incomplete and component-local use/reuse would fail. This should be fixed before closing the task, either by moving the contract to a lower component or by declaring the explicit dependency.
- shape concern: `agent-session.context` wires `:resolve-workflow-step-session-config-fn`, `:materialize-workflow-step-session-conversation-fn`, and `:split-workflow-step-session-conversation-fn` to `psi.agent-session.workflow-execution` wrapper vars even though those wrappers only delegate back down into `psi.workflow-runtime.step-prep`. This keeps behavior correct, but it is unnecessary upward bounce/indirection rather than the most direct downward dependency shape. Consider rewiring those callbacks straight to `psi.workflow-runtime.step-prep/*` in a follow-up if task scope stays closed here.

2026-05-09 follow-up execution

Addressed both review items:
- moved the bounded step execution seam fully into the extracted component as `psi.workflow-runtime.turn-execution-contract`
- deleted the old `components/agent-session/src/psi/agent_session/turn_execution_contract.clj` owner instead of leaving a compatibility shim
- rewired `psi.workflow-runtime.statechart-runtime`, `psi.agent-session.workflow-judge`, and workflow-focused tests to the new lower namespace
- changed the lower contract to call a ctx-supplied `:workflow-prompt-execution-result-fn`, so `workflow-runtime` no longer depends back upward on `psi.agent-session.turn`
- added `:workflow-prompt-execution-result-fn` to `agent-session.context`, `psi_tool_workflow` compatibility backfill, and test-support wiring
- rewired `agent-session.context` and `psi_tool_workflow` step-prep callback wiring directly to `psi.workflow-runtime.step-prep/*` instead of bouncing through `psi.agent-session.workflow-execution`

Follow-up verification:
- focused tests green:
  - `clojure -M:test --focus psi.workflow-runtime.core-test --focus psi.workflow-runtime.statechart-runtime-test --focus psi.workflow-runtime.attempts-test --focus psi.workflow-runtime.step-prep-test --focus psi.workflow-runtime.source-resolution-test --focus psi.workflow-runtime.target-ir-compiler-test --focus psi.workflow-runtime.ir-test --focus psi.workflow-runtime.model-test --focus psi.workflow-runtime.progression-recording-test --focus psi.workflow-runtime.ir-runtime-adoption-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.workflow-judge-test`
  - result: `57 tests, 275 assertions, 0 failures`
- focused execution integration re-check green:
  - `clojure -M:test --focus psi.agent-session.workflow-execution-test`
  - result: `17 tests, 83 assertions, 0 failures`
- lint green:
  - `clojure -M:lint --lint components/workflow-runtime/src components/workflow-runtime/test components/agent-session/src components/agent-session/test`
  - result: `0 errors, 0 warnings`

2026-05-09 code-shaper review

Terse review note:
- shape follow-up: `psi.workflow-runtime.statechart-runtime` remains the main complexity hotspot and should be decomposed by role

Findings:
- good: the extracted ownership boundary is now coherent; lower workflow runtime code no longer relies on hidden classpath reachability or wrapper bounce paths for the reviewed seams
- shape concern: `psi.workflow-runtime.statechart-runtime` still combines working-memory seeding, event queue handling, run projection, actor/delegate execution, judge orchestration, and statechart session lifecycle in one large namespace. This is acceptable for the extraction slice, but it is the obvious next shaping target for simplicity and local comprehensibility.

2026-05-09 decomposition follow-up execution

Decomposed `psi.workflow-runtime.statechart-runtime` by role while preserving its public façade:
- added `psi.workflow-runtime.statechart-runtime.state`
  - runtime working-memory seeding
  - configuration → run status/current-step projection
  - terminal configuration helpers
- added `psi.workflow-runtime.statechart-runtime.queue`
  - workflow event queue enqueue helpers
- added `psi.workflow-runtime.statechart-runtime.step-execution`
  - deterministic invoke result wrapping
  - session-backed actor step execution normalization
- added `psi.workflow-runtime.statechart-runtime.delegate`
  - delegated workflow target resolution and delegated run execution/result shaping
- added `psi.workflow-runtime.statechart-runtime.lifecycle`
  - process-event/drain/send loop over the statechart session
- kept `psi.workflow-runtime.statechart-runtime` as the orchestration façade and `make-workflow-actions` owner, now delegating to the smaller role-focused namespaces

Additional proof shaping:
- moved deterministic operation result wrapping proof to the narrower lower namespace `psi.workflow-runtime.statechart-runtime.step-execution`
- split new focused runtime tests under `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime/`
  - `state_test.clj`
  - `step_execution_test.clj`
  - `lifecycle_test.clj`
  - `public_test.clj`

Verification after decomposition:
- focused tests green:
  - `clojure -M:test --focus psi.workflow-runtime.core-test --focus psi.workflow-runtime.statechart-runtime.public-test --focus psi.workflow-runtime.statechart-runtime.state-test --focus psi.workflow-runtime.statechart-runtime.step-execution-test --focus psi.workflow-runtime.statechart-runtime.lifecycle-test --focus psi.workflow-runtime.attempts-test --focus psi.workflow-runtime.step-prep-test --focus psi.workflow-runtime.source-resolution-test --focus psi.workflow-runtime.target-ir-compiler-test --focus psi.workflow-runtime.ir-test --focus psi.workflow-runtime.model-test --focus psi.workflow-runtime.progression-recording-test --focus psi.workflow-runtime.ir-runtime-adoption-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.workflow-judge-test --focus psi.agent-session.deterministic-operation-registry-test`
  - result: `18 tests, 85 assertions, 0 failures`
- lint green:
  - `clojure -M:lint --lint components/workflow-runtime/src components/workflow-runtime/test components/agent-session/src components/agent-session/test`
  - result: `0 errors, 0 warnings`

2026-05-09 test review

Terse review note:
- test-shape follow-up: runtime decomposition tests still have duplicated/unclear proof ownership; narrow or remove overlapping façade-vs-lower tests and strengthen lifecycle-owned behavior proofs

Findings:
- shape concern: `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime/public_test.clj` still overlaps with lower split role tests, especially `create-working-memory-test`, so proof ownership is not yet crisp after the decomposition
- shape concern: `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime_test.clj` still exists alongside the split directory tests, which keeps the old pre-split proof surface around and makes test topology harder to read
- coverage concern: `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime/lifecycle_test.clj` only proves delegation of `send-and-drain!`; it does not yet prove the owned lifecycle behaviors that matter most: queued-event draining order, terminal tail discard, and overflow safety-bound failure

2026-05-09 test follow-up execution

Addressed the reviewed test-shape items:
- removed overlapping runtime test surfaces:
  - deleted `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime_test.clj`
  - deleted `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime/public_test.clj`
- kept lower role-focused proofs with one clearer owner each:
  - `state_test.clj` for working-memory/state helpers
  - `step_execution_test.clj` for invoke result wrapping
  - `lifecycle_test.clj` for lifecycle/drain behavior
- repointed the remaining agent-session deterministic operation wrapper proof to the narrower extracted owner `psi.workflow-runtime.statechart-runtime.step-execution`
- strengthened `lifecycle_test.clj` with owned-behavior proofs for:
  - queued-event draining order
  - terminal tail discard
  - `max-drain-events` overflow failure
- retained higher execution façade coverage in `psi.agent-session.workflow-execution-resume-test`

Verification after test reshaping:
- added `components/workflow-runtime/test` to top-level Kaocha `tests.edn` unit/integration `:test-paths`
- added `components/workflow-runtime/src` to top-level Kaocha `tests.edn` unit/integration `:source-paths`
- focused lifecycle/runtime/adapter checks green through the top-level Kaocha config:
  - `clojure -M:test --focus psi.workflow-runtime.statechart-runtime.state-test --focus psi.workflow-runtime.statechart-runtime.step-execution-test --focus psi.workflow-runtime.statechart-runtime.lifecycle-test --focus psi.agent-session.workflow-execution-resume-test --focus psi.agent-session.deterministic-operation-registry-test`
  - result: `8 tests, 26 assertions, 0 failures`
