# 057 — Plan

## Approach

Bottom-up, test-first. Each slice proves one architectural layer before the next builds on it. The existing Phase B tests serve as the behavioral specification — Phase A must produce identical observable behavior.

The key constraint: fulcrologic statecharts `process-event!` is synchronous. Entry actions that call `prompt-in!` (blocking) can synchronously send completion events back into the chart. This means the entire execution cascade is a single synchronous call chain rooted in the initial `:workflow/start` event.

## Risks

- **Synchronous cascade**: entry actions that send events create nested `process-event!` calls. The fulcrologic statecharts library may or may not support re-entrant event processing. **Must verify in slice 1** with a minimal test before committing to the architecture.
- **Working memory mutation during processing**: if entry actions need to update the working-memory data model (e.g., to record which session was created), this must happen between event processings, not during. The `turn_statechart.clj` pattern uses an external atom for mutable state — follow the same approach.
- **Blocked state representation**: the current `:blocked` status is a workflow-run-level concept. The statechart needs a representation that allows the chart to pause and resume.

## Slice order

### Slice 1 — Verify re-entrant event processing + chart compiler skeleton

**Goal**: Prove that fulcrologic statecharts support the synchronous cascade pattern, and build the chart compiler skeleton.

Verify:
- Create a minimal 2-state chart where the entry action of state B sends an event that transitions to state C
- Confirm `process-event!` handles this correctly (state ends up in C, not stuck in B)
- If re-entrant processing doesn't work, design an alternative (external event queue drained in a loop)

Chart compiler:
- New function `compile-hierarchical-chart` in `workflow_statechart.clj`
- Input: workflow definition (same shape as today)
- Output: fulcrologic statechart definition with per-step states
- For non-judged steps: leaf states with `:actor/done` → next step transitions
- For judged steps: compound states with `.acting` and `.judging` sub-states
- Terminal states: `:completed`, `:failed`, `:cancelled`
- Initial state: `:pending` with `:workflow/start` → first step

Test: compile a 3-step definition (plan/build/review with judge on review), verify the chart structure has the expected states and transitions. Verify the re-entrant cascade works.

### Slice 2 — Actions-fn and workflow turn context

**Goal**: Build the actions dispatch model and the workflow execution context.

- `create-workflow-context` — creates statechart env, session, working-memory with context data + actions-fn atom
- `make-workflow-actions` — creates an actions-fn that dispatches on action keywords:
  - `:step/enter` — increment iteration count, create actor session, prompt, send `:actor/done` or `:actor/failed`
  - `:step/record-result` — record actor output onto workflow-run
  - `:judge/enter` — project, create judge session, prompt, evaluate routing, send signal event
  - `:judge/record` — record judge result onto attempt
  - `:judge/retry` — inject feedback, re-prompt, re-evaluate
  - `:terminal/record` — record terminal outcome
- Wire script elements in the chart to call the actions-fn

Test: with a mock actions-fn that records calls, verify that starting the chart and sending `:workflow/start` triggers the expected action sequence for a 2-step linear workflow.

### Slice 3 — Leaf step execution through statechart

**Goal**: Non-judged steps execute through statechart entry actions.

- Wire `make-workflow-actions` with real session creation and prompting
- Entry action for leaf step: `increment-iteration-count` → `create-step-attempt-session!` → `prompt-in!` → classify result → send `:actor/done` or `:actor/failed`
- Exit action: `record-actor-result` or `submit-result-envelope`
- New `execute-run!` implementation: create context → start chart → send `:workflow/start` → return result

Test: execute a 2-step linear workflow (plan→build) through the statechart. Verify same observable behavior as Phase B: step-runs populated, accepted-results recorded, status :completed.

### Slice 4 — Compound step execution (judge)

**Goal**: Judged steps execute through compound statechart states.

- `.acting` sub-state entry action: same as leaf step entry
- `.acting` → `.judging` transition on `:actor/done`
- `.judging` entry action: project actor session, create judge session, prompt, evaluate routing
- Signal transitions with guards for iteration limits
- Judge retry as internal transition (no exit/entry)

Test: execute a 3-step workflow with judge loop (plan→build→review→REVISE→build→review→APPROVED). Verify same observable behavior as Phase B.

### Slice 5 — Guard functions and iteration limits

**Goal**: Guards evaluate routing conditions correctly.

- Guard for iteration limit: check target step's iteration count against directive's `:max-iterations`
- Guard for retry availability: check attempt count against retry policy
- Guards are pure functions of statechart context

Test: iteration exhaustion causes `:failed`. Retry-available guard allows re-entry. Retry-exhausted guard causes `:failed`.

### Slice 6 — Blocked/resume semantics

**Goal**: Blocked runs pause the statechart and resume correctly.

- `:blocked` outcome from actor → chart enters a blocked sub-state
- `:workflow/resume` event → chart re-enters the step state for a new attempt
- Resume creates a fresh attempt (same as today)

Test: blocked run pauses, resume continues execution.

### Slice 7 — Cancel and error handling

**Goal**: Cancel and error paths work through the statechart.

- `:workflow/cancel` transitions from any non-terminal state to `:cancelled`
- Exception in entry action → catch → send `:actor/failed`
- Terminal state entry actions record outcome

Test: cancel from various states. Exception during execution records failure.

### Slice 8 — Test migration and cleanup

**Goal**: All existing tests pass, dead code removed.

- Migrate existing `workflow_execution_test.clj` tests to drive the new statechart-based execution
- Remove the imperative loop code
- Remove `execute-current-step!` (absorbed into actions-fn)
- Remove `step-result-map` helper
- Verify full suite green
- Update `compile-definition` to produce the hierarchical chart by default

## Decisions

- **Actions-fn pattern**: follow `turn_statechart.clj` — single callback dispatched from `ele/script` elements. External atom for mutable state (workflow-run map in ctx state atom).
- **Event cascade vs event queue**: prefer synchronous cascade if fulcrologic supports it. Fall back to external event queue if not. Verified in slice 1.
- **Workflow-run state synchronization**: actions-fn writes to both statechart context and workflow-run atom (Option A from design). Statechart context is authoritative for execution flow; run map is authoritative for introspection/persistence.
- **Chart compilation**: `compile-definition` produces the hierarchical chart. The flat status-tracker chart is retained only if needed for backward compatibility (unlikely).
- **Progression functions survive**: `increment-iteration-count`, `record-actor-result`, `submit-judged-result`, `record-execution-failure` continue to exist as pure state-update functions called by the actions-fn. They are not removed — only the imperative orchestration that called them is replaced.
