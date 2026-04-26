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
- no active runtime execution path appears to require the compatibility compiler except run creation
- `workflow_statechart_compat.clj` currently looks removable once `workflow_runtime/create-run` is migrated and the direct compatibility test is reshaped

### `psi.agent-session.workflow-progression`

Observed call sites split into two groups.

#### A. Functions already used by canonical Phase A runtime as recording helpers

Callers in `workflow_statechart_runtime.clj`:
- `start-latest-attempt`
- `increment-iteration-count`
- `record-actor-result`
- `latest-attempt`

These are not good deletion targets as-is because the statechart runtime currently uses them on the active canonical path.

Disposition hypothesis:
- these should likely stop being reached through the mixed `workflow_progression` namespace
- final home should be the record-only/canonical side, primarily `workflow_progression_recording`
- `latest-attempt` may remain a small shared query helper if that is the simplest canonical home

#### B. Legacy/lifecycle helpers still called outside the statechart runtime

Production callers:
- `cancel-run`
  - `components/agent-session/src/psi/agent_session/psi_tool_workflow.clj`
  - `components/agent-session/src/psi/agent_session/mutations/canonical_workflows.clj`
  - current responsibility: pure run lifecycle transition to terminal cancelled state
  - disposition hypothesis: **canonicalize into `workflow_runtime.clj`** as a pure run lifecycle operation

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
  - used in `workflow_tools_test.clj`
  - used in `workflow_lifecycle_test.clj`
  - not currently found in production runtime/tool code; `resume-run` tool delegates to execute wrapper rather than this helper directly
  - current responsibility: clear blocked payload and return run to `:running`
  - disposition hypothesis: either:
    - move to `workflow_runtime.clj` as canonical lifecycle op if resume remains part of public pure run-state transitions, or
    - keep only if the public tool/mutation layer truly needs an explicit pre-execution resume helper
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

## Recommended next implementation order

1. Migrate `workflow_runtime/create-run` off `workflow_statechart_compat/compile-definition`
2. Move `cancel-run` into `workflow_runtime.clj` and repoint:
   - `psi_tool_workflow.clj`
   - `mutations/canonical_workflows.clj`
3. Decide whether `resume-blocked-run` is a canonical pure lifecycle op
   - if yes, move it to `workflow_runtime.clj`
   - if no, remove it after test/tool harness reshaping
4. Repoint `workflow_statechart_runtime.clj` to `workflow_progression_recording` for canonical helpers now imported through `workflow_progression`
5. Rewrite tests so they prove final canonical surfaces
6. Delete `workflow_statechart_compat.clj` if consumer count reaches zero
7. Shrink or delete remaining legacy control helpers in `workflow_progression.clj`
8. Update `workflow_statechart_canonical.md` to reflect the final surface map

## Current judgment

Best current interpretation:
- **full removal remains realistic** for `workflow_statechart_compat.clj`
- `workflow_progression.clj` should probably stop being a mixed surface
- some functions currently living there are not "compatibility" in substance; they are canonical record or lifecycle helpers and should move to their proper homes
- the most likely final architecture is:
  - `workflow_runtime` owns pure run lifecycle operations
  - `workflow_progression_recording` owns record/update helpers used by Phase A runtime
  - `workflow_statechart_runtime` owns execution/statechart control
  - `workflow_progression` either disappears or shrinks to a very small, explicitly named legacy compatibility seam pending complete deletion