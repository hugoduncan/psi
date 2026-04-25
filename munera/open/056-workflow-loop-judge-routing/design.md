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

A workflow with steps `[plan, build, review]` where review has a judge.

This chart describes the **Phase A target architecture**. Phase B implements the same semantics imperatively without this chart driving execution — see "Phase B execution flow" below.

```
[workflow-run]
  │
  ├─ :pending
  │    on-entry: —
  │    :start → :step/plan
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/plan                            ← leaf state (no judge)
  │    on-entry: increment-iteration-count(plan),
  │              create-actor-session(plan, context), prompt(plan)
  │    on-exit:  record-result(plan) into context
  │    :actor/done → :step/build
  │    :actor/failed [guard: retry-available] → :step/plan
  │    :actor/failed [guard: ¬retry-available] → :failed
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/build                           ← leaf state (no judge)
  │    on-entry: increment-iteration-count(build),
  │              create-actor-session(build, context), prompt(build)
  │    on-exit:  record-result(build) into context
  │    :actor/done → :step/review
  │    :actor/failed [guard: retry-available] → :step/build
  │    :actor/failed [guard: ¬retry-available] → :failed
  │    :workflow/cancel → :cancelled
  │
  ├─ :step/review                          ← compound state (has judge)
  │    │
  │    ├─ :step/review.acting
  │    │    on-entry: increment-iteration-count(review),
  │    │              create-actor-session(review, context), prompt(review)
  │    │    on-exit:  record-result(review) into context
  │    │    :actor/done → :step/review.judging
  │    │    :actor/failed [guard: retry-available] → :step/review.acting
  │    │    :actor/failed [guard: ¬retry-available] → :failed
  │    │    :workflow/cancel → :cancelled
  │    │
  │    └─ :step/review.judging
  │         on-entry: project(review-session, projection-spec),
  │                   create-judge-session(projected-messages, no-tools),
  │                   prompt(judge)
  │         on-exit:  record-judge-result on attempt
  │         "APPROVED" → :completed
  │         "REVISE" [guard: target iterations < max] → :step/build
  │         "REVISE" [guard: target iterations >= max] → :failed
  │         <no-match> [internal, guard: judge-retries < max]
  │              → action: inject feedback into same judge session, re-prompt
  │              (internal transition — no exit/entry, same session continues)
  │         <no-match> [guard: judge-retries >= max] → :failed
  │         :workflow/cancel → :cancelled
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

Key statechart modeling notes:
- **Iteration count increment** is an on-entry action of each step (leaf or `.acting` sub-state), not an on-exit of judging. This matches resolved decision #10: count incremented on every entry including the first.
- **Actor failure** is modeled as `:actor/failed` with retry guards, matching the existing `record-execution-failure` + retry logic.
- **Cancel** transitions exist from every non-terminal state, matching the existing `workflow-statechart.clj`.
- **Judge retry** is an **internal transition** (no exit/entry fired), not a self-transition. The same judge session continues via `prompt-in!` — no new session is created on retry. This matches resolved decision #13.

### Key structural properties

**Steps without a judge are leaf states.** Events: `:actor/done` (advance), `:actor/failed` (retry or fail), `:workflow/cancel`. The existing linear behavior plus failure handling.

**Steps with a judge are compound states** with two sub-states: `.acting` and `.judging`. The actor produces `:actor/done` → transitions to `.judging`. The judge produces a signal string → the routing table maps it to a transition. Judge retry is an internal transition (same session continues).

**In Phase A, the imperative execution loop disappears.** The statechart drives execution: entering a state fires its entry action (spawn agent), agent completes and emits an event, event fires a transition, next state entered, next entry action fires. The loop is the statechart's event-processing loop. Phase B preserves the imperative loop and uses the statechart only for status tracking and history.

**Context accumulation is explicit.** Each exit action writes to the extended state. The Phase A context shape is a flat map designed for statechart consumption:

```clojure
;; Phase A — statechart extended state (flat, designed for statechart)
context = {:workflow-input    {...}
           :step-outputs      {"plan"   {:text "..."}
                               "build"  {:text "..."}
                               "review" {:text "..."}}
           :iteration-counts  {"plan" 1 "build" 2 "review" 2}
           :judge-results     {"review" {:output "REVISE" :event "REVISE"}}
           :sessions          {"plan"         "sid-1"
                               "build"        "sid-2"
                               "review"       "sid-3"
                               "review-judge" "sid-4"}}
```

Note: Phase B uses the existing nested `workflow-run` structure (step-runs → attempts → judge fields). The flat context shape above is the Phase A target. Phase A will need to either flatten the existing structure or adapt the statechart to work with the nested shape. This is a Phase A design decision, not a Phase B concern.

### Correspondence to current code

| Current code | Statechart equivalent | Notes |
|---|---|---|
| `workflow-run` state map | Statechart extended state (context) | Same data, different shape — nested (current) vs flat (Phase A target) |
| `execute-current-step!` | Entry action on a step state | |
| `submit-result-envelope` | Exit action + event emission | For judged steps, split into `record-actor-result` (exit of `.acting`) + judge routing |
| `execute-run!` loop | Statechart event-processing loop | Phase B preserves the loop; Phase A replaces it |
| `next-step-id-fn` | Transition target (static for leaf states) | For judged steps, replaced by `:on` routing table |
| routing table `:on` | Transition table with guards | |
| projection spec | Computed action parameter (context + session → messages) | |
| prompt template + bindings | Computed action parameter (context → string) | |
| `record-execution-failure` | `:actor/failed` event + retry guard | |
| judge `prompt-in!` retry | Internal transition (no exit/entry) | Same session continues |

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
- **`:on`** — a routing table mapping signals to directives. **Requires `:judge`** — `:on` without `:judge` is a validation error.

When both `:judge` and `:on` are absent, the step uses implicit routing: advance to next step on success (equivalent to `{:ok {:goto :next}}`).

### Judge

The judge is a separate agent session — not the actor. It receives a **projection** of the actor session as context, plus its own narrow prompt. It produces a short text signal that is matched against the routing table.

Separating the judge from the actor keeps actor steps fully reusable — the builder doesn't know it's in a loop. The judge is workflow infrastructure, not domain logic.

#### Judge schema

```clojure
{:prompt "..."              ; user-turn prompt — the decision question sent to the judge
 :system-prompt "..."       ; optional system prompt for the judge session
 :projection <projection>}  ; how much actor context to carry over
```

The `:prompt` is the **user message** — the specific question the judge must answer (e.g. "Respond exactly: APPROVED or REVISE"). The optional `:system-prompt` sets the judge session's system prompt (e.g. "You are a workflow routing judge. Respond with exactly one word."). When `:system-prompt` is absent, the judge session gets no system prompt — only the projected context and the user prompt.

The judge session gets **no tools** — it is a pure text-in/text-out classification agent. The judge child session is created with an empty tool-defs list explicitly.

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
- Send a new user message to the **same** judge session via `prompt-in!` with feedback (e.g. "Your response 'XYZ' did not match any expected signal. Expected exactly one of: APPROVED, REVISE"). This is a multi-turn conversation on the same session — the judge retains its prior context plus the correction.
- Extract the new assistant response and match again.
- Up to 2 retries (3 total attempts). If retries are exhausted, fail the workflow.

This works because `prompt-in!` is synchronous and blocking — the first call has completed and the session is idle before the retry call is made.

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
| `:max-iterations` | `pos-int?` | Bound on how many times the **target step** can be entered |

- `:goto :next` — advance to next step in definition order (current linear behavior). When the current step is the last in order, this completes the workflow (equivalent to `:goto :done`).
- `:goto :done` — complete the workflow immediately
- `:goto :previous` — jump to previous step in definition order. If the current step is the first in order, this is a definition error and returns `{:action :fail :reason :no-previous-step}`.
- `:goto "step-id"` — jump to a named step

**`:max-iterations`** is declared on the directive but the counter is **per-step** — all gotos targeting the same step share a single counter. If multiple directives target the same step with different `:max-iterations` values, the limit from the directive that fires is checked against the shared counter. The first directive to find the counter exhausted triggers failure.

When `:on` is absent, the implicit routing table is `{:ok {:goto :next}}` — identical to today's behavior.

#### Goto targets in file format vs compiled step-ids

In the `.psi/workflows/*.md` file format, `:goto` values use **workflow names** (e.g. `"builder"`), matching the `:workflow` key on other steps. The compiler resolves these to compiled step-ids (e.g. `"step-2-builder"`) during compilation, using the same name→step-id mapping used for step references. This keeps the file format author-friendly while the runtime operates on canonical step-ids.

### State additions

**Step-run level** (per-step, survives across attempts):

| Field | Type | Description |
|-------|------|-------------|
| `:iteration-count` | `int` | How many times this step has been entered, starting at 0, incremented on every entry (including the first). `:max-iterations 3` means the step can be entered at most 3 times total. |

**Attempt level** (per-attempt, consistent with existing execution-error placement):

| Field | Type | Description |
|-------|------|-------------|
| `:judge-session-id` | `string?` | The judge's execution session |
| `:judge-output` | `string?` | Raw judge text (last successful or exhausted response) |
| `:judge-event` | `string?` | The matched signal (or nil if no match after retries) |

### Execution flow

#### Phase A (statechart-driven, target architecture)

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

#### Phase B (progression-layer, first cut)

In Phase B, `execute-current-step!` is extended with a post-actor judge phase:

```
1. Actor step executes → :ok envelope
2. Step has :judge?
   a. Yes → DO NOT call submit-result-envelope (which would advance to next step)
          → instead: call record-actor-result (new helper in workflow_progression.clj)
            which writes the :ok envelope and :accepted-result onto the step-run
            without touching current-step-id or run status
          → call execute-judge! → get routing result
          → call submit-judged-result (new progression path) which:
              records judge fields on the attempt,
              applies routing:
                :goto → set current-step-id to target, increment target iteration count
                :complete → transition to :completed
                :fail → transition to :failed
   b. No  → existing path: call submit-result-envelope (advances to next step)
3. execute-run! loop unchanged — picks up whatever current-step-id was set
```

The key invariant: for judged steps, the judge routing **replaces** the normal `submit-result-envelope` advancement. The actor's `:ok` result is recorded via `record-actor-result`, but `current-step-id` is set by the routing directive, not by `next-step-id-fn`.

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
7. **Judge system prompt** → **author-provided** via the optional `:judge {:system-prompt "..."}` field. No auto-generation from `:on` keys. The author is responsible for instructing the judge to produce one of the expected signals. The `:judge {:prompt "..."}` is the user-turn question.
8. **Statechart events in Phase B vs A** → Phase B adds `:verdict/advance`, `:verdict/goto`, `:verdict/exhausted` as **history markers** for observability. They are appended to the run's history log but do not drive execution (the imperative code does). Phase A will use signal strings as actual statechart events that drive transitions.
9. **Judge fields placement** → on the **attempt**, not the step-run. Each attempt gets its own judge result. The step-run level holds only `:iteration-count`. This is consistent with how `:execution-error` already lives on attempts.
10. **`:iteration-count` semantics** → starts at 0, incremented on **every entry** to the step (including the first normal linear entry). `:max-iterations 3` means the step can be entered at most 3 times total.
11. **`:on` requires `:judge`** → `:on` without `:judge` is a validation error. The implicit advance-on-success routing only applies when both are absent.
12. **`:goto :previous` on first step** → returns `{:action :fail :reason :no-previous-step}`. This is a definition error.
13. **Judge retry mechanism** → multi-turn on the same session via `prompt-in!`. The judge session is idle after each response, so a new `prompt-in!` call works. Not a new session.
14. **Judge tools** → none. Judge session is created with empty tool-defs. Pure text-in/text-out classification.
15. **Recording actor result for judged steps** → new `record-actor-result` helper in `workflow_progression.clj` writes envelope and accepted-result onto step-run without advancing `current-step-id` or run status. `submit-result-envelope` is not called for judged steps.

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
