2026-05-08

Implemented extraction slice.

What changed
- introduced new workflow-facing bounded execution namespace:
  - `components/agent-session/src/psi/agent_session/turn_execution_contract.clj`
- routed workflow actor-step execution in `workflow_statechart_runtime.clj` through that contract instead of directly through `psi.agent-session.turn`
- routed impure judge execution in `workflow_judge.clj` through the same contract instead of directly through `psi.agent-session.turn`
- moved canonical assistant-text extraction and execution-failure normalization into the new boundary
- preserved existing workflow-specific shaping, child-session creation, routing interpretation, and judge retry orchestration above the boundary

Boundary decisions recorded

Shared contract vs shared substrate
- chose one shared bounded execution contract used directly by both actor and judge callers
- exposed thin entrypoints:
  - `execute-actor-turn!`
  - `execute-judge-turn!`
- both currently delegate to shared `execute-session-turn!`
- this keeps the semantic contract shared while leaving room for later actor/judge-specific substrate growth if needed

Canonical execution-result shape
- success result:
  - `{:status :ok
      :session-id ...
      :turn-outcome ...
      :assistant-message ...
      :assistant-text ...
      :execution-result ...}`
- failure result:
  - same base keys plus
  - `:status :error`
  - `:failure {:message ...
               :stop-reason ...?
               :turn-outcome ...?
               :session-id ...}`
- workflow runtime now consumes canonical direct bounded results from this contract rather than reconstructing meaning from journal/transcript state

Execution-session creation mode
- chose caller-supplied execution session id
- workflow-specific child-session creation/binding remains above the boundary:
  - actor step attempt session creation stays in `workflow-attempts`
  - judge child-session creation stays in `workflow_judge`
- this preserves the rule that workflow-specific session shaping stays outside the extracted contract while still hiding turn-result normalization behind the lower seam

Actor-step boundary start
- lower boundary starts once workflow runtime already has:
  - execution session id
  - final prompt text
- workflow-specific responsibilities remaining outside:
  - step session-config resolution
  - contribution/conversation materialization
  - preload vs prompt splitting
  - attempt/session creation

Judge-session reuse across retries
- judge retry orchestration remains above the contract in `workflow_judge`
- reuse happens by invoking repeated `execute-judge-turn!` calls against the same previously created `judge-sid`
- this preserves same-session retries without re-exposing lower prompt execution details to workflow runtime

Lower-boundary home
- introduced a new lower execution-boundary namespace inside the existing `agent-session` component rather than expanding `turn-runtime`
- reason:
  - the extracted seam still legitimately depends on `psi.agent-session.turn` as the authoritative execution path today
  - pushing this first slice into `turn-runtime` would have prematurely mixed workflow-facing bounded caller semantics with lower generic turn runtime ownership
  - the new namespace creates the needed dependency seam now, while leaving a later move into a lower component possible once workflow runtime extraction sharpens ownership further

Dependency direction after this slice
- `workflow_statechart_runtime` no longer depends directly on high-level `psi.agent-session.turn` for bounded actor execution
- `workflow_judge` no longer depends directly on high-level `psi.agent-session.turn` for bounded judge execution
- both now depend on `psi.agent-session.turn-execution-contract`
- journal/transcript reread is still available for audit/history but is no longer the semantic bounded caller contract for these workflow paths

Preserved out-of-scope boundaries
- no redesign of `:invoke`
- no redesign of `:delegate`
- no redesign of workflow routing/progression
- no redesign of transcript/UI publication
- no persistence redesign
- no mutation/resolver/`psi-tool` changes

Verification
- lint green for changed sources
- focused tests green:
  - `psi.agent-session.workflow-judge-test`
  - `psi.agent-session.workflow-statechart-runtime-test`
  - `psi.agent-session.workflow-execution-test`
  - `psi.agent-session.workflow-lifecycle-test`
  - `psi.agent-session.workflow-ir-runtime-adoption-test`
  - `psi.agent-session.workflow-invoke-runtime-test`
  - `psi.agent-session.workflow-execution-terminal-contract-test`
  - `psi.agent-session.workflow-delegate-example-execution-test`

Follow-on implication
- task 125 can now target workflow runtime extraction against `turn-execution-contract` rather than against direct `turn` usage for session-backed actor/judge execution

Review note
- implementation review passed against task intent and architecture
- follow-up completed: removed unused `:execution-result` retention from workflow pending actor state and added focused proof that successful actor pending state no longer carries that internal execution detail

Code-shaper review note
- code shape is good overall: the new contract is small, coherent, and improves ownership boundaries
- follow-up completed:
  - documented `execute-actor-turn!` and `execute-judge-turn!` as intentional semantic aliases over `execute-session-turn!`
  - extracted `prompt-execution-result` so turn invocation selection is separate from bounded result normalization while keeping the boundary minimal
