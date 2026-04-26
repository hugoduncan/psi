# 057 — Plan

## Approach

Bottom-up, test-first. Each slice proves one architectural layer before the next builds on it. The existing Phase B tests serve as the behavioral specification — Phase A must preserve observable parity while shifting control ownership into the chart.

The execution mechanism is settled: re-entrant `process-event!` is not viable with fulcrologic statecharts for this use case. Phase A uses an **event-queue + drain loop** with FIFO semantics.

## Risks

- **Event-pump correctness**: the drain loop must preserve ordering, drop queued events after terminal entry, and quiesce cleanly in blocked states.
- **Working memory / projection drift**: entry/exit actions dual-write working memory and workflow-run projection; tests must prove they stay aligned.
- **Chart-size growth**: compiling `.blocked` for every executable step increases chart size; the uniform shape must remain testable and understandable.
- **Record-only helper extraction**: progression helpers that currently combine recording + control-flow must be cleanly split so the chart remains the sole control owner.

## Slice order

### Slice 1 — FIFO event pump proof + canonical chart compiler skeleton

**Goal**: Prove the queue-and-drain execution model and the canonical per-step chart shape.

Verify:
- Create a minimal chart where an entry action enqueues a completion event and the drain loop advances to the next state
- Confirm re-entrant `process-event!` is not the mechanism used
- Confirm FIFO ordering, terminal queue discard, and blocked-state quiescence semantics

Chart compiler:
- New function `compile-hierarchical-chart` in `workflow_statechart.clj`
- Input: workflow definition (same shape as today)
- Output: fulcrologic statechart definition with canonical step-local states
- Every executable step gets `.acting` and `.blocked`
- Judged steps additionally get `.judging`
- Initial state: `:pending` with `:workflow/start` → first step `.acting`
- Resume transitions always target the same step's `.acting`

Test: compile a 3-step definition (plan/build/review with judge on review), verify exact state shape and transition pattern.

### Slice 2 — Workflow context, attempt identity, authority rules, and actions-fn

**Goal**: Build the working-memory model and the action dispatch boundary.

- `create-workflow-context` — creates statechart env, session, working memory, FIFO event queue, and actions-fn
- Working memory includes:
  - execution context
  - attempt ids
  - actor/judge retry counts
  - pending actor/judge/routing buffers
  - blocked-step metadata
- Attempt identity rules:
  - entering `.acting` allocates fresh attempt id
  - `.judging` records onto the same attempt id
  - actor retry and resume allocate fresh attempt ids on `.acting` re-entry
  - judge retry keeps same attempt id and same judge session
- Authority rules:
  - working memory is authoritative for execution
  - workflow-run atom is projection for introspection/persistence
  - guards read only working-memory snapshots
  - run status is projected from active chart state after each event
- `make-workflow-actions` — dispatches on `:step/enter`, `:step/record-result`, `:step/record-failure`, `:judge/enter`, `:judge/record`, `:judge/retry`, `:step/block`, `:terminal/record`

Test: verify attempt allocation, dual-write sync expectations, and projection-of-status policy.

### Slice 3 — Linear-step execution with explicit outcome ownership

**Goal**: Non-judged steps execute through `.acting` / `.blocked` with unambiguous recording ownership.

- `.acting` entry: increment iteration count → allocate attempt → create actor session → `prompt-in!` → classify outcome → buffer transient result → enqueue `:actor/done` / `:actor/failed` / `:actor/blocked`
- `.acting` exit on success: `:step/record-result`
- `.acting` exit on failure: `:step/record-failure`
- `.blocked` entry: `:step/block`
- `.blocked` → `.acting` on `:workflow/resume`

Test: linear success, retrying failure, terminal failure, blocked/resume.

### Slice 4 — Judged compound steps and same-attempt judging

**Goal**: Judged steps execute through `.acting` / `.judging` / `.blocked` with same-attempt judge recording.

- `.acting` behavior matches linear steps
- `.acting` success transitions to `.judging`
- `.judging` entry: projection, judge session creation, judge prompt, routing evaluation, transient judge/routing buffering
- `.judging` exit on matched signal: `:judge/record`
- Judge emits `:judge/signal` with signal in event payload
- Judge no-match retry remains internal and keeps same attempt id and judge session
- Judge execution does not block in Phase A

Test: plan→build→review→REVISE→build→review→APPROVED, same attempt id across acting/judging, retry stays on same attempt.

### Slice 5 — Guard purity and routing/retry rules

**Goal**: Guards encode routing and retry rules without hidden state access.

- Guards are pure functions of working-memory snapshot + event payload + compiled metadata
- Iteration guard checks target step iteration count from working memory
- Actor retry guard checks actor retry metadata from working memory
- Judge retry guard checks judge retry metadata from working memory

Test: iteration exhaustion, actor retry available/exhausted, judge retry available/exhausted, no external atom reads.

### Slice 6 — Phase B helper decomposition under chart ownership

**Goal**: Split Phase B progression helpers so recording survives but control-flow ownership moves fully into the chart.

- Reuse `record-actor-result` for acting success where suitable
- Reuse `record-execution-failure` for acting failure
- Extract record-only subset from `submit-result-envelope` if needed
- Extract record-only subset from `submit-judged-result` for judged exits
- Ensure no helper mutates `current-step-id` or determines next step in Phase A

Test: helper-level tests proving recording works without imperative transition side-effects.

### Slice 7 — Cancel, terminal projection, and queue semantics

**Goal**: Cancel and terminal behavior are fully deterministic.

- `:workflow/cancel` transitions from `.acting`, `.judging`, and `.blocked` to `:cancelled`
- External cancel is enqueued FIFO and does not interrupt an already-running entry action
- Terminal entry projects terminal status and discards queued tail events
- `send-and-drain!` stops when queue is empty; blocked means quiescent in `.blocked`

Test: cancel ordering, cancel from blocked/judging, terminal queue discard semantics.

### Slice 8 — Test migration and cleanup

**Goal**: All existing tests pass, imperative orchestration removed.

- Migrate existing `workflow_execution_test.clj` tests to drive the statechart-based execution
- Remove the imperative loop code
- Remove `execute-current-step!`
- Replace `step-result-map` helper with explicit pending-result buffers
- Remove `next-step-id-fn` from execution usage
- Verify full suite green
- Update `compile-definition` to produce the hierarchical chart by default

## Decisions

- **Canonical chart shape**: every executable step gets `.acting` and `.blocked`; judged steps additionally get `.judging`.
- **Blocking scope**: only actor execution can block in Phase A; judge execution does not block.
- **Event model**: use `:judge/signal` plus event payload, not dynamic event names.
- **Execution mechanism**: FIFO event-queue + drain loop, not re-entrant `process-event!`.
- **Attempt identity**: `.acting` allocates attempt id; `.judging` shares it; actor retry/resume allocate fresh attempt ids; judge retry keeps same attempt id.
- **Authority split**: working memory is authoritative for execution; workflow-run atom is authoritative for external projection. Guards read only working memory.
- **Status projection**: workflow-run `:status` is derived from active chart state after each event.
- **Recording ownership**: entry actions do work; acting/judging exits record success/failure data; blocked entry records blocked data.
- **Helper reuse**: progression helpers survive only as record/update substrate, not as transition owners.
- **Observable parity**: parity means matching run status, current-step-id behavior, step-run/attempt shape, accepted results, iteration counts, judge fields, blocked/resume behavior, and terminal outcome.
