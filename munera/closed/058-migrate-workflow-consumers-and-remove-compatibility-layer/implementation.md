# 058 — Implementation notes

Task created from post-057 analysis.

Initial hypothesis:
- some compatibility surfaces may now be removable
- others may still be required by run creation or legacy progression/query surfaces

Start by proving actual consumers before deciding whether the final outcome is full removal or one explicit retained seam.

## Consumer inventory — initial pass

### `psi.agent-session.workflow-statechart-compat`

Direct code callers discovered:
- `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
  - current use: `compile-definition` during `create-run`
  - apparent responsibility actually needed: derive `:initial-step-id`
  - disposition hypothesis: **migrate to canonical** by using `workflow-statechart/initial-step-id` directly
- `components/agent-session/test/psi/agent_session/workflow_statechart_test.clj`
  - current use: asserts compatibility compilation and flat run-chart surface directly
  - disposition hypothesis: **test-only**; rewrite onto canonical compiler/runtime surfaces or drop if only proving transitional behavior

Namespace/doc references discovered:
- `components/agent-session/src/psi/agent_session/workflow_statechart.clj`
  - docstring still points readers at compatibility compiler surfaces
  - disposition: **docs/code-comment cleanup** after final migration decision
- `components/agent-session/src/psi/agent_session/workflow_statechart_canonical.md`
  - still lists compatibility surfaces as part of authoritative note
  - disposition: **final doc update required**

Current conclusion:
- `workflow_runtime/create-run` has now been migrated to canonical `workflow-statechart/initial-step-id`
- the direct compatibility test has been reshaped onto canonical `workflow-statechart` surfaces
- no remaining runtime or test callers of `workflow_statechart_compat.clj` are expected after final namespace deletion

### `psi.agent-session.workflow-progression`

Observed call sites split into two groups.

#### A. Functions already used by canonical Phase A runtime as recording helpers

Callers in `workflow_statechart_runtime.clj`:
- `start-latest-attempt`
- `increment-iteration-count`
- `record-actor-result`
- `latest-attempt`

These are not good deletion targets as-is because the statechart runtime currently uses them on the active canonical path.

Status:
- active Phase A runtime has now been repointed away from `workflow_progression` and onto `workflow_progression_recording` for these helpers
- remaining references are test-side only
- `latest-attempt` remains available in `workflow_progression_recording` as the canonical shared query helper

#### B. Legacy/lifecycle helpers still called outside the statechart runtime

Production callers:
- `cancel-run`
  - `components/agent-session/src/psi/agent_session/psi_tool_workflow.clj`
  - `components/agent-session/src/psi/agent_session/mutations/canonical_workflows.clj`
  - current responsibility: pure run lifecycle transition to terminal cancelled state
  - status: **done** — canonicalized into `workflow_runtime.clj` as a pure run lifecycle operation

Test-only callers or transitional helpers:
- `submit-result-envelope`
  - used in `workflow_lifecycle_test.clj`
  - used in `workflow_tools_test.clj`
  - heavily used in `workflow_progression_test.clj`
  - current role: sequential compatibility progression that validates envelope, advances next step, blocks, retries, or completes
  - disposition hypothesis: **compatibility/test seam**, not active canonical runtime execution
- `record-execution-failure`
  - used in `workflow_progression_test.clj`
  - current role: sequential compatibility control helper for execution failure → retry/fail ownership
  - disposition hypothesis: **compatibility/test seam** unless another production caller appears
- `resume-blocked-run`
  - was used in `workflow_tools_test.clj`
  - was used in `workflow_lifecycle_test.clj`
  - not found in production runtime/tool code; `resume-run` tool delegates to execute wrapper rather than this helper directly
  - current responsibility: clear blocked payload and return run to `:running`
  - status: **decision made** — canonicalized as `workflow_runtime/resume-run`; test callers have started moving to that runtime surface
- `submit-judged-result`
  - used in `workflow_progression_test.clj` only
  - current role: pre-Phase-A compatibility helper for judge routing application
  - disposition hypothesis: **delete after tests migrate**, because active canonical runtime now applies judge routing inline in `workflow_statechart_runtime.clj`

#### C. Tests importing `workflow_progression` primarily for setup or transitional proofs

- `workflow_resolvers_test.clj`
  - uses `start-latest-attempt` for setup
  - disposition hypothesis: migrate setup to canonical recording helper surface
- `workflow_lifecycle_test.clj`
  - currently proves representative behavior through compatibility progression helpers
  - disposition hypothesis: split into:
    - canonical runtime lifecycle proofs, and/or
    - explicit compatibility tests if one seam is retained
- `workflow_tools_test.clj`
  - stub executor currently simulates execution by calling `submit-result-envelope` and `resume-blocked-run`
  - disposition hypothesis: rework test harness to use final canonical lifecycle helpers rather than compatibility progression helpers where possible
- `workflow_progression_test.clj`
  - currently mixes record-only and compatibility control-flow assertions in one file
  - disposition hypothesis: split or rewrite around final surfaces

## Preliminary retained-vs-migrate classification

### Likely migrate to canonical homes

- `workflow_runtime/create-run`
  - replace compat compilation dependency with canonical initial-step derivation
- `workflow_progression/cancel-run`
  - move to `workflow_runtime` as a canonical pure run lifecycle transition
- `workflow_progression/start-latest-attempt`
- `workflow_progression/increment-iteration-count`
- `workflow_progression/record-actor-result`
- `workflow_progression/latest-attempt`
  - stop routing canonical runtime through mixed namespace; use the record-only/canonical home directly if possible

### Likely compatibility/test-only surfaces

- `workflow_statechart_compat/compile-definition`
- `workflow_statechart_compat/workflow-run-chart` and related flat run-event aliases
- `workflow_progression/submit-result-envelope`
- `workflow_progression/record-execution-failure`
- `workflow_progression/submit-judged-result`

### Needs one more explicit decision

- `workflow_progression/resume-blocked-run`
  - structurally this looks like a canonical lifecycle transition, not a sequential progression helper
  - if retained, best home is probably `workflow_runtime.clj`
  - if not retained, tests and tool seams must prove they do not need it as a public pure operation

## Proposed final canonical homes

### 1. `workflow_runtime.clj`

Best home for pure root-state workflow run lifecycle operations:
- `create-run`
- `update-run-workflow-input`
- `remove-run`
- **proposed add:** `cancel-run`
- **proposed add:** `resume-run` / `resume-blocked-run` if resume remains a public pure lifecycle transition

Why:
- these are root-state run lifecycle mutations
- they are not record-only attempt helpers
- they are used by public mutation/tool surfaces
- placing them here removes lifecycle ownership from the mixed compatibility progression namespace

### 2. `workflow_progression_recording.clj`

Best home for canonical record/update helpers used by the Phase A statechart runtime:
- `start-latest-attempt`
- `increment-iteration-count`
- `latest-attempt`
- `record-step-result`
- `record-actor-result`
- `record-attempt-execution-failure`
- `record-judge-result`
- `retry-available?` if still needed as a shared local rule helper

Why:
- these mutate attempt/run recording state without owning high-level public workflow lifecycle surfaces
- `workflow_statechart_runtime.clj` already behaves as though these are canonical helpers
- routing canonical runtime through `workflow_progression` currently hides that ownership

### 3. Delete or isolate as explicit compatibility

Likely remove after migration of tests and any helper harnesses:
- `workflow_statechart_compat.clj` entire namespace
- `workflow_progression/submit-result-envelope`
- `workflow_progression/record-execution-failure`
- `workflow_progression/submit-judged-result`

Why:
- they encode the older sequential control-flow model
- active Phase A runtime no longer appears to depend on them for real execution
- keeping them expands the number of "authoritative" workflow paths unnecessarily

## Historical implementation order

Completed migration sequence:
1. `workflow_runtime/create-run` migrated off `workflow_statechart_compat/compile-definition`
2. `cancel-run` moved into `workflow_runtime.clj` and callers repointed
3. `resume-blocked-run` decided as a canonical lifecycle operation and represented as `workflow_runtime/resume-run`
4. `workflow_statechart_runtime.clj` repointed to `workflow_progression_recording` for active Phase A helpers
5. canonical workflow-surface docs updated to reflect the new ownership split
6. remaining compatibility-oriented tests were rewritten to prove final canonical surfaces or isolated as legacy-seam tests
7. `workflow_statechart_compat.clj` was deleted once consumer count reached zero
8. remaining legacy control helpers in `workflow_progression.clj` were deleted with the namespace removal
9. verification rings were rerun and the final retained-vs-removed summary recorded

## Closure note

This compatibility-removal task is complete.

Later workflow authoring convergence work in tasks `059`–`064` built on the cleaned-up canonical runtime surface and completed the session-first workflow authoring model. That later work did not reopen the removed production compatibility layer; it sharpened the authoring/documentation surface that now sits on top of the canonical runtime ownership established here.

## Final retained-vs-removed summary

Removed from production code:
- `components/agent-session/src/psi/agent_session/workflow_statechart_compat.clj`
- `components/agent-session/src/psi/agent_session/workflow_progression.clj`

Canonical production ownership after task 058:
- `workflow_runtime` owns pure run lifecycle operations
- `workflow_progression_recording` owns record/update helpers used by Phase A runtime
- `workflow_statechart_runtime` owns execution/statechart control
- `workflow_statechart` owns canonical run-lifecycle chart and definition-order helpers

Intentional retained compatibility support:
- `components/agent-session/test/psi/agent_session/workflow_sequential_compat_test_support.clj`
  - test-only
  - retains old sequential progression semantics for compatibility-era proof coverage
  - not used by runtime/production execution paths

Verification after final cleanup:
- full unit suite green (`1422 tests, 10639 assertions, 0 failures`)
- full `bb test` green

## Current judgment

Best current interpretation:
- full removal of production compatibility namespaces is now complete
- canonical ownership is clearer in both code and docs
- remaining compatibility-era sequential proofs live only in test-only support code rather than production namespaces