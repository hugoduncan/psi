# 056 — Workflow loop, judge, and routing

## Intent

Extend the deterministic workflow runtime with looping capability, driven by a judge/routing model that cleanly separates actor execution, result classification, and transition routing.

## Context

The current workflow model is a fixed linear pipeline: steps execute in definition order, each producing a result envelope, and the progression layer unconditionally advances to the next step (or completes). There is no way for a step's outcome to influence which step runs next.

Real workflows need loops — the canonical case being plan→build→review where the reviewer can send work back to the builder. Today this must be encoded in prose instructions to a single agent session, losing the composability and observability of the step model.

## Core insight

Every step already has an implicit routing rule: "on success, go to the next step." Making that routing explicit — and optionally driven by a judge agent — gives us loops, conditional branching, and early termination as a natural extension of the existing model, with the linear pipeline as the zero-configuration degenerate case.

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

A projection controls what the judge sees from the actor session. It is a strategy, not inline keys:

| Value | Meaning |
|-------|---------|
| `:none` | Judge gets only its own prompt, no actor context |
| `:full` | Judge gets the entire actor session history |
| `{:type :tail :turns N}` | Last N conversation turns |
| `{:type :tail :turns N :tool-output false}` | Last N turns, tool call/result blocks stripped |

Default when `:projection` is absent: `:full`.

The projection extracts from the actor session's message history and produces synthetic preloaded messages for the judge session, using the existing child-session prelude mechanism.

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
| `:max-iterations` | `pos-int?` | Bound on how many times this directive can fire |

- `:goto :next` — advance to next step in definition order (current linear behavior)
- `:goto :done` — complete the workflow immediately
- `:goto :previous` — jump to previous step in definition order
- `:goto "step-id"` — jump to a named step
- `:max-iterations` — when the iteration count for this goto is exhausted, the workflow blocks or fails (TBD: which)

When `:on` is absent, the implicit routing table is `{:ok {:goto :next}}` — identical to today's behavior.

### Statechart additions

New events:

- `:verdict/advance` — judge says advance (maps to `:goto :next` or `:goto :done`)
- `:verdict/goto` — judge says loop to a specific step
- `:verdict/exhausted` — max-iterations reached on a goto directive

These are `:running → :running` transitions (like `:workflow/retry`), except `:verdict/goto` changes `current-step-id`.

### Step run state additions

| Field | Type | Description |
|-------|------|-------------|
| `:judge-session-id` | `string?` | The judge's execution session |
| `:judge-output` | `string?` | Raw judge text |
| `:judge-event` | `string?` | The matched signal |
| `:iteration-counts` | `{string int}` | Per-goto-target iteration counts for this step |

### Execution flow

```
1. Actor step executes → :ok envelope recorded
2. Step has :judge?
   a. Yes → apply projection to actor session → projected messages
          → create judge child session (preloaded with projected messages)
          → prompt judge with judge :prompt
          → match judge output against :on keys → routing directive
          → apply directive (advance, goto, done)
   b. No  → apply implicit {:ok {:goto :next}} routing
3. Progression updates run state, statechart validates transition
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

When a `:goto` directive routes back to a previous step, the step re-executes. Its `:input-bindings` resolve as normal — `:workflow-input` still available, `:step-output` from prior accepted results still available. The key question is whether the *new* output from the judge/reviewer should be available to the target step.

Design decision (to refine): the judge's matched signal and the actor's output text from the judged step should be available as a binding source for the goto target. Candidate binding source: `:loop-input` or `:judge-output`, resolving from the most recent judge result that triggered the goto.

### Backward compatibility

- Steps without `:judge` or `:on` behave identically to today.
- The linear pipeline is the zero-configuration degenerate case.
- Existing workflow files, definitions, and tests are unaffected.
- The result envelope schema is unchanged — actors still produce `{:outcome :ok :outputs {:text ...}}`.

## Open questions

1. **`:max-iterations` exhaustion** — should it block (for human decision) or fail the workflow? Blocking is more forgiving; failing is more predictable.
2. **`:goto :previous` input bindings** — should the previous step receive the reviewer's feedback as input, or re-execute with its original inputs? Likely needs a `:loop-input` binding source.
3. **Judge signal matching** — exact string match, or case-insensitive / trimmed? Exact is simpler and more predictable; trimmed+case-insensitive is more tolerant of model variation.
4. **Judge failure** — if the judge output doesn't match any `:on` key, should it retry the judge, block, or fail? A default/fallback directive (`:else`) could handle this.
5. **Per-directive vs per-step iteration counting** — `:max-iterations` on the directive means different gotos from the same step have independent counters. Is that the right granularity?

## Acceptance criteria

- [ ] Steps with `:judge` and `:on` execute the judge phase and route based on the signal
- [ ] Steps without `:judge`/`:on` behave identically to today (linear advance)
- [ ] Projection controls what the judge sees from the actor session
- [ ] `:goto` directives route to named steps, `:next`, `:previous`, `:done`
- [ ] `:max-iterations` bounds loop count per directive
- [ ] Iteration state is tracked in step-run state and observable via introspection
- [ ] Workflow file compiler threads `:judge` and `:on` from config to canonical definitions
- [ ] Existing workflow files and tests pass without modification
- [ ] New tests prove: linear (no judge), single loop, multi-loop with exhaustion, projection variants
