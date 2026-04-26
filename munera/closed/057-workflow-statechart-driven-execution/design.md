# 057 — Workflow statechart-driven execution (Phase A)

## Intent

Replace the imperative `execute-run!` loop with a statechart that **drives** workflow execution. Entry actions spawn agents, events carry results, guards evaluate routing conditions, and exit actions record accepted results. The statechart correspondence documented in 056's design becomes the literal implementation.

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

The statechart becomes hierarchical — one state per step, compound states for judged steps. Entry actions create sessions and prompt them. Exit actions record results. Events carry completion signals. Guards check iteration limits. The `execute-run!` loop becomes “start statechart, pump events until quiescent.”

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

### Canonical chart shape

Every actor-executing step compiles to a canonical blocked-aware shape.

- **Non-judged step**: `:step/<id>.acting` and `:step/<id>.blocked`
- **Judged step**: `:step/<id>.acting`, `:step/<id>.judging`, and `:step/<id>.blocked`
- **Blocked state is always step-local**, never a generic run-level blocked state
- **Only actor execution can block in Phase A**. Judge execution does not block; there is no `:judge/blocked` event in Phase A.

This yields one uniform resume rule: `:workflow/resume` always targets the current step's `.acting` state.

### Chart structure

Compiled from workflow definition. For a 3-step workflow `[plan, build, review]` where review has a judge:

```
[workflow-run]
  ├─ :pending
  │    :workflow/start → :step/plan.acting
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/plan
  │    ├─ :step/plan.acting                      ← no judge
  │    │    on-entry: [increment-iteration, create-attempt, create-actor-session, prompt]
  │    │    on-exit:  [record-success-or-failure-as-applicable]
  │    │    :actor/done → :step/build.acting
  │    │    :actor/failed [retry-available?] → :step/plan.acting
  │    │    :actor/failed [¬retry-available?] → :failed
  │    │    :actor/blocked → :step/plan.blocked
  │    │    :workflow/cancel → :cancelled
  │    │
  │    └─ :step/plan.blocked
  │         on-entry: [record-blocked-outcome]
  │         :workflow/resume → :step/plan.acting
  │         :workflow/cancel → :cancelled
  │
  ├─ :step/build
  │    ├─ :step/build.acting
  │    │    on-entry: [increment-iteration, create-attempt, create-actor-session, prompt]
  │    │    on-exit:  [record-success-or-failure-as-applicable]
  │    │    :actor/done → :step/review.acting
  │    │    :actor/failed [retry-available?] → :step/build.acting
  │    │    :actor/failed [¬retry-available?] → :failed
  │    │    :actor/blocked → :step/build.blocked
  │    │    :workflow/cancel → :cancelled
  │    │
  │    └─ :step/build.blocked
  │         on-entry: [record-blocked-outcome]
  │         :workflow/resume → :step/build.acting
  │         :workflow/cancel → :cancelled
  │
  ├─ :step/review
  │    ├─ :step/review.acting
  │    │    on-entry: [increment-iteration, create-attempt, create-actor-session, prompt]
  │    │    on-exit:  [record-success-or-failure-as-applicable]
  │    │    :actor/done → :step/review.judging
  │    │    :actor/failed [retry-available?] → :step/review.acting
  │    │    :actor/failed [¬retry-available?] → :failed
  │    │    :actor/blocked → :step/review.blocked
  │    │    :workflow/cancel → :cancelled
  │    │
  │    ├─ :step/review.judging
  │    │    on-entry: [project-actor-session, create-judge-session, prompt-judge]
  │    │    on-exit:  [record-judge-result]
  │    │    :judge/signal [signal=APPROVED] → :completed
  │    │    :judge/signal [signal=REVISE ∧ within-limit?] → :step/build.acting
  │    │    :judge/signal [signal=REVISE ∧ exhausted?] → :failed
  │    │    :judge/no-match [retries-left?] → internal (feedback + re-prompt)
  │    │    :judge/no-match [¬retries-left?] → :failed
  │    │    :workflow/cancel → :cancelled
  │    │
  │    └─ :step/review.blocked
  │         on-entry: [record-blocked-outcome]
  │         :workflow/resume → :step/review.acting
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
{:workflow-run-id      "run-123"
 :workflow-input       {...}
 :step-outputs         {"plan" {:text "..."} "build" {:text "..."}}
 :iteration-counts     {"plan" 1 "build" 2 "review" 2}
 :judge-results        {"review" {:output "REVISE" :event "REVISE"}}
 :sessions             {"plan" "sid-1"
                        "build" "sid-2"
                        "review" "sid-3"
                        "review-judge" "sid-4"}
 :attempt-ids          {"plan" "attempt-1"
                        "build" "attempt-3"
                        "review" "attempt-4"}
 :actor-retries        {"plan" 0 "build" 1 "review" 0}
 :judge-retries        {"review" 1}
 :blocked-step-id      nil
 :pending-actor-result {:outcome :ok :outputs {:text "..."}}
 :pending-judge-result {:output "REVISE" :event "REVISE"}
 :pending-routing      {:action :goto :target "step-2-builder"}
 :current-step-id      "step-3-reviewer"
 :actions-fn           <fn>}
```

Notes:
- `:pending-actor-result`, `:pending-judge-result`, and `:pending-routing` are transient working-memory buffers consumed by exit actions.
- These replace the previously vague `:last-result` sketch.
- `:judge-results` is an optional per-step summary/cache in working memory for guard/debug convenience; canonical persisted judge data still belongs on the attempt.
- **Attempt identity rule**: entering `.acting` allocates a fresh attempt id for that step. The subsequent `.judging` phase, if present, records onto that same attempt id. Actor retry and resume each allocate a fresh attempt id on re-entry to `.acting`. Judge retry keeps the same attempt id and the same judge session.

### Authority and synchronization

The statechart working memory is the **authoritative execution state**. Guards and transition decisions read from a working-memory snapshot only.

The workflow-run state map in the shared atom remains the **authoritative external projection** for:
- introspection
- resolver queries
- persistence (if/when added)
- `psi-tool` workflow ops

Phase A keeps both synchronized via the actions callback:
- each impure action updates working memory first
- the same action performs the corresponding workflow-run projection update in the shared atom
- guards must not read projected run state back out of the atom
- `workflow-run[:status]` is derived from the active chart state after each processed event; actions record ancillary data but do not independently invent run status
- `current-step-id` projects the logical workflow step id, not the substate name; transitions among `.acting`, `.judging`, and `.blocked` of the same step do not change it

This is Option A from the Phase A design, but with explicit authority rules to avoid drift.

### Actions dispatch model

Following `turn_statechart.clj`: a single `actions-fn` callback dispatched from `ele/script` elements. The actions-fn receives an action keyword and the working-memory snapshot, performs the side-effect, updates working memory, and projects the corresponding change onto the workflow-run map.

```clojure
;; Action keywords:
:step/enter          — increment iteration count, allocate attempt, create actor session, prompt it
:step/record-result  — record accepted actor output onto workflow-run and context
:step/record-failure — record actor failure metadata onto attempt and context
:judge/enter         — project actor session, create judge session, prompt it
:judge/record        — record judge result onto workflow-run attempt and context
:judge/retry         — inject feedback into judge session, re-prompt
:step/block          — record blocked outcome and resumable step metadata
:terminal/record     — record terminal outcome
```

The actions-fn is the **only** impure boundary. All statechart definitions, guard functions, and transitions are pure data.

### Guard purity rule

Guard functions must be pure functions of:
- the working-memory snapshot supplied by the statechart
- the current event payload
- compiled static step metadata

Guards must not read atoms, sessions, or external runtime state directly.

### Outcome recording rules

Phase A keeps the 056 statechart correspondence explicit and resolves recording ownership per outcome:

- **Entry actions own execution**: create sessions, materialize prompts, prompt actor/judge, classify outcome, buffer transient results in working memory, enqueue the next event.
- **Actor success (`:actor/done`)**: acting exit runs `:step/record-result`, consuming `:pending-actor-result` and projecting accepted result state.
- **Actor failure (`:actor/failed`)**: acting exit runs `:step/record-failure`, consuming failure metadata buffered in working memory. Retrying failure still records the failed attempt before re-entry.
- **Actor blocked (`:actor/blocked`)**: blocked bookkeeping is owned by blocked-state entry via `:step/block`; acting exit does not record an accepted result or failure record for the blocked outcome.
- **Judge matched signal (`:judge/signal`)**: judging exit runs `:judge/record`, consuming `:pending-judge-result` and `:pending-routing`.
- **Judge no-match retry (`:judge/no-match` with retries left)**: internal transition only; no exit/entry, same judge session and same attempt.
- **Judge exhausted no-match**: failure path records the final judge output and failure metadata before transition to `:failed`.

### Actor retry semantics

Actor retry remains distinct from judge retry.

- Actor retry availability is tracked from the current step's attempt lineage in working memory and mirrored into workflow-run attempt state.
- Re-entering the same `.acting` state creates a fresh attempt, just as Phase B does today.
- `:actor/failed` transitions back into the same `.acting` state when retry is available, otherwise to `:failed`.
- Attempt creation happens on state entry, not on the failure transition itself.

### Compilation: definition → hierarchical chart

`compile-definition` changes from producing a flat status-tracker chart to producing a hierarchical per-step chart. The compiler walks the definition's `:step-order` and `:steps` to emit:

- `:step/<id>.acting` for every executable step
- `:step/<id>.blocked` for every executable step, unconditionally
- `:step/<id>.judging` for judged steps only
- explicit `:actor/done` transitions for implicit linear advance (`{:ok {:goto :next}}`) on non-judged steps
- guarded `:judge/signal` transitions for compiled routing-table directives on judged steps
- `:workflow/resume` transitions from each blocked state back to the same step's `.acting`
- guards for iteration limits and retry availability
- `:pending`, `:completed`, `:failed`, `:cancelled` terminal/initial states

This removes `next-step-id-fn` from the execution path: implicit linear routing is now compiled into explicit chart transitions.

### Event vocabulary

| Event | Produced by | Meaning |
|---|---|---|
| `:workflow/start` | Caller | Begin execution |
| `:actor/done` | actions-fn after actor session completes | Actor step succeeded |
| `:actor/failed` | actions-fn after actor session errors | Actor step failed |
| `:actor/blocked` | actions-fn after actor session yields blocked outcome | Actor step blocked pending resume |
| `:judge/signal` | actions-fn after judge session completes | Judge produced a signal (signal string in event data) |
| `:judge/no-match` | actions-fn after signal evaluation | Judge signal didn't match routing table |
| `:workflow/resume` | Caller | Resume a blocked run |
| `:workflow/cancel` | Caller | External cancellation |

056's conceptual Phase A model described signal strings as the transition-driving events. Phase A implementation refines that into a single `:judge/signal` event carrying the signal string in event data, with guards matching `"APPROVED"`, `"REVISE"`, etc. This keeps the chart static and compiler-friendly while preserving the same semantics.

### Execution loop replacement

Re-entrant `process-event!` does not work with fulcrologic statecharts (reads stale working memory). Instead, use an **event-queue + drain loop** pattern:

```clojure
(defn execute-run! [ctx parent-session-id run-id]
  (let [wf-ctx (create-workflow-context ctx parent-session-id run-id)
        _ (start-chart! wf-ctx)
        _ (send-and-drain! wf-ctx :workflow/start)]
    (workflow-run-result ctx run-id)))
```

Queue rules:
- the event queue is FIFO
- entry actions may enqueue zero or more events after the current `process-event!` returns
- external `:workflow/cancel` is enqueued like any other event; it does not interrupt an already-running entry action
- once the chart enters a terminal state, remaining queued events are discarded
- `send-and-drain!` stops when the queue is empty; a blocked run is simply quiescent while active in a `.blocked` state

This is an **event-queue drain loop** — structurally similar to the imperative loop it replaces, but driven by the statechart's own actions rather than external orchestration logic.

### Blocked and resume semantics

Blocked runs are first-class chart states in Phase A.

- An actor that yields a blocked outcome emits `:actor/blocked`.
- The chart transitions into the current step's `.blocked` state and records resumable metadata in working memory and the workflow-run projection.
- `:workflow/resume` transitions back into that same step's `.acting` state.
- Resume creates a fresh attempt, matching current Phase B semantics.
- Cancel works from blocked states exactly as from other non-terminal states.
- Judge execution does not block in Phase A.

### Phase B helper reuse

Phase B progression helpers survive, but chart ownership changes where they are used.

| Phase A point | Helper substrate | Notes |
|---|---|---|
| acting success exit | `record-actor-result` or extracted success-recording subset from `submit-result-envelope` | chart owns step advance |
| acting failure exit | `record-execution-failure` | chart owns retry/fail transition |
| judging exit | extracted record-only subset of `submit-judged-result` | chart owns goto/complete/fail transition |
| terminal entry | existing terminal-outcome recording logic | chart owns terminal state selection; `:status` is still projected from active chart state |

`submit-result-envelope` and `submit-judged-result` do not remain transition owners in Phase A. If needed, record-only portions should be extracted so the chart remains the sole owner of control flow.

### Observable parity with Phase B

“Behave identically to Phase B” means parity in the externally observable workflow-run behavior:
- run status progression
- `current-step-id` behavior
- `step-runs` / attempts structure
- accepted results
- iteration counts
- judge result fields
- blocked/resume behavior
- terminal outcomes

Session creation may differ internally as long as the observable run semantics above remain equivalent.

### Backward compatibility

- Existing workflow definitions compile to the new hierarchical chart
- Linear workflows (no judge) produce a chart with equivalent observable behavior, even though Phase A represents them internally with `.acting` / `.blocked` substates rather than a single leaf state
- `workflow-run` state map shape is unchanged — same resolvers, same introspection
- `psi-tool` workflow ops are unchanged
- `create-run`, `resume-blocked-run`, `cancel-run` continue to work

### What stays from Phase B

- **Progression functions**: `record-actor-result`, `record-execution-failure`, and record-only portions extracted from existing progression helpers remain the implementation substrate for action callbacks and exit bookkeeping
- **Judge layer**: `project-messages`, `evaluate-routing`, `execute-judge!` — unchanged in semantics, though statechart entry/exit now determine when they are invoked
- **Model schemas**: unchanged
- **Workflow runtime**: `register-definition`, `create-run` — unchanged
- **Compiler**: `workflow_file_compiler.clj` — unchanged as definition compiler

### What changes from Phase B

- **`workflow_statechart.clj`**: flat status-tracker chart → hierarchical per-step chart compiler
- **`workflow_execution.clj`**: imperative loop → statechart-driven execution
- **`compile-definition`**: produces hierarchical chart with entry/exit actions, explicit blocked states, resume transitions, and routing transitions
- **`execute-run!`**: loop → start chart + send/drain events
- **`execute-current-step!`**: absorbed into entry/exit actions and action callbacks

### What gets removed

- The `execute-run!` while-loop
- The `execute-current-step!` function (logic moves into actions-fn + chart entry/exit)
- The step-result-map helper (replaced by explicit pending result buffers in working memory)
- The `next-step-id-fn` in compiled definitions (transitions are in the chart)

## Risks

1. **~~Synchronous event cascade depth~~**: Resolved. Re-entrant `process-event!` doesn't work; using event-queue + drain loop instead. No stack depth concern — the loop is iterative, not recursive.

2. **Error handling in entry actions**: If an entry action throws, the statechart may be in an inconsistent state. Need to catch exceptions in the actions-fn and emit `:actor/failed` instead.

3. **Working-memory / projection drift**: Dual-write between working memory and workflow-run projection must stay disciplined. Guards must read from working memory only.

4. **Chart-shape sprawl**: explicit blocked states on every executable step increases chart size. Tests should prove the uniform shape stays manageable.

5. **Working memory serialization**: If workflow runs need to survive process restarts, the statechart working memory needs to be serializable. The `simple-env` stores working memory in an atom — this is fine for in-process but doesn't persist. This is a known limitation that exists today and is not a Phase A concern.

## Acceptance criteria

- [ ] Workflow definitions compile to hierarchical statecharts with canonical per-step `.acting` / `.blocked` states and `.judging` for judged steps
- [ ] Entry actions on step states create sessions and prompt them
- [ ] Exit and blocked-entry actions record actor/judge/failure/blocked outcomes into workflow-run state
- [ ] Guards evaluate iteration limits and retry availability from working-memory snapshots only
- [ ] Judge steps compile to compound states with `.acting` and `.judging` sub-states
- [ ] Signal-based routing works through `:judge/signal` transitions with guards
- [ ] Implicit linear advance compiles to explicit `:actor/done` transitions
- [ ] `execute-run!` uses FIFO statechart event processing instead of an imperative loop
- [ ] Linear workflows (no judge) preserve observable parity with Phase B
- [ ] Judged workflows with loops preserve observable parity with Phase B
- [ ] Blocked/resume semantics work through explicit step-local `.blocked` chart states
- [ ] Cancel works from any non-terminal state, including blocked and judging states
- [ ] Existing tests pass (possibly refactored to drive the statechart)
- [ ] New tests prove the statechart drives execution end-to-end
- [ ] `psi-tool` workflow ops continue to work
- [ ] Workflow-run state map shape is unchanged for introspection
