# 226 — Multi-prompt workflow steps

## Status

Design — complete. All open questions resolved (Q1–Q11 below). Ready for
planning.

## Intent

Allow a single `:session` workflow step to carry **more than one prompt**. The
prompts form an ordered queue. The first prompt is submitted to the step's child
session and runs one model turn to completion; when that turn finishes, the next
queued prompt is submitted into the **same** child session and runs its own
turn; and so on until the queue is drained. Routing/judging happens once, after
the whole queue has drained.

The animating goal is **scripted multi-turn conversation within one session
step, preserving conversational context across turns**, without forcing authors
to split the work into separate session steps (which today each construct a
*fresh* child session and therefore lose the prior turns' context).

## Problem

Today a `:session` step runs exactly one actor turn:

- `materialize-step-session-conversation` assembles ordered `:contributions`
  into a conversation.
- `split-step-session-conversation` preloads all but the final user text message
  and treats that final message as **the** prompt.
- `execute-session-step!` runs **one** `execute-actor-turn!` and then routes.

So the only way to express "do A, then once A is done, do B in the same context"
is to either:

1. cram both asks into a single prompt (the model decides ordering; no enforced
   turn boundary; no intermediate model reply to anchor B on), or
2. use two session steps (separate child sessions; B loses A's conversation,
   tool history, and accumulated reasoning).

Neither expresses "sequential prompts sharing one session's evolving context,
with a real model turn boundary between them."

## Scope

In scope:

- A grammar extension to the `:session` step that expresses an **ordered list of
  prompts**, each submitted as its own turn into the same child session.
- Sequential execution semantics: prompt _n+1_ is submitted only after prompt
  _n_'s turn has completed.
- Defining which turn's results populate the step's output surfaces
  (`:final-llm-reply`, `:transcript`, etc.) and feed the judge.
- Error/cancellation behavior across the queue (stop on intermediate failure;
  honor stop-signal checkpoints between prompts).
- Documentation (`doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`)
  and grammar/runtime tests.

- **Per-prompt text output surfaces** (in scope, Q11(b)): each prompt-group
  exposes its own `:final-llm-reply`/`:transcript`, addressable downstream as
  `{:step <s> :prompt <p> :output <k>}`.
- **Step-level structured output** applied to the **final** prompt's turn (the
  existing single `:outputs` structured entry; unchanged granularity).
- The exemplar rewrite of `review-task-design.edn` (merge architecture +
  ambiguity reviews into one multi-prompt step) and the new state-based routing
  operation it needs (Q8).

Out of scope (candidate follow-on tasks):

- **Per-prompt structured `:outputs`** (deferred, Q11). First cut keeps
  structured output step-level / final-turn only.

- Cross-turn *workflow data flow* letting prompt _n+1_ template-reference prompt
  _n_'s reply via source-refs. Prompts share the live session context (the model
  sees prior turns), but the first cut does not add new workflow-level
  source-refs that inject a prior same-step turn's text into a later prompt's
  template. Downstream steps may still address per-prompt outputs.
- Conditional/looping prompt queues (a prompt queue is linear; loops still use
  step-level `:on`/`:max-iterations`).
- Per-prompt distinct tools/skills/model (the child session config is per-step,
  shared by all queued prompts).
- Per-prompt judges/routing (a step still has exactly one judge + `:on`, applied
  after the queue drains). See Q8.
- Multi-prompt support for LLM `:judge` sub-steps (judging stays single-turn).
- Multi-prompt support for `:delegate` steps (delegation boundary unchanged).

## Minimum concepts

- **Prompt queue**: an ordered sequence of prompt specs on a `:session` step.
- **Prompt spec**: the existing assembly material (contributions / template)
  that renders to one submitted prompt for one turn.
- **Turn boundary**: a completed `execute-actor-turn!` (full model reply,
  including any tool loop) marks the point at which the next queued prompt is
  submitted.
- **Drain**: the step is complete (and eligible for judging/routing) only after
  every queued prompt's turn has finished (or the queue aborted on failure).

## Acceptance criteria

1. A `:session` step can author N ≥ 1 prompts; they execute in author order,
   each as a separate turn in the **same** child session, with prompt _n+1_
   submitted only after prompt _n_'s turn completes.
2. A single-prompt step behaves exactly as today (the new form is a strict
   superset; the existing single-prompt authoring continues to work).
3. Each prompt-group exposes its own text output surfaces (`:final-llm-reply`,
   `:transcript`), addressable as `{:step <s> :prompt <p> :output <k>}`. The
   step-level `:final-llm-reply` reflects the **last** prompt's reply and
   `:transcript` the accumulated conversation across all turns (so
   `{:step <s> :output …}` without a `:prompt` selector stays single-prompt
   back-compatible). A step-level structured output, if declared, applies to the
   **final** prompt's turn.
4. The judge/`:on` routing runs **once**, after the queue drains, against the
   post-drain step result; the judge may reference per-prompt output surfaces.
5. If an intermediate turn errors, the queue stops at that point and the step
   surfaces the failure (no further queued prompts are submitted).
6. Stop-signal/cancellation is honored between queued prompts (a cancelled run
   does not keep submitting later queued prompts).
7. `doc/workflow-grammar.md` + concepts doc describe the multi-prompt form;
   grammar IR validation and runtime tests cover ordering, drain, single-prompt
   equivalence, intermediate-failure abort, and cancellation between prompts.

## Architecture alignment

- The conversation/turn substrate already exists: `execute-session-step!` →
  `execute-actor-turn!` → `execute-session-turn!` runs one bounded turn against
  a child session id that persists across calls. Multi-prompt is "call the
  existing turn primitive N times against the same session id," so the runtime
  primitive does not need new turn mechanics — the change is in how the step
  drives the queue and in IR/materialization shaping.
- Workflow-runtime boundary principle (`AGENTS.md`): generic mechanism stays in
  runtime; workflow-specific labels/topology stay in authored definitions. The
  prompt **queue mechanism** is generic runtime; the concrete prompts are
  authored content. No workflow-specific business rules enter runtime code.
- Materialization (`workflow-step-materialization`) currently produces a single
  prompt + preloaded messages. The natural extension is to produce an ordered
  list of "submission points," each itself a (preload?, prompt) shaping, or a
  list of prompt strings layered over a shared preload. This must stay
  data-shaped and introspectable.
- Statechart execution currently records one pending actor result per attempt
  after the single turn. Multi-prompt must reconcile "one statechart step / one
  attempt / one routing decision" with "N internal turns." The intended model is
  that the N turns are an internal loop **inside** one statechart step attempt,
  not N statechart steps (Q5, resolved).

## Grammar shape (Q1 — decided: A)

The current grammar deliberately avoids canonical `:prompt`/`:input` fields and
subsumes conversation assembly into ordered `:contributions`. The multi-prompt
form must fit that philosophy.

**Decision: Option A — `:prompts` as an ordered vector of named prompt-groups.**
Each prompt-group materializes to one submitted prompt and runs one turn against
the shared child session, in author order. Shape (to be finalized as IR schema):

```clojure
{:name "design-review"
 :type :session
 :tools [...] :skills [...]                 ; per-step session config (shared)
 :prompts
 [{:name "architecture"
   :prompt-workflow "review-task-design-architecture-review.md"} ; or :contributions [...]
  {:name "ambiguity"
   :prompt-workflow "review-task-design-ambiguity-review.md"}]
 :outputs {...}?                             ; step-level; structured entry → final turn
 :judge {...}                                ; one judge, after drain
 :on {...}}
```

Rationale:

- Each prompt-group reuses the existing `materialize-step-session-conversation`
  + `split-step-session-conversation` per group, so the runtime turn primitive
  is unchanged — multi-prompt is "loop the existing one-turn primitive N times
  against the same session id."
- Per-prompt `:name` gives the addressable handle for text replies
  (`{:step "design-review" :prompt "architecture" :output :final-llm-reply}`).
- Structured output stays a single step-level `:outputs` entry bound to the
  final turn (Q4/Q11), so no per-prompt structured-output machinery is built in
  the first cut.

**Precedence (single vs multi):** a `:session` step uses `:contributions`
(/`:prompt-workflow`) **xor** `:prompts`. The single-prompt form is exactly
today's behavior (it is *not* internally rewritten into a one-element
`:prompts`, to guarantee byte-for-byte equivalence — Q6).

## Open questions

Resolved:

- **Q1 — Grammar shape.** ✅ Option A (named prompt-groups under `:prompts`);
  `:contributions` xor `:prompts`.
- **Q2 — "Model turn" definition.** ✅ One turn = one full `execute-actor-turn!`
  (model reply incl. complete tool loop); prompt _n+1_ submits only after that
  turn completes.
- **Q3 — Output surfaces.** ✅ Per-prompt surfaces addressable via `:prompt`
  selector; step-level surfaces = last prompt (back-compat).
- **Q4 — Structured output.** ✅ (Revised after Q8/Q11.) Structured output stays
  a **step-level** construct (the existing single `:outputs` structured entry),
  applied to the **final** prompt's turn. Because the judge/routing fires once
  per step after the queue drains, a single final structured output is the right
  granularity. Per-prompt structured output is deferred (Q11).
- **Q5 — Statechart accounting.** ✅ N turns run as an internal loop inside one
  statechart step attempt (one pending-actor result, one routing decision), not
  N statechart sub-steps.
- **Q6 — Degenerate cases.** ✅ Empty `:prompts` = IR-validation error;
  single-prompt authoring stays the existing form (unchanged behavior).
- **Q7 — Exemplar.** ✅ Merge `architecture-review` + `ambiguity-review` of
  `review-task-design.edn` into one multi-prompt `:session` step so the task
  design + architecture sources are read once and reused for both reviews
  (token efficiency). Routing for the merged step is state-based over
  `design-steps.md` (Q8), not per-prompt reply text.

Resolved (continued):

- **Q8 — Routing/follow-up for the exemplar.** ✅ The merged step's judge routes
  on **whether `design-steps.md` has open (unchecked `- [ ]`) checklist items**:
  REPEAT while open items remain, DONE when none. This is state-based routing
  over the task artifact, not text-token (`PASS_STATUS`) parsing. Both review
  turns append follow-up items to `design-steps.md`; the post-drain judge
  inspects the file. Implication: this likely needs a **new generic
  deterministic operation** (e.g. `workflow/open-checklist-items-routing` taking
  an authored file path + routes), consistent with the workflow-runtime boundary
  (generic parameterized primitive; concrete path/routes authored). Consequence:
  the exemplar's *routing* no longer depends on per-prompt reply addressing — see
  Q11.
- **Q9 — Model fallback under multi-prompt.** ✅ Fallback applies **per turn**:
  each queued prompt's turn independently attempts ranked-model fallback, and a
  model switch persists to subsequent turns in the same session.
- **Q10 — Per-prompt error granularity.** ✅ On an intermediate turn failure the
  step surfaces failure and stops the queue (AC-5); the failure payload
  identifies which prompt failed (by prompt `:name`).

Resolved (continued):

- **Q11 — Scope of per-prompt outputs given Q8.** ✅ Option **(b)**. First cut
  ships:
  - the `:prompts` queue (Option A, named prompt-groups);
  - per-prompt **text** reply addressing (`{:step s :prompt p :output
    :final-llm-reply | :transcript}`), which falls out of named prompt-groups
    and is useful for `final-summary`;
  - **step-level** structured output bound to the **final** prompt's turn (no
    new structured-output machinery).
  Deferred to a follow-on task (until an exemplar needs it):
  - per-prompt structured `:outputs`.

All open questions are resolved; the design is ready for planning.
