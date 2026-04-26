# 057 — Implementation notes

## Provenance

Continuation of 056 Phase A architecture. Design derived from the statechart ↔ workflow correspondence established in 056's design document and tightened to align with it explicitly.

## Resolved ambiguities

The 057 task now makes these implementation-shaping decisions explicit:

- Every executable step compiles to canonical step-local `.acting` and `.blocked` states; judged steps additionally compile `.judging`.
- Only actor execution can block in Phase A. Judge execution does not block.
- Entry actions execute work; acting/judging exits record success/failure/judge data; blocked entry records blocked data.
- Working memory is authoritative for execution control; the workflow-run atom is its projected external view.
- `workflow-run[:status]` is derived from active chart state after each processed event.
- Judge routing uses a static `:judge/signal` event plus payload guards rather than dynamic event names.
- Entering `.acting` allocates a fresh attempt id; `.judging` shares that attempt; actor retry/resume allocate fresh attempts; judge retry stays on the same attempt and same judge session.
- The event queue is FIFO; terminal entry discards queued tail events; blocked is represented by quiescence in a step-local `.blocked` state.
- Phase B helper functions may be reused only as record/update substrate. The chart is the sole owner of control flow.

## 2026-04-26 — Slice 1 landed

Implemented the first Phase A slice in `workflow_statechart.clj` and proved it with focused tests in `workflow_hierarchical_chart_test.clj`.

### What changed

- `compile-hierarchical-chart` now emits the canonical Phase A step shell:
  - every executable step gets `.acting`
  - every executable step gets `.blocked`
  - judged steps additionally get `.judging`
- `:workflow/start` now targets the first step's `.acting` state directly.
- implicit linear advance now targets the next step's `.acting` state directly.
- actor blocking now has explicit chart support through `:actor/blocked -> .blocked` and `:workflow/resume -> .acting`.
- judged routing transitions now compile exhaustion behavior explicitly:
  - matching signal within limit routes to the requested target
  - matching signal at/exceeding limit routes to `:failed`
  - this avoids quiescing forever in `.judging` on exhausted loop signals
- action script return values are now explicitly discarded so test-side tracing values do not get interpreted as working-memory operations by fulcrologic statecharts.

### Test harness decisions

The focused hierarchical-chart tests now model the intended FIFO event-pump pattern rather than re-entrant dispatch:

- event queue entries carry both `:event` and optional `:data`
- `process-event` forwards event payload into both the flat data model and the statechart event object
- iteration-count snapshots are injected into queued events so guard evaluation sees the same working-memory-style snapshot the design expects

### Slice 1 proofs now covered

- linear workflows enter canonical `.acting` states
- judged workflows compile and execute through `.judging`
- blocked execution reaches canonical step-local `.blocked` states
- resume targets the same step's `.acting`
- linear success, retry, failure, cancel, and judge-loop scenarios still work in focused tests
- iteration exhaustion now fails deterministically instead of hanging in `.judging`

### Focused validation

Verified with direct clojure.test execution under the test-paths alias:

- `psi.agent-session.workflow-statechart-test`
- `psi.agent-session.workflow-hierarchical-chart-test`

Result: both focused suites green.

## 2026-04-26 — Slice 2 landed

Implemented the Phase A runtime scaffolding in a new namespace:

- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`

This slice establishes the execution envelope around the hierarchical chart without yet moving full actor/judge execution out of the old imperative substrate.

### What changed

#### 1. Authoritative working-memory model

Added `create-working-memory`, which seeds an authoritative execution map containing:

- workflow identity
- parent session identity
- workflow input
- accepted step outputs snapshot
- iteration counts snapshot
- current step id
- blocked-step id
- attempt ids
- attempt counts
- actor retry limits
- judge/session/result placeholders for later slices

This working memory is stored in an atom and snapshotted into the flat statechart data model at event-processing boundaries.

#### 2. Workflow statechart runtime context

Added `create-workflow-context`, which builds:

- isolated statechart env with the hierarchical workflow chart registered
- statechart session id
- authoritative `working-memory*`
- FIFO `event-queue*`
- workflow-specific `actions-fn`
- initial started workflow working memory (`:pending` chart configuration)

This is the runtime envelope planned in Slice 2.

#### 3. Authority split made concrete

Implemented the intended authority rules:

- **working memory** is authoritative for execution-local control data
- **workflow-run atom projection** remains authoritative for external introspection
- guards/actions read from the statechart’s flat snapshot, which is refreshed from authoritative working memory before event processing
- public run `:status` and `:current-step-id` are projected from active chart configuration via `sync-run-projection!`

#### 4. Logical current-step projection

Added:

- `step-id-from-configuration`
- `run-status-from-configuration`
- `sync-run-projection!`

Key rule now enforced:

- `current-step-id` projects the logical step id (`plan`, `build`, `review`), not the leaf substate id (`plan.acting`, `plan.blocked`, `review.judging`)

#### 5. Attempt identity semantics

Implemented and proved the Slice 2 identity rules:

- entering `.acting` allocates a fresh attempt id
- `.judging` preserves that same attempt id
- resume back into `.acting` allocates a fresh attempt id
- retry availability is derived from authoritative attempt counts + max-attempts budget in working memory

This slice does **not** yet wire full actor failure progression semantics into the runtime; instead it establishes the attempt accounting substrate the later slices will consume.

#### 6. Event-pump helpers in runtime namespace

Added:

- `process-event!`
- `drain-events!`
- `send-and-drain!`

These mirror the tested FIFO statechart event-pump style and now live in runtime code instead of only in focused test scaffolding.

### Test coverage added

New focused suite:

- `components/agent-session/test/psi/agent_session/workflow_statechart_runtime_test.clj`

It proves:

- working-memory seed shape matches the canonical workflow run
- workflow context starts in `:pending`
- `.acting` entry allocates an attempt, increments iteration, and projects `:running`
- resume from `.blocked` allocates a fresh attempt id
- `.judging` preserves the same attempt id
- blocked entry records authoritative blocked-step metadata and projects blocked public status
- status/current-step projection is derived from active chart configuration rather than invented independently
- retry availability reflects authoritative attempt-count budget

### Focused validation

Verified green with direct clojure.test execution under the test-paths alias:

- `psi.agent-session.workflow-statechart-test`
- `psi.agent-session.workflow-hierarchical-chart-test`
- `psi.agent-session.workflow-statechart-runtime-test`

Result: all focused workflow Phase A suites green.

## 2026-04-26 — Slice 3 landed

Implemented linear-step execution ownership in the Phase A runtime.

### What changed

#### 1. `.acting` entry now performs real linear-step execution

`workflow_statechart_runtime/make-workflow-actions` now makes `:step/enter` do real work for linear execution scaffolding:

- resolve the current step prompt/config via existing `workflow_execution` helpers
- create the workflow attempt child session
- append attempt state to the workflow run projection
- increment iteration count in both working memory and projected run state
- prompt the child session
- classify the outcome
- buffer the transient actor result into authoritative working memory
- enqueue one of:
  - `:actor/done`
  - `:actor/failed`
  - `:actor/blocked`

This establishes the Slice 3 rule that entry owns execution and transient result buffering.

#### 2. Explicit recording ownership moved into transitions

The hierarchical chart now wires explicit recording ownership into transition content:

- `:actor/done` transitions dispatch `:step/record-result`
- `:actor/failed` transitions dispatch `:step/record-failure`
- `:actor/blocked` still records blocked ownership on `.blocked` entry via `:step/block`

This matches the task design’s ownership split.

#### 3. Runtime action handlers now record success/failure explicitly

Added action handling for:

- `:step/record-result`
  - consumes `:pending-actor-result`
  - calls `workflow-progression/submit-result-envelope`
  - updates working-memory `:step-outputs`
  - clears the transient pending buffer
- `:step/record-failure`
  - consumes `:pending-actor-result`
  - calls `workflow-progression/record-execution-failure`
  - clears the transient pending buffer

Blocked outcomes remain owned by `.blocked` entry, not by acting exit.

#### 4. Shared message classification helpers moved into runtime scaffolding

Added local runtime helpers for:

- assistant text extraction
- error message extraction
- assistant turn classification
- failure payload shaping

This lets Phase A runtime classify turn outcomes directly while reusing existing prompt-control / prompt-recording infrastructure.

### Test coverage added

Extended `workflow_statechart_runtime_test.clj` with linear execution ownership proofs:

- successful linear actor completion records accepted result and terminal/projected outcome correctly for a single-step workflow
- erroring linear actor completion records failure metadata and terminal failure state correctly
- pending actor result buffer is cleared after recording

These tests use focused `with-redefs` around prompt-control to keep the slice deterministic and local.

### Focused validation

Verified green with direct clojure.test execution under the test-paths alias:

- `psi.agent-session.workflow-statechart-test`
- `psi.agent-session.workflow-hierarchical-chart-test`
- `psi.agent-session.workflow-statechart-runtime-test`

Result: all focused workflow Phase A suites remain green after linear execution ownership landed.

## 2026-04-26 — Slice 4 landed

Implemented judged compound-step execution in the Phase A runtime.

### What changed

#### 1. Judged actor success now records actor output without advancing

`workflow_statechart_runtime/:step/record-result` now detects whether the current step has a judge:

- non-judged step → `workflow-progression/submit-result-envelope`
- judged step → `workflow-progression/record-actor-result`

This preserves the Slice 4 rule that actor success on judged steps records accepted actor output but does **not** own routing.

#### 2. `.judging` entry now performs real judge execution

`workflow_statechart_runtime/:judge/enter` now:

- resolves judge spec + routing table from the effective workflow definition
- uses the same actor attempt’s execution session as judge input
- calls `workflow-judge/execute-judge!`
- stores the result in authoritative working memory as:
  - `:pending-judge-result`
  - `:pending-routing`
  - `:judge-results[step-id]`
- records judge session identity into working memory session tracking
- enqueues either:
  - `:judge/signal` when routing matched
  - `:judge/no-match` when the judge exhausted into no-match

This establishes the Slice 4 rule that judging is entry-owned work and that the transition event is emitted from judged execution.

#### 3. Judged transitions now own verdict recording

Added runtime action handling for:

- `:judge/record`
  - consumes `:pending-judge-result`
  - applies `workflow-progression/submit-judged-result`
  - clears `:pending-judge-result` and `:pending-routing`

On the chart side, judged routing transitions now dispatch `:judge/record` on matched-signal routes. The exhausted-to-failed signal route deliberately does **not** record through the same transition wrapper, matching the current compiled transition shape.

#### 4. Same-attempt judging preserved

The judged path continues to preserve the same attempt id allocated on `.acting` entry:

- `.acting` creates the attempt id
- actor success records on that attempt
- `.judging` records judge metadata on that same attempt

This is now proven by focused runtime tests instead of only by conceptual design.

### Test coverage added

Extended `workflow_statechart_runtime_test.clj` with judged execution proofs:

- judge APPROVED records judge metadata and completes the run
- judge REVISE routes back to `build`
- judge result buffers are cleared after recording
- judge session id is written onto the attempt
- review attempt identity persists through judging

The tests use focused prompt-control stubs to simulate:

- actor assistant result
- judge assistant verdict

without invoking real providers.

### Focused validation

Verified green with direct clojure.test execution under the test-paths alias:

- `psi.agent-session.workflow-statechart-test`
- `psi.agent-session.workflow-hierarchical-chart-test`
- `psi.agent-session.workflow-statechart-runtime-test`

Result: all focused workflow Phase A suites remain green after judged execution landed.

## 2026-04-26 — Slice 5 landed

Implemented guard-purity cleanup so routing and retry behavior is now determined by working-memory snapshots rather than callback logic.

### What changed

#### 1. Actor retry guard is now pure

In `workflow_statechart.clj`, actor retry transitions no longer ask `actions-fn` whether retry is available.

Instead they now use a pure compiled guard:

- `actor-retry-available-guard`

This guard reads only the flat working-memory snapshot keys:

- `[:attempt-counts step-id]`
- `[:actor-retry-limits step-id]`

So actor retry behavior is now fully chart-local and snapshot-driven.

#### 2. Existing judge routing guards were kept snapshot-based

The earlier compiled routing guards already read only:

- `:signal`
- `:iteration-counts`
- compiled static metadata (`step-order`, routing target, max-iterations)

Slice 5 kept that design and added focused proof coverage for it.

#### 3. Focused tests no longer rely on `:retry-available?` callback behavior

The hierarchical chart tests were cleaned so they no longer encode retry behavior through callback answers. This removes the last test-only illusion that retry routing depends on side-effect dispatch.

### New focused proofs

Added a new suite:

- `components/agent-session/test/psi/agent_session/workflow_guard_purity_test.clj`

It proves:

- actor retry path is selected from working-memory snapshot attempt counts + max-attempts only
- actor retry exhaustion reaches `:failed` without consulting callback state
- judge REVISE routing uses working-memory `:iteration-counts` snapshot to choose:
  - route/re-enter when under the limit
  - fail when at/exceeding the limit

These tests drive the compiled chart directly and intentionally omit action callbacks as decision sources.

### Focused validation

Verified green for the new focused suite:

- `psi.agent-session.workflow-guard-purity-test`

And previously maintained focused workflow Phase A suites remain available for broader validation.

## 2026-04-26 — Slice 6 landed

Implemented helper decomposition in `workflow_progression.clj` so recording helpers can be used under chart ownership without also owning control flow.

### What changed

#### 1. Added record-only helper for linear/non-judged success

New helper:

- `record-step-result`

Behavior:

- marks the latest attempt succeeded
- stores the result envelope on the attempt
- stores accepted result on the step-run
- appends history
- does **not** mutate run status
- does **not** mutate current-step-id

This is the Phase A record-only equivalent of the successful-recording portion that used to live inside `submit-result-envelope`.

#### 2. Added record-only helper for acting failure

New helper:

- `record-attempt-execution-failure`

Behavior:

- marks the latest attempt `:execution-failed`
- stores execution error metadata on the attempt
- appends history
- does **not** mutate run status
- does **not** mutate current-step-id

This is the Phase A record-only counterpart to the attempt-update portion of `record-execution-failure`.

#### 3. Refactored judged success aliasing explicitly

`record-actor-result` now delegates to `record-step-result`.

That keeps the semantic distinction visible for callers:

- `record-step-result` → linear / general record-only success helper
- `record-actor-result` → judged-step success helper name retained for clarity

#### 4. Added record-only helper for judge metadata

New helper:

- `record-judge-result`

Behavior:

- writes judge session id / output / event onto the latest attempt
- appends judge-recorded history
- does **not** mutate run status
- does **not** mutate current-step-id

This isolates the metadata-recording portion from the control-owning routing portion.

#### 5. Made `submit-judged-result` build on the record-only judge helper

`submit-judged-result` now:

- first calls `record-judge-result`
- then applies route-owned control projection (`:goto`, `:complete`, `:fail`)

This keeps the public helper behavior unchanged while making the decomposition explicit and reusable.

#### 6. Runtime callers updated to use record-only helpers

`workflow_statechart_runtime.clj` now uses:

- `record-step-result` for non-judged acting success
- `record-attempt-execution-failure` for acting failure recording
- `record-judge-result` as the record-only part of judged recording before chart-owned routing projection continues

This keeps recording and control more clearly separated in Phase A runtime paths.

### Focused validation

Verified green for the focused helper suite:

- `psi.agent-session.workflow-progression-test`

The updated progression tests prove:

- `record-step-result` records success without advancing control
- `record-actor-result` remains a semantic alias for judged-step success recording
- `record-attempt-execution-failure` records failure metadata without taking over run control
- `record-judge-result` records judge metadata without changing run status/current-step-id
- existing `submit-judged-result` behavior remains green

## 2026-04-26 — Slice 8 isolated workflow test migration landed

Completed a focused test reshaping pass under the testing-without-mocks / nullable-infrastructure approach.

### What changed

- Added an isolated Kaocha config:
  - `tests-workflow-isolated.edn`
- Reshaped workflow tests to keep:
  - real chart/runtime/progression logic
  - nullable infrastructure seams for prompt/judge/session creation
  - state/output assertions instead of interaction choreography
- Fixed `workflow_execution_test.clj` to use schema-valid nullable child sessions when stubbing attempt-session creation
- Simplified `resume-and-execute-run-test` to assert wrapper/result behavior rather than forcing queue internals
- Fixed `workflow_statechart_runtime_test.clj` expectations:
  - REVISE runtime test now asserts judged recording path only
  - terminal-tail semantics now correctly distinguish FIFO pre-start cancel from post-terminal discard
- Added a hard safety bound to the focused hierarchical-chart test drain loop so accidental churn becomes a concrete failure
- Fixed the hierarchical actor no-retry failure test to drive the failure event directly with explicit retry snapshot data instead of self-reenqueuing forever

### Validation

Ran the isolated workflow suite only:

- `psi.agent-session.workflow-hierarchical-chart-test`
- `psi.agent-session.workflow-statechart-runtime-test`
- `psi.agent-session.workflow-progression-test`
- `psi.agent-session.workflow-execution-test`
- `psi.agent-session.workflow-guard-purity-test`

Result:

- `51 tests, 177 assertions, 0 failures`

### Outcome

This does not prove the full repository unit suite is green yet, but it does prove the Phase A workflow slice is internally coherent under an isolated workflow-only harness and removes the earlier ambiguous hanging diagnosis from this slice.

## 2026-04-26 — Slice 8 repository-wide reintegration follow-on landed

Completed the immediate full-suite reconciliation after the isolated workflow slice turned green.

### What changed

- Hardened `query_graph_test.clj` RPC trace mutation assertions against global dispatch-event-log interleaving.
- The test now:
  - clears the global dispatch event log before each mutation assertion block
  - records the pre-mutation event-log count
  - inspects only newly appended entries
  - selects the latest `:session/set-rpc-trace` event rather than assuming `(last event-log)` belongs to the mutation under test
- This preserves the test's behavioral intent while removing a brittle ordering assumption that became invalid under broader suite activity.

### Validation

Ran the focused query-graph suite:

- `clojure -M:test --focus psi.agent-session.query-graph-test`

Result:

- `8 tests, 55 assertions, 0 failures`

Ran the full unit suite:

- `bb clojure:test:unit`

Result:

- `1420 tests, 10554 assertions, 0 failures`

### Outcome

Slice 8 is now confirmed both:

- locally/isolated for workflow-specific suites, and
- repository-wide for the unit suite

The remaining workflow cleanup is now structural/architectural follow-on (for example, retiring residual compatibility helpers like `next-step-id-fn` from non-Phase-A paths where still present), not test-red reconciliation.

## 2026-04-26 — Post-review cleanup landed

Addressed the follow-up cleanup items surfaced by task review.

### What changed

- Added shared step-preparation namespace:
  - `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
- Extracted shared helpers from both execution/runtime paths into that namespace:
  - binding source resolution
  - step input materialization
  - prompt rendering
  - step prompt shaping
  - child session config shaping
- Made `workflow_execution.clj` delegate to shared step-prep helpers instead of carrying duplicate logic.
- Made `workflow_statechart_runtime.clj` delegate to the same shared step-prep helpers.
- Clarified compiler surface in `workflow_statechart.clj`:
  - `compile-definition` is now explicitly documented as a compatibility Phase B compiler
  - `compile-hierarchical-chart` remains the canonical Phase A execution compiler
- Removed residual non-Phase-A `next-step-id-fn` dependence from `workflow_progression.clj` by using `workflow_statechart/next-step-id` directly.
- Removed no-op statechart hooks from the compiled hierarchical chart:
  - removed `:step/exit`
  - removed `:judge/exit`
- Aligned terminal action naming with the design:
  - renamed chart/runtime terminal hook usage from `:terminal/enter` to `:terminal/record`
- Updated focused hierarchical-chart tests to match the slimmer action surface.

### Validation

Focused workflow/statechart regression set:

- `clojure -M:test --focus psi.agent-session.workflow-hierarchical-chart-test --focus psi.agent-session.workflow-statechart-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.workflow-progression-test`

Result:

- `36 tests, 137 assertions, 0 failures`

Workflow isolated suite:

- `clojure -M:test -c tests-workflow-isolated.edn`

Result:

- `51 tests, 177 assertions, 0 failures`

Full unit suite:

- `bb clojure:test:unit`

Result:

- `1420 tests, 10632 assertions, 0 failures`

### Outcome

The review feedback is now implemented:

- compile surface clarified
- legacy next-step compatibility usage reduced
- duplicate preparation logic extracted
- dead/no-op chart hooks removed
- naming drift aligned

## 2026-04-26 — Optional shaping follow-on landed

Executed the remaining optional code-shaper follow-up items.

### What changed

- Added `workflow_progression_recording.clj` as the canonical Phase A record/update substrate.
- Trimmed `workflow_progression.clj` into a clearer compatibility/legacy control-plane namespace that delegates record-only helpers to `workflow_progression_recording`.
- Added `workflow_statechart_compat.clj` for compatibility compiler surfaces:
  - compatibility `compile-definition`
  - compatibility `workflow-run-chart`
  - related sequential compatibility metadata
- Simplified `workflow_statechart.clj` so it now presents the canonical Phase A compiler surface more clearly.
- Added a canonical-surfaces note:
  - `components/agent-session/src/psi/agent_session/workflow_statechart_canonical.md`
- Updated runtime/tests to consume the new split surfaces cleanly.

### Validation

Focused progression/statechart/execution checks:

- `clojure -M:test --focus psi.agent-session.workflow-statechart-test --focus psi.agent-session.workflow-progression-test --focus psi.agent-session.workflow-execution-test`
  - `26 tests, 112 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.workflow-statechart-runtime-test --focus psi.agent-session.workflow-guard-purity-test --focus psi.agent-session.workflow-hierarchical-chart-test`
  - `28 tests, 87 assertions, 0 failures`

Workflow isolated suite:

- `clojure -M:test -c tests-workflow-isolated.edn`
  - `51 tests, 177 assertions, 0 failures`

Full unit suite:

- `bb clojure:test:unit`
  - `1420 tests, 10632 assertions, 0 failures`

### Outcome

The optional shaping work is now complete:

- progression surface split
- compatibility compiler surface isolated
- canonical workflow surfaces documented

## Next slice

Follow-on from 057 is now task closure only, unless a separate new task is desired for broader workflow API polish.
