2026-05-07

Task created.

Initial framing:
- this task exists to clean up workflow-related higher-surface residue left after the successful lower component extraction sequence
- expected focus is `agent-session` workflow entrypoints, workflow-specific runtime assembly, naming ambiguity, projection duplication, and test ownership cleanup
- lower workflow components currently presumed coherent unless review finds a specific contradiction

To record during execution:
- reviewed namespaces and their final classification
- compatibility-backfill decision and any resulting move/removal
- workflow assembly ownership decision
- workflow façade keep/remove/reframe decisions
- projection/report ownership decisions
- extension-workflow naming decision
- workflow test ownership decisions
- any explicit residual debt left behind with blocking reason

2026-05-07 — review + implementation slice 1: psi-tool compatibility removal and extension-workflow rename

Reviewed namespace classifications:
- `psi.agent-session.psi-tool-workflow` → reshape
  - kept as the workflow psi-tool entrypoint
  - removed migration-era live ctx repair and adapter backfill
  - now requires an already assembled workflow runtime ctx instead of patching missing callbacks on demand
- `psi.agent-session.context` → keep
  - review found it coherent as the composition root for workflow callback defaults and named adapter assembly
  - no extraction implemented in this slice because the highest-value residue was the downstream psi-tool compatibility repair, not the composition-root location itself
- `psi.agent-session.workflow-execution` → keep
  - thin but justified: owns session-facing canonical workflow run execution/resume entrypoints and higher execution result shaping
- `psi.agent-session.workflow-judge` → keep
  - coherent higher impure orchestration owner over lower `psi.workflow-judge` projection/routing logic
- `psi.agent-session.mutations.canonical-workflows` → keep
  - Pathom mutation surface remains distinct from lower runtime ownership and preserves its higher contract shaping
- `psi.agent-session.resolvers.workflows` → keep
  - Pathom resolver projection is intentionally EQL-specific rather than accidental duplication
- `psi.agent-session.workflows` → rename
  - current name is materially misleading after canonical deterministic workflow extraction because it suggests generic workflow ownership
  - authoritative owner renamed to `psi.agent-session.extension-workflow-runtime`
  - legacy namespace retained as a compatibility shim that simply re-exports the renamed owner
- `psi.agent-session.workflow-mutations` → rename
  - authoritative owner renamed to `psi.agent-session.extension-workflow-mutations`
  - legacy namespace retained as a compatibility shim that re-exports the renamed owner

Compatibility-backfill decision:
- removed `psi.agent-session.psi-tool-workflow` dynamic callback backfill and lazy execution-adapter assembly
- reviewed supported lifecycle evidence and found no concrete still-supported caller that should rely on psi-tool mutating an incomplete ctx at call time
- canonical context creation already assembles:
  - workflow callback defaults
  - `:execute-workflow-run-fn`
  - `:resume-and-execute-workflow-run-fn`
  - named `:workflow-execution-adapter`
- final rule after cleanup: psi-tool workflow requires an assembled workflow runtime ctx and fails explicitly with missing-key data when invoked against an incomplete ctx

Implemented code changes:
- `components/agent-session/src/psi/agent_session/psi_tool_workflow.clj`
  - deleted `find-required-fn`
  - deleted `ensure-workflow-callbacks`
  - deleted `ensure-workflow-execution-adapter`
  - added explicit `required-workflow-ctx-keys`
  - added `require-workflow-runtime-ctx!`
  - switched workflow psi-tool execution to validation of assembled ctx instead of runtime patching
- `components/agent-session/src/psi/agent_session/extension_workflow_runtime.clj`
  - introduced explicit authoritative extension-workflow runtime owner by moving the prior `workflows` implementation under a clearer name
- `components/agent-session/src/psi/agent_session/workflows.clj`
  - rewritten as a compatibility shim re-exporting `extension-workflow-runtime`
- `components/agent-session/src/psi/agent_session/extension_workflow_mutations.clj`
  - introduced explicit authoritative extension-workflow mutation owner by moving the prior `workflow-mutations` implementation under a clearer name
- `components/agent-session/src/psi/agent_session/workflow_mutations.clj`
  - rewritten as a compatibility shim re-exporting `extension-workflow-mutations`
- rewired direct authoritative callers to the renamed owners where touched in this slice:
  - `context.clj`
  - `mutations.clj`
  - `background_job_runtime.clj`
  - `introspection.clj`
  - `resolvers/extensions.clj`
  - `session_lifecycle.clj`
  - `dispatch_effects.clj`
  - selected tests and test support
- renamed extension-workflow runtime proof file to match the new authoritative namespace:
  - `components/agent-session/test/psi/agent_session/extension_workflow_runtime_test.clj`

Projection duplication review:
- no extraction implemented in this slice
- current review outcome:
  - `psi-tool-workflow` summary shaping remains psi-tool specific
  - `resolvers/workflows` remains EQL-specific
  - `mutations/canonical-workflows` remains mutation-contract specific
  - `workflow-execution` remains execution-result specific
- there may still be future consolidation opportunities for canonical workflow summaries, but this slice did not find a clearly superior shared owner that would reduce duplication without collapsing intentionally different contracts

Workflow assembly ownership review:
- kept `psi.agent-session.context` as the composition root for workflow callback defaults and named adapter assembly
- rationale: after removing psi-tool backfill, the current architecture is clearer without also extracting a new assembly namespace in the same slice
- residual question left open for follow-up review: whether workflow-specific callback assembly should eventually be factored behind a named helper inside or alongside context for readability only

Test ownership review:
- `workflow_execution_test.clj` → keep as higher integration proof
  - proves cross-component orchestration through canonical workflow execution and child-session behavior
- `workflow_execution_resume_test.clj` → keep as higher integration proof
  - proves higher resume entrypoint orchestration contract
- `mutations/canonical_workflows_test.clj` → keep as higher contract proof
  - proves Pathom mutation surface behavior rather than lower runtime internals
- `workflow_judge_test.clj` → keep as higher integration proof
  - proves higher impure judge-session orchestration around the lower judge routing logic
- extension workflow runtime proof renamed to `extension_workflow_runtime_test.clj` to align proof naming with authoritative ownership

Contract preservation review:
- preserved psi-tool workflow report shapes
- preserved canonical workflow Pathom mutation output shapes
- preserved workflow resolver attribute surfaces
- preserved workflow execution higher-surface result shapes
- preserved extension workflow mutation/result shapes while renaming only the authoritative namespace owners

Why these slices were chosen now:
- removing psi-tool compatibility backfill was the highest-value clear cleanup because it directly eliminated migration-era shim behavior instead of preserving it inside a public entrypoint
- renaming extension workflow runtime surfaces was the highest-value naming cleanup because the old names were materially misleading after the canonical workflow runtime extraction
- both changes sharpen ownership without redesigning workflow behavior

Deferred / residual debt:
- `psi.agent-session.context` workflow-specific assembly remains embedded in the composition root; reviewed as coherent for now, but could still be reshaped later for readability
- canonical workflow projection duplication across psi-tool / resolver / mutation / execution surfaces remains reviewed-but-unextracted because current duplication appears mostly contract-specific rather than clearly accidental
- legacy compatibility shim namespaces remain intentionally for transition:
  - `psi.agent-session.workflows`
  - `psi.agent-session.workflow-mutations`
  - a later cleanup can delete them once direct callers are fully migrated

Verification:
- lint:
  - `clj-kondo --lint` over touched workflow cleanup files → `0 errors, 0 warnings`
- focused tests green:
  - `psi.agent-session.extension-workflow-runtime-test`
  - `psi.agent-session.workflow-tools-test`
  - `psi.agent-session.workflow-judge-test`
  - `psi.agent-session.mutations.canonical-workflows-test`
  - `psi.agent-session.background-jobs-test`
  - result: `50 tests, 246 assertions, 0 failures`
