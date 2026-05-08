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
- bounded step-execution seam from task 124 is consumed directly via `psi.agent-session.turn-execution-contract`
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
