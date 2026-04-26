# 057 — Workflow statechart-driven execution (Phase A)

## Intent

Replace the imperative `execute-run!` loop with a statechart that **drives** workflow execution. Entry actions spawn agents, events carry results, guards evaluate routing conditions. The statechart correspondence documented in 056's design becomes the literal implementation.

## Context

Phase B (task 056) shipped the full judge/routing/loop capability with an imperative execution loop. The statechart in `workflow_statechart.clj` is a flat status tracker — it records `:pending`, `:running`, `:validating`, etc. but doesn't drive execution. The actual control flow lives in `execute-run!` → `execute-current-step!` → progression functions.

Phase A makes the statechart the execution controller. This is the target architecture described in 056's design.

### What exists today (Phase B)

1. **Status-tracking statechart** (`workflow_statechart.clj`): flat states `:pending → :running → :validating → :completed/:failed/:cancelled`. Used only for transition legality checking in tests. Not wired into execution at all — progression functions directly mutate `:status` on the workflow-run map.

2. **Imperative execution loop** (`workflow_execution.clj`): `execute-run!` loops calling `execute-current-step!` until terminal/blocked. `execute-current-step!` handles actor execution, judge branching, and calls progression functions to advance state.

3. **Progression layer** (`workflow_progression.clj`): pure functions that update the workflow-run state map — `submit-result-envelope`, `record-actor-result`, `submit-judged-result`, `record-execution-failure`, etc.

4. **Judge layer** (`workflow_judge.clj`): projection, routing evaluation, and impure `execute-judge!`.

5. **Compiled definitions** (`workflow_statechart.clj`): `compile-definition` produces `{:chart, :next-step-id-fn, :step-order, ...}` but the chart is the flat status-tracker, not a per-step hierarchical chart.

### What Phase A changes

The statechart becomes hierarchical — one state per step, compound states for judged steps. Entry actions create sessions and prompt them. Exit actions record results. Events carry completion signals. Guards check iteration limits. The `execute-run!` loop becomes "start statechart, pump events until quiescent."

### Existing statechart patterns in codebase

The codebase has three established statechart usage patterns:

1. **`turn_statechart.clj`** — per-turn streaming. Uses `simple/simple-env`, `sp/start!`, `sp/process-event!`, working-memory data model for context, script elements for side-effects. This is the closest pattern to what Phase A needs.

2. **`workflows.clj`** (extension workflow runtime) — per-workflow-instance statecharts with async invoke. Uses the same `simple/simple-env` pattern with event pumping.

3. **`statechart.clj`** (session statechart) — session lifecycle. Uses compound states with entry/exit actions.

Phase A follows the `turn_statechart.clj` pattern most closely: synchronous event processing with side-effects dispatched through a callback function, context carried in working memory.

## Design

### Core architectural shift

| Concern | Phase B (current) | Phase A (target) |
|---|---|---|
| Execution control | Imperative loop in `execute-run!` | Statechart event-processing loop |
| Step entry | `execute-current-step!` called by loop | Entry action on step state |
| Step result recording | Progression functions called imperatively | Exit action on step state |
| Next-step decision | `next-step-id-fn` + judge routing | Statechart transition (static or guard-evaluated) |
| Iteration counting | `increment-iteration-count` in `execute-current-step!` | Entry action on step state |
| Judge execution | Branch in `execute-current-step!` | Entry action on `.judging` sub-state |
| Run status | Direct `:status` mutation | Derived from statechart configuration |

### Chart structure

Compiled from workflow definition. For a 3-step workflow `[plan, build, review]` where review has a judge:

```
[workflow-run]
  ├─ :pending
  │    :workflow/start → :step/plan
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/plan                              ← leaf (no judge)
  │    on-entry: [increment-iteration, create-actor-session, prompt]
  │    :actor/done → :step/build
  │    :actor/failed [retry-available?] → :step/plan
  │    :actor/failed [¬retry-available?] → :failed
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/build                             ← leaf (no judge)
  │    on-entry: [increment-iteration, create-actor-session, prompt]
  │    :actor/done → :step/review
  │    :actor/failed [retry-available?] → :step/build
  │    :actor/failed [¬retry-available?] → :failed
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/review                            ← compound (has judge)
  │    ├─ :step/review.acting
  │    │    on-entry: [increment-iteration, create-actor-session, prompt]
  │    │    :actor/done → :step/review.judging
  │    │    :actor/failed [retry-available?] → :step/review.acting
  │    │    :actor/failed [¬retry-available?] → :failed
  │    │    :workflow/cancel → :cancelled
  │    │
  │    └─ :step/review.judging
  │         on-entry: [project-actor-session, create-judge-session, prompt-judge]
  │         "APPROVED" → :completed
  │         "REVISE" [within-limit?] → :step/build
  │         "REVISE" [exhausted?] → :failed
  │         :judge/no-match [retries-left?] → internal (feedback + re-prompt)
  │         :judge/no-match [¬retries-left?] → :failed
  │         :workflow/cancel → :cancelled
  │
  ├─ :completed
  │    on-entry: [record-terminal-outcome]
  │
  ├─ :failed
  │    on-entry: [record-terminal-outcome]
  │
  └─ :cancelled
       on-entry: [record-terminal-outcome]
```

### Statechart context (extended state)

Carried in the working-memory data model, following the `turn_statechart.clj` pattern:

```clojure
{:workflow-run-id  "run-123"
 :workflow-input   {...}
 :step-outputs     {"plan" {:text "..."} "build" {:text "..."}}
 :iteration-counts {"plan" 1 "build" 2 "review" 2}
 :judge-results    {"review" {:output "REVISE" :event "REVISE"}}
 :sessions         {"plan" "sid-1" "build" "sid-2" "review" "sid-3" "review-judge" "sid-4"}
 :judge-retries    0
 :current-step-id  "step-3-reviewer"
 :actions-fn       <fn>}
```

This is the flat context shape described in 056's design. The statechart owns this context; the workflow-run state map is derived/synchronized from it.

### Actions dispatch model

Following `turn_statechart.clj`: a single `actions-fn` callback dispatched from `ele/script` elements. The actions-fn receives an action keyword and the working-memory data, and performs the side-effect.

```clojure
;; Action keywords:
:step/enter          — increment iteration count, create actor session, prompt it
:step/record-result  — extract actor output, record onto workflow-run
:judge/enter         — project actor session, create judge session, prompt it
:judge/record        — record judge result onto workflow-run attempt
:judge/retry         — inject feedback into judge session, re-prompt
:terminal/record     — record terminal outcome
```

The actions-fn is the **only** impure boundary. All statechart definitions and transitions are pure data. This keeps the chart testable with a mock actions-fn (same pattern as `make-accumulation-actions` in turn_statechart).

### Compilation: definition → hierarchical chart

`compile-definition` changes from producing a flat status-tracker chart to producing a hierarchical per-step chart. The compiler walks the definition's `:step-order` and `:steps` to emit:

- Leaf state for each non-judged step
- Compound state (`.acting` + `.judging`) for each judged step
- Transitions derived from step order (non-judged: `:actor/done` → next step) and routing tables (judged: signal → target)
- Guards for iteration limits and retry availability
- `:pending`, `:completed`, `:failed`, `:cancelled` terminal/initial states

### Event vocabulary

| Event | Produced by | Meaning |
|---|---|---|
| `:workflow/start` | Caller | Begin execution |
| `:actor/done` | actions-fn after actor session completes | Actor step succeeded |
| `:actor/failed` | actions-fn after actor session errors | Actor step failed |
| `:judge/signal` | actions-fn after judge session completes | Judge produced a signal (signal string in event data) |
| `:judge/no-match` | actions-fn after signal evaluation | Judge signal didn't match routing table |
| `:workflow/cancel` | Caller | External cancellation |

Judge signal events use a single `:judge/signal` event with the signal string in event data. Guards on transitions check `(= signal "APPROVED")`, `(= signal "REVISE")`, etc. This is cleaner than emitting the signal string as the event name (which would require dynamic event names in the chart definition).

### Execution loop replacement

Re-entrant `process-event!` does not work with fulcrologic statecharts (reads stale working memory). Instead, use an **event-queue + drain loop** pattern:

```clojure
(defn execute-run! [ctx parent-session-id run-id]
  (let [wf-ctx (create-workflow-context ctx parent-session-id run-id)
        ;; Start the statechart — enters :pending
        _ (start-chart! wf-ctx)
        ;; Send :workflow/start — enters first step, fires entry action
        ;; Entry action is synchronous: creates session, prompts, waits for completion
        ;; On completion, actions-fn enqueues :actor/done or :actor/failed
        ;; drain-events! processes the queue, which may enqueue more events...
        ;; This continues until no more events are enqueued (quiescence)
        _ (send-and-drain! wf-ctx :workflow/start)]
    ;; After drain completes, the chart has run to quiescence
    (workflow-run-result ctx run-id)))
```

The pattern:
1. Entry actions perform side-effects (create session, prompt, block for completion)
2. On completion, entry actions **enqueue** the next event into an external atom
3. After each `process-event!` returns, a drain loop checks the queue
4. If events are queued, they are processed one at a time, each potentially enqueuing more
5. This continues until the queue is empty (terminal state reached, or blocked)

This is an **event-queue drain loop** — structurally similar to the imperative loop it replaces, but driven by the statechart's own actions rather than external orchestration logic. The statechart owns the control flow; the drain loop is just the mechanical event pump.

### Synchronization with workflow-run state

The workflow-run state map in the atom must stay synchronized for:
- Introspection (resolvers query the run map)
- Persistence (if/when added)
- `psi-tool` workflow ops

Two options:

**Option A: Actions-fn writes to both.** Entry/exit actions update both the statechart context and the workflow-run map in the atom. The statechart context is the authoritative accumulator; the run map is a projection.

**Option B: Derive run map from statechart context.** After each event, project the statechart context into the run map shape. Cleaner but requires a projection function.

**Decision: Option A.** The actions-fn already has access to `ctx` and can `swap!` the atom. This matches the existing pattern where progression functions update the run map. The difference is that the statechart drives *when* those updates happen, not the imperative loop.

### Backward compatibility

- Existing workflow definitions compile to the new hierarchical chart
- Linear workflows (no judge) produce a chart with leaf states only — same behavior
- `workflow-run` state map shape is unchanged — same resolvers, same introspection
- `psi-tool` workflow ops are unchanged
- `create-run`, `resume-blocked-run`, `cancel-run` continue to work

### What stays from Phase B

- **Progression functions**: `record-actor-result`, `submit-judged-result`, `record-execution-failure`, `increment-iteration-count` — these become the implementation of actions-fn callbacks
- **Judge layer**: `project-messages`, `evaluate-routing`, `execute-judge!` — unchanged
- **Model schemas**: unchanged
- **Workflow runtime**: `register-definition`, `create-run` — unchanged
- **Compiler**: `workflow_file_compiler.clj` — unchanged (produces definition, not chart)

### What changes from Phase B

- **`workflow_statechart.clj`**: flat status-tracker chart → hierarchical per-step chart compiler
- **`workflow_execution.clj`**: imperative loop → statechart-driven execution
- **`compile-definition`**: produces hierarchical chart with entry/exit actions, guards, transitions
- **`execute-run!`**: loop → start chart + send start event
- **`execute-current-step!`**: absorbed into entry actions

### What gets removed

- The `execute-run!` while-loop
- The `execute-current-step!` function (logic moves into actions-fn)
- The step-result-map helper (absorbed into actions-fn)
- The `next-step-id-fn` in compiled definitions (transitions are in the chart)

## Risks

1. **~~Synchronous event cascade depth~~**: Resolved. Re-entrant `process-event!` doesn't work; using event-queue + drain loop instead. No stack depth concern — the loop is iterative, not recursive.

2. **Error handling in entry actions**: If an entry action throws, the statechart may be in an inconsistent state. Need to catch exceptions in the actions-fn and emit `:actor/failed` instead.

3. **Blocked runs**: The current `:blocked` status needs to work with the statechart. A blocked step should leave the chart in a state that can receive `:workflow/resume`. This maps to a `:blocked` sub-state or a guard-gated transition.

4. **Resume semantics**: Resuming a blocked run means re-entering the step state. The statechart needs to support this — either by staying in the step state (and the resume event triggers a new attempt) or by transitioning to a blocked state and back.

5. **Working memory serialization**: If workflow runs need to survive process restarts, the statechart working memory needs to be serializable. The `simple-env` stores working memory in an atom — this is fine for in-process but doesn't persist. This is a known limitation that exists today (workflow runs don't survive restarts) and is not a Phase A concern.

## Acceptance criteria

- [ ] Workflow definitions compile to hierarchical statecharts with per-step states
- [ ] Entry actions on step states create sessions and prompt them
- [ ] Exit actions record results into workflow-run state
- [ ] Guards evaluate iteration limits and retry availability
- [ ] Judge steps compile to compound states with `.acting` and `.judging` sub-states
- [ ] Signal-based routing works through statechart transitions with guards
- [ ] `execute-run!` uses statechart event processing instead of an imperative loop
- [ ] Linear workflows (no judge) behave identically to Phase B
- [ ] Judged workflows with loops behave identically to Phase B
- [ ] Blocked/resume semantics work through the statechart
- [ ] Cancel works from any non-terminal state
- [ ] Existing tests pass (possibly refactored to drive the statechart)
- [ ] New tests prove the statechart drives execution end-to-end
- [ ] `psi-tool` workflow ops continue to work
- [ ] Workflow-run state map shape is unchanged for introspection
