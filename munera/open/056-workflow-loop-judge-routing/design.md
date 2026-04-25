# 056 — Workflow loop, judge, and routing

## Intent

Extend the deterministic workflow runtime with looping capability, driven by a judge/routing model that cleanly separates actor execution, result classification, and transition routing — grounded in a rigorous statechart model where the chart drives execution rather than merely tracking status.

## Context

The current workflow model is a fixed linear pipeline: steps execute in definition order, each producing a result envelope, and the progression layer unconditionally advances to the next step (or completes). There is no way for a step's outcome to influence which step runs next.

Real workflows need loops — the canonical case being plan→build→review where the reviewer can send work back to the builder. Today this must be encoded in prose instructions to a single agent session, losing the composability and observability of the step model.

The current statechart is a **status tracker** — it records that the run is `:pending`, `:running`, `:blocked`, etc. The actual execution logic lives in an imperative loop (`execute-run!` calling `execute-current-step!` repeatedly). The statechart doesn't drive execution, it reflects it.

## Core insight

Every step already has an implicit routing rule: "on success, go to the next step." Making that routing explicit — and optionally driven by a judge agent — gives us loops, conditional branching, and early termination as a natural extension of the existing model, with the linear pipeline as the zero-configuration degenerate case.

## Statechart ↔ Workflow correspondence

This is the foundational mapping. The statechart is the execution controller, not a status mirror.

### Concept mapping

| Statechart concept | Workflow counterpart |
|---|---|
| **State** | A step in a specific phase (acting, judging) |
| **Event** | A signal produced by completion of work (`:actor/done`, judge verdict string) |
| **Action (on-entry)** | Spawn agent session and prompt it (actor or judge) |
| **Action (on-exit)** | Record result into context, clean up session reference |
| **Guard** | Iteration count check, signal match against routing table |
| **Extended state (context)** | Workflow input, step outputs, iteration counts, session references, judge results |
| **Transition** | Routing directive — which state to enter next, derived from `:on` table |

### Three kinds of state

1. **Workflow context** (statechart extended state) — the accumulated data map: workflow input, each step's accepted output, iteration counts, judge results. Grows as steps complete. Owned by the statechart.

2. **Agent context** (external resource) — the actor session's conversation history. External to the statechart. The statechart holds a *reference* (session-id) in its extended state, not the content. Created by entry actions, read by exit actions and projection functions.

3. **Judge context** (external resource) — the projected view of the agent context, plus the judge session's own conversation. Also external, also referenced. Created by the judging state's entry action from a projection of the actor session.

### Messages and computed parameters

Prompts and projections are **computed functions of context**, not carried state:

- **Prompt materialization**: the entry action for a step computes the prompt from `context[:step-outputs]` and `context[:workflow-input]` via binding resolution. This is the existing `materialize-step-inputs` + `render-prompt-template` — reading from statechart context instead of the workflow-run state map.

- **Projection**: the entry action for a judging sub-state reads the actor session referenced in context, applies the projection spec, and produces preloaded messages for the judge session. The projection spec is definition data, the actor session is an external resource, and the projected messages are a computed parameter — none of these are statechart state.

### Chart structure

A workflow with steps `[plan, build, review]` where review has a judge:

```
[workflow-run]
  │
  ├─ :pending
  │    on-entry: —
  │    :start → :step/plan
  │
  ├─ :step/plan                            ← leaf state (no judge)
  │    on-entry: create-actor-session(plan, context), prompt(plan)
  │    on-exit:  record-result(plan) into context
  │    :actor/done → :step/build
  │
  ├─ :step/build                           ← leaf state (no judge)
  │    on-entry: create-actor-session(build, context), prompt(build)
  │    on-exit:  record-result(build) into context
  │    :actor/done → :step/review
  │
  ├─ :step/review                          ← compound state (has judge)
  │    │
  │    ├─ :step/review.acting
  │    │    on-entry: create-actor-session(review, context), prompt(review)
  │    │    on-exit:  record-result(review) into context
  │    │    :actor/done → :step/review.judging
  │    │
  │    └─ :step/review.judging
  │         on-entry: project(review-session, projection-spec),
  │                   create-judge-session(projected-messages),
  │                   prompt(judge)
  │         on-exit:  record-judge-result into context,
  │                   increment-iteration-count
  │         "APPROVED" → :completed
  │         "REVISE" [guard: iterations < max] → :step/build
  │         "REVISE" [guard: iterations >= max] → :failed
  │         <no-match> [guard: judge-retries < max] → :step/review.judging  (retry judge)
  │         <no-match> [guard: judge-retries >= max] → :failed
  │
  ├─ :completed
  │    on-entry: record-terminal-outcome(context)
  │
  ├─ :failed
  │    on-entry: record-terminal-outcome(context)
  │
  └─ :cancelled
       on-entry: record-terminal-outcome(context)
```

### Key structural properties

**Steps without a judge are leaf states.** One event (`:actor/done`), one transition (to next step). The existing linear behavior.

**Steps with a judge are compound states** with two sub-states: `.acting` and `.judging`. The actor produces `:actor/done` → transitions to `.judging`. The judge produces a signal string → the routing table maps it to a transition.

**The imperative execution loop disappears.** There is no `execute-run!` loop. The statechart drives execution: entering a state fires its entry action (spawn agent), agent completes and emits an event, event fires a transition, next state entered, next entry action fires. The loop is the statechart's event-processing loop.

**Context accumulation is explicit.** Each exit action writes to the extended state:

```clojure
context = {:workflow-input    {...}
           :step-outputs      {"plan"   {:text "..."}
                               "build"  {:text "..."}
                               "review" {:text "..."}}
           :iteration-counts  {"review" 2}
           :judge-results     {"review" {:output "REVISE" :event "REVISE"}}
           :sessions          {"plan"         "sid-1"
                               "build"        "sid-2"
                               "review"       "sid-3"
                               "review-judge" "sid-4"}}
```

### Correspondence to current code

| Current code | Statechart equivalent |
|---|---|
| `workflow-run` state map | Statechart extended state (context) |
| `execute-current-step!` | Entry action on a step state |
| `submit-result-envelope` | Exit action + event emission |
| `execute-run!` loop | Statechart event-processing loop |
| `next-step-id-fn` | Transition target (static for leaf states) |
| routing table `:on` | Transition table with guards |
| projection spec | Computed action parameter (context + session → messages) |
| prompt template + bindings | Computed action parameter (context → string) |

## Design

### Decomposition of concerns

```
Actor (does work) → Judge (classifies result) → Routing table (dispatches on signal) → Statechart (enforces transition)
```

Four orthogonal concerns:

| Concern | Owner | Description |
|---------|-------|-------------|
| Doing work | Actor step | Existing workflow step — runs an agent session, produces output. Unchanged. |
| Classifying result | Judge | Optional lightweight agent that observes actor output and emits a signal. |
| Routing on signal | Routing table (`:on`) | Maps judge signals to routing directives. Declared in the workflow definition. |
| Enforcing transitions | Statechart | Validates that the routing directive corresponds to a legal state transition. |

### Step definition — enriched

A step definition gains two optional keys:

- **`:judge`** — an optional signal-producing phase that runs after the actor step completes.
- **`:on`** — a routing table mapping signals to directives.

When `:judge` is absent, the implicit signal is `:ok` and the implicit routing is `{:ok {:goto :next}}`.

### Judge

The judge is a separate agent session — not the actor. It receives a **projection** of the actor session as context, plus its own narrow prompt. It produces a short text signal that is matched against the routing table.

Separating the judge from the actor keeps actor steps fully reusable — the builder doesn't know it's in a loop. The judge is workflow infrastructure, not domain logic.

#### Judge schema

```clojure
{:prompt "..."              ; narrow decision prompt
 :projection <projection>}  ; how much actor context to carry over
```

#### Projection

A projection controls what the judge sees from the actor session. It is a named strategy:

| Value | Meaning |
|-------|---------|
| `:none` | Judge gets only its own prompt, no actor context |
| `:full` | Judge gets the entire actor session history |
| `{:type :tail :turns N}` | Last N conversation turns |
| `{:type :tail :turns N :tool-output false}` | Last N turns, tool call/result blocks stripped |

Default when `:projection` is absent: `:full`.

The projection extracts from the actor session's message history and produces synthetic preloaded messages for the judge session, using the existing child-session prelude mechanism.

#### Judge failure handling

If the judge output doesn't match any key in the `:on` table:
- Inject the mismatch as feedback into the judge session and continue it (limited retries).
- If retries are exhausted, fail the workflow.

Judge signal matching is **exact string match** (trimmed of leading/trailing whitespace).

### Routing table (`:on`)

Maps signal strings to routing directives:

```clojure
{:on {"APPROVED" {:goto :next}
      "REVISE"   {:goto "build" :max-iterations 3}}}
```

#### Directive vocabulary

| Key | Type | Meaning |
|-----|------|---------|
| `:goto` | `:next`, `:previous`, `:done`, or step-id string | Where to route |
| `:max-iterations` | `pos-int?` | Bound on how many times this step can be looped to |

- `:goto :next` — advance to next step in definition order (current linear behavior)
- `:goto :done` — complete the workflow immediately
- `:goto :previous` — jump to previous step in definition order
- `:goto "step-id"` — jump to a named step
- `:max-iterations` — per-step iteration count; when exhausted, the workflow **fails**

When `:on` is absent, the implicit routing table is `{:ok {:goto :next}}` — identical to today's behavior.

Iteration counting is **per-step** — all gotos targeting the same step share a single counter.

### Step run state additions

| Field | Type | Description |
|-------|------|-------------|
| `:judge-session-id` | `string?` | The judge's execution session |
| `:judge-output` | `string?` | Raw judge text |
| `:judge-event` | `string?` | The matched signal |
| `:iteration-count` | `int` | How many times this step has been entered (per-step) |

### Execution flow (statechart-driven)

```
1. Statechart enters step state
2. Entry action: compute prompt from context, create actor session, prompt it
3. Actor completes → emit :actor/done event
4. Step has :judge?
   a. Yes → transition to .judging sub-state
          → entry action: project actor session, create judge session, prompt it
          → judge completes → emit signal string as event
          → match signal against :on table:
              → matched directive with :goto → guard checks iteration count
                  → within limit → transition to target step state
                  → exhausted → transition to :failed
              → no match → retry judge (inject feedback, continue session)
                  → retries exhausted → transition to :failed
   b. No  → transition to next step state (implicit {:ok {:goto :next}})
5. Exit action: record result + judge result into context
```

### File format

Multi-step workflow with a review loop:

```edn
{:steps [{:workflow "planner"  :prompt "$INPUT"}
         {:workflow "builder"  :prompt "Execute:\n$INPUT\nOriginal: $ORIGINAL"}
         {:workflow "reviewer" :prompt "Review:\n$INPUT\nOriginal: $ORIGINAL"
          :judge {:prompt "Respond exactly: APPROVED or REVISE"
                  :projection {:type :tail :turns 1}}
          :on {"APPROVED" {:goto :next}
               "REVISE"   {:goto "builder" :max-iterations 3}}}]}
```

A linear workflow (no `:judge`, no `:on`) is unchanged from today.

### Input binding on loop-back

When a `:goto` directive routes back to a previous step, the step re-executes. Its `:input-bindings` resolve as normal — `:workflow-input` still available, `:step-output` from prior accepted results still available (and now updated with the latest outputs from the judged step).

No special loop-feedback binding source in the first cut. The goto target gets its normal bindings. Since `:step-output` resolves from accepted results, and the reviewing step's output is recorded before the judge runs, the goto target *can* access the reviewer's output via `:step-output` if its bindings reference it. This may be sufficient; a dedicated `:loop-feedback` source can be added later if needed.

### Backward compatibility

- Steps without `:judge` or `:on` behave identically to today.
- The linear pipeline is the zero-configuration degenerate case.
- Existing workflow files, definitions, and tests are unaffected.
- The result envelope schema is unchanged — actors still produce `{:outcome :ok :outputs {:text ...}}`.

## Resolved decisions

1. **`:max-iterations` exhaustion** → **fail** the workflow. Predictable; human intervention can restart.
2. **Judge signal matching** → **exact string match** (trimmed). Predictable; workflow authors control the judge prompt.
3. **Judge failure (no match)** → **limited retries** (inject mismatch feedback into judge session and continue), then **fail**. Fixed limit: **2 retries**.
4. **Iteration counting** → **per-step**. All gotos targeting the same step share one counter.
5. **Loop-back input bindings** → **no** for now. The goto target re-executes with its normal input bindings (`:workflow-input`, `:step-output` from prior accepted results). No special `:loop-feedback` binding source. Can be added later if needed.
6. **Judge retry limit** → **fixed at 2** retries (3 total attempts). Not configurable per judge in the first cut.
7. **Judge system prompt** → **author-provided** via the `:judge {:prompt "..."}` field. No auto-generation from `:on` keys. The author is responsible for instructing the judge to produce one of the expected signals.

## Open questions

None remaining — all resolved. See "Resolved decisions" below.

## Implementation strategy

### Phase B: Progression-layer extension (first cut)

Keep the imperative `execute-run!` loop. Add judge + routing as new branches in the existing progression logic. The statechart remains a status tracker. This ships the user-facing capability quickly.

Changes:
- Model: judge schema, projection schema, routing directive schema, step-run judge fields, iteration count
- New namespace `workflow_judge.clj`: projection extraction, judge session creation, signal matching
- Progression: wire judge+routing into post-actor phase in `execute-current-step!`
- Compiler: thread `:judge` and `:on` from workflow file config to canonical step definitions
- Statechart: add verdict events for observability (status tracking)
- Tests: model, projection, judge, routing, progression, compiler, end-to-end loop

### Phase A: Statechart-driven execution (follow-on)

Migrate the execution layer so the compiled statechart drives step execution. Entry actions spawn agents, events carry results, guards evaluate routing conditions. The imperative loop disappears. The statechart correspondence documented above becomes the literal implementation.

Changes:
- Compile workflow definitions into hierarchical statecharts (leaf states for simple steps, compound states for judged steps)
- Entry/exit actions own session creation, prompting, result recording
- Extended state (context) replaces the workflow-run state map as the accumulator
- `execute-run!` becomes "start statechart, process events until quiescent"
- The progression layer becomes thin — just context updates and event emission
- Existing tests refactored to drive the statechart directly

Phase A is the target architecture. Phase B is the pragmatic path to get looping capability shipped, with Phase A immediately following.

## Acceptance criteria

- [ ] Steps with `:judge` and `:on` execute the judge phase and route based on the signal
- [ ] Steps without `:judge`/`:on` behave identically to today (linear advance)
- [ ] Projection controls what the judge sees from the actor session
- [ ] `:goto` directives route to named steps, `:next`, `:previous`, `:done`
- [ ] `:max-iterations` bounds loop count per step
- [ ] Iteration state is tracked in step-run state and observable via introspection
- [ ] Judge failure triggers limited retries with feedback injection, then fails
- [ ] Workflow file compiler threads `:judge` and `:on` from config to canonical definitions
- [ ] Existing workflow files and tests pass without modification
- [ ] New tests prove: linear (no judge), single loop, multi-loop with exhaustion, projection variants, judge retry on no-match
