# 226 — Multi-prompt workflow steps

## Status

Design — complete. All open questions resolved (Q1–Q12 below) and all design
review follow-ups executed (architectural-fit A1–A3 + pass-2 D1–D2, ambiguity
B1–B5 + pass-2 E1–E3, inconsistency C1 + pass-2 C2). Ready for planning.

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
- The exemplar rewrite of `review-task-design.edn`: merge the **three** review
  phases — architecture, ambiguity, inconsistency — into one multi-prompt
  `:session` step. This is a deliberate **topology redesign** (see D1), **not** a
  behaviour-preserving refactor: the merged step reads the design **once** and
  runs all three reviews as back-to-back turns in one shared session against the
  **same un-followed-up** design, then a **single** `design`-profile follow-up
  step (replacing the three per-phase `*-follow-up` steps) executes the
  accumulated recorded items after the post-drain route. Its post-drain routing
  reuses the **existing** `workflow/pass-feedback-routing` family over the merged
  step's per-prompt reply outputs — no new filesystem-state routing operation is
  introduced (Q8). The surviving `final-summary` step's three per-phase
  contributions migrate from `{:step "<phase>-review" :yield :text}` to per-prompt
  `{:step "design-review" :prompt "<phase>" :output :final-llm-reply}` refs — the
  only legal per-phase text addressing once the three review steps are merged
  (C2; per-prompt `:yield` is invalid under B1(b), and step-level `:yield :text`
  resolves only to the last prompt's reply).

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
2. A single-prompt step behaves exactly as today — **as the N=1 degenerate of
   the one unified prompt-queue runtime path** (not a separately maintained
   path; see D2). The `:contributions` single-prompt authoring continues to work
   because a queue of length 1 reproduces today's single-turn behaviour, a
   *consequence* of the unified mechanism rather than a back-compat guarantee.
3. Each prompt-group exposes its own text output surfaces (`:final-llm-reply`,
   `:transcript`), addressable as `{:step <s> :prompt <p> :output <k>}`. A
   prompt-group's `:final-llm-reply`/`:transcript` are **turn-local** (that
   prompt's own reply / its own turn slice, B2). The step-level
   `:final-llm-reply` reflects the **last** prompt's reply and the step-level
   `:transcript` is the conversation accumulated across all turns (so
   `{:step <s> :output …}` without a `:prompt` selector stays single-prompt
   back-compatible). The step's **yielded** value as a whole is the unchanged
   session-step default — text sourced from the step-level `:final-llm-reply`
   (B1); the `:prompt` discriminator applies to `:output` refs only, never to
   `:yield` refs. A step-level structured output, if declared, applies to the
   **final** prompt's turn.
4. The judge/`:on` routing runs **once**, after the queue drains, against the
   post-drain step result; the judge may reference per-prompt output surfaces.
5. If an intermediate turn errors, the queue stops at that point and the step
   surfaces the failure (no further queued prompts are submitted).
6. Stop-signal/cancellation is honored between queued prompts (a cancelled run
   does not keep submitting later queued prompts). A cancellation between prompts
   short-circuits to a terminal `:cancelled` step outcome (distinct from the
   AC-5 `:failed` path), does **not** run the judge/`:on` routing, and retains
   the already-completed per-prompt turn records as introspectable (B5).
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
  list of "submission points," each itself a (preload?, prompt) shaping. This
  must stay data-shaped and introspectable. **Both authoring forms normalize into
  this one queue representation (D2):** `:contributions` → a one-element unnamed
  prompt-group; `:prompts` → named prompt-groups. There is a single
  queue-driving runtime path; single-prompt is its N=1 degenerate, not a
  separate path.
- **Shared source material is carried by the live shared session, not a
  step-level shared-preload field (E1).** The earlier "or a list of prompt
  strings layered over a shared preload" alternative is **withdrawn**: there is
  no step-level shared `:contributions`/preamble distinct from the per-prompt
  prompt-groups (the `:contributions` xor `:prompts` precedence already forbids
  step-level `:contributions` on a `:prompts` step, and the grammar deliberately
  avoids a canonical `:preload` field — `doc/workflow-grammar-concepts.md`,
  "Session construction"). Instead, the **first** prompt-group's own
  contributions/`:prompt-workflow` load the shared sources once into the child
  session; every later prompt-group's turn runs against that **same live child
  session**, so the model sees the already-loaded sources via conversation
  memory without re-embedding them. This is the concrete realization of the
  Q7/D1 "sources read once and reused" token-efficiency rationale: the sources
  are assembled into the conversation exactly once (turn 1), not re-read and
  re-embedded per review prompt. It also keeps the queue representation uniform —
  each prompt-group is just one submission point — and is the same shared-context
  mechanism that animates the whole feature (preserving conversational context
  across turns).
- Statechart execution currently records one pending actor result per attempt
  after the single turn. Multi-prompt must reconcile "one statechart step / one
  attempt / one routing decision" with "N internal turns." The intended model is
  that the N turns are an internal loop **inside** one statechart step attempt,
  not N statechart steps (Q5, resolved).
- **Per-prompt turn results are recorded in the canonical step-result /
  progression substrate, not transient loop locals (Q5/Q12).** Each queued
  prompt's completed turn is recorded as a named prompt-group entry in the same
  substrate that records the single `:pending-actor-result` envelope today, so
  every intermediate turn is introspectable (S4 self-awareness) and
  replay-faithful (`∀change → event → log → replayable`). The reconcile with Q5
  is structural, not a loss of fidelity: the step still emits **one**
  post-drain `:pending-actor-result` for the **one** routing decision, but that
  envelope carries an ordered collection of per-prompt turn records (each keyed
  by prompt `:name`, each exposing `:final-llm-reply`/`:transcript`) plus the
  step-level rollup (last prompt's reply; accumulated cross-turn transcript).
  The internal turn loop never holds a turn's result only in an in-loop local
  that the recorded step result cannot reproduce on replay.

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
   :prompt-workflow "review-task-design-architecture-review.md"} ; :prompt-workflow XOR :contributions [...]
  {:name "ambiguity"
   :prompt-workflow "review-task-design-ambiguity-review.md"}
  {:name "inconsistency"
   :prompt-workflow "review-task-design-inconsistency-review.md"}]
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
(/`:prompt-workflow`) **xor** `:prompts`. **Both authoring forms normalize at IR
time into the same internal prompt-queue representation** (an ordered vector of
prompt-groups), so the runtime drives **one** queue path (D2): `:contributions`
normalizes to a queue of one **unnamed** prompt-group (step-level surfaces only,
no per-prompt namespace), and `:prompts` normalizes to a queue of **named**
prompt-groups (per-prompt addressing). The single-prompt form reproduces today's
behaviour as the **N=1 degenerate** of this one path — not a separately
maintained byte-for-byte path. The two authoring forms are not rewritten *into
each other* (a `:contributions` step does not become a named one-element
`:prompts`, nor vice versa); what they share is the internal queue
representation. A `:prompts` vector must be non-empty; a **one-element**
`:prompts` is valid and exposes per-prompt addressing (B3). Prompt-group
`:name`s must be **unique within the step** (duplicate names are an
IR-validation error); the `(step-name, prompt-name)` pair is the addressing
handle, so names may repeat across distinct steps (B4).

**Prompt-group internal authoring precedence (E2):** within a single
prompt-group, the prompt body is authored by `:prompt-workflow` **xor**
`:contributions` — mirroring the step-level `:contributions` xor `:prompts`
rule one level down. Exactly one of the two **must** be present:

- **both** `:prompt-workflow` and `:contributions` on one prompt-group ⇒
  IR-validation error;
- **neither** present ⇒ IR-validation error (a prompt-group with no prompt body
  has nothing to submit).

These errors are reported at workflow-load / IR-normalization time with the same
fail-fast shape as the other grammar/source-ref validation errors. Both forms
materialize to one submitted prompt for that group's turn: `:prompt-workflow`
names a prompt markdown file rendered to the turn's prompt; `:contributions` is
the ordered assembly material rendered to the turn's prompt (the same assembly
the single-prompt `:contributions` step uses). The step-level session config
(`:model`/`:tools`/`:skills`) is shared by all prompt-groups regardless of which
internal form each group uses.

## Source-ref integration for `:prompt` (Q12)

The per-prompt selector `{:step s :prompt p :output k}` is an **optional
`:prompt` discriminator** layered onto the canonical prior-step source ref
`{:step s :output k}`. It is one ref shape in the **shared** data-reference
substrate, so it resolves uniformly everywhere a source ref is admitted: invoke
`:args`, session `:contributions` source items, template `:vars`, and delegated
context. No selector-specific code path is added per call site.

Resolution and back-compat:

- `{:step s :output k}` **without** `:prompt` against a multi-prompt step
  resolves to the **step-level** surface (last prompt's `:final-llm-reply`,
  accumulated `:transcript`), preserving single-prompt back-compat (AC-3).
- `{:step s :prompt p :output k}` resolves to prompt-group `p`'s own output
  surface `k` within step `s`'s recorded per-prompt turn records.

Validation (compile-time IR validation, mirroring the existing "a reference that
selects an output not exposed by that step type is invalid" rule in
`doc/workflow-grammar-concepts.md`). A `:prompt` selector is **invalid** when:

- the target step `s` is **not** a `:session` step (invoke/delegate/judge steps
  expose no prompt-groups);
- the target session step `s` is **single-prompt** (uses
  `:contributions`/`:prompt-workflow`, not `:prompts`) — it has no prompt-group
  namespace;
- `p` does not name a declared prompt-group of step `s`;
- `k` is not an output surface that prompt-group exposes — first cut admits only
  the per-prompt **text** surfaces `:final-llm-reply` and `:transcript`; a
  `:prompt` selector against a **structured** `:output` key is invalid because
  per-prompt structured output is deferred (Q11). Structured output stays
  step-level / final-turn and is addressed by the no-`:prompt` form.
- the ref targets the **same step that is currently being assembled** — i.e. a
  prompt-group's `:contributions`/template references a sibling prompt-group in
  **its own** step via `{:step <self> :prompt p :output k}` (including a forward
  reference to a not-yet-run later group or a back reference to an earlier
  sibling group). This is **invalid in the first cut (E3)** because injecting a
  prior same-step turn's reply into a later prompt's workflow-rendered template
  is exactly the **cross-turn workflow data flow** that Scope defers. The
  `:prompt` selector resolves uniformly only against **prior steps'** recorded
  per-prompt records; a self/same-step `:prompt` ref has no resolved value at
  assembly time and is rejected at IR-validation time. (This is a
  workflow-data-flow restriction only — earlier sibling prompt-groups' turns are
  still **visible to the model** at runtime through the shared live child session
  (E1), which is what carries cross-turn context in the first cut; what is
  withheld is the ability to *template-inject* a sibling's reply text via a
  source-ref. Lifting this restriction is the deferred cross-turn-data-flow
  follow-on.)

These validation errors are reported at workflow-load / IR-normalization time
with the same fail-fast shape as other source-ref validation errors, so authors
catch a mis-targeted `:prompt` ref before runtime.

## Ambiguity resolutions (B1–B5)

These resolve ambiguities raised by the design ambiguity review. They refine,
not revise, the acceptance criteria and grammar above.

### B1 — Yielded value vs `:prompt` discriminator

`doc/workflow-grammar-concepts.md` keeps **output surfaces** (`:output`, e.g.
`:final-llm-reply`/`:transcript`) distinct from the **yielded value** (the step's
value as a whole, addressed via `:yield` as a tagged union, default for a session
step = text sourced from the `:final-llm-reply` surface).

(a) **A multi-prompt session step yields exactly one value as a whole**, composed
by the unchanged session-step default: `{:type :text :text <step-level
:final-llm-reply>}`, where the step-level `:final-llm-reply` is the **last**
prompt's reply (AC-3). There is no per-prompt yielded value; per-prompt data is an
**output-surface** concept only. This keeps `{:step s :yield :text}` against a
multi-prompt step byte-for-byte equivalent to the single-prompt case.

(b) **The `:prompt` discriminator applies to `:output` refs only**, never to
`:yield` refs. `{:step s :prompt p :output k}` is the per-prompt addressing form;
`{:step s :prompt p :yield k}` is **invalid** (rejected at IR-validation time,
same fail-fast shape as the other `:prompt` validation errors), because the
yielded value is the step's value as a whole and has no per-prompt namespace.
`{:step s :yield k}` (no `:prompt`) addresses the single step-level yielded
tagged union.

### B2 — Per-prompt `:transcript` is the prompt's own turn slice

A prompt-group's per-prompt `:transcript` surface contains **only that prompt's
own turn slice** (the messages submitted for that turn plus that turn's model
reply and tool loop), **not** the cumulative conversation up to that turn. This
mirrors per-prompt `:final-llm-reply` (that prompt's reply, not the last). The
**step-level** `:transcript` remains the conversation **accumulated across all
turns** (AC-3). So per-prompt surfaces are turn-local; step-level surfaces are the
cross-turn rollup. (The model still *sees* cumulative context at runtime because
all prompts share one live child session; that live-context sharing is independent
of how the addressable `:transcript` surface is sliced.)

### B3 — One-element `:prompts` is valid; one unified runtime path (revised — D2)

`:prompts` legality by cardinality:

- **empty `:prompts`** ⇒ IR-validation error (Q6).
- **one-element `:prompts`** ⇒ **valid** (AC-1, N ≥ 1); its single **named**
  prompt-group exposes per-prompt addressing (`{:step s :prompt p :output …}`).
- **single-prompt `:contributions`** ⇒ valid; normalizes to a single **unnamed**
  prompt-group (step-level surfaces only).

Both authoring forms normalize into the **same internal prompt-queue
representation** and run the **one unified runtime path** (D2): there is no
separate single-prompt execution path. They remain two **distinct authoring
surfaces**, not rewritten *into each other*, differing only in whether the lone
prompt-group is **named** — i.e. in **per-prompt addressing capability**, the
forward-looking architectural axis this feature introduces, not backward
compatibility. Choose `:prompts` (even with one entry) when you want named
per-prompt addressing; choose `:contributions` for the terse unnamed single-turn
case. `:contributions` xor `:prompts` (the precedence rule) still holds.

### B4 — Prompt-group `:name` uniqueness within a step

Each prompt-group's `:name` **must be unique within its step**. Duplicate
prompt-group names in one step are an **IR-validation error** reported at
workflow-load / IR-normalization time with the same fail-fast shape as the other
grammar/source-ref validation errors. Uniqueness is **per step** only — the
`(step-name, prompt-name)` pair is the addressing handle, so prompt-group names
may repeat across different steps. This uniqueness is what makes the `:prompt p`
selector and the per-prompt turn records (keyed by prompt `:name`) well-defined.

### B5 — Step outcome on cancellation between prompts (AC-6)

Cancellation between queued prompts is a **run-level stop**, distinct from the
AC-5 intermediate-turn error:

- **Queue stops**: no further queued prompts are submitted past the cancellation
  checkpoint (AC-6).
- **No judge/`:on` routing runs.** Unlike a normal drain, a cancelled step does
  not produce a post-drain step result for routing; cancellation short-circuits
  to a terminal **`:cancelled`** outcome (matching the `:cancelled` terminal
  status in `doc/workflows.md`), separate from the `:failed` outcome an
  intermediate turn error produces (AC-5).
- **Partial per-prompt turn records are retained and introspectable.** The
  prompt-groups whose turns completed before the cancellation checkpoint remain
  recorded in the canonical step-result/progression substrate (S4
  introspectable, replay-faithful); the recorded step result carries those
  partial per-prompt records plus the `:cancelled` marker. The in-flight turn at
  the checkpoint is aborted per the existing cancellation contract (a
  provider/tool boundary already in flight may finish once, but no new queued
  prompt is submitted).

This makes the cancellation path as explicit as the AC-5 error path: error ⇒
`:failed` + failing-prompt name, routing skipped; cancellation ⇒ `:cancelled`,
routing skipped, partial records retained.

## Architectural-fit resolutions (D1–D2, pass 2)

These resolve the pass-2 architectural-fit misfits. They refine the exemplar
framing (D1) and the single-prompt execution model (D2) without changing the
core feature (the `:prompts` queue).

### D1 — Merged exemplar is a topology redesign, not a faithful refactor

The pass-2 review noted that claiming the merged multi-prompt step "matches the
real workflow's three-phase topology" is an architectural-fit overclaim: a
multi-prompt step is *N turns in one session → one post-drain route* (Q5/Q8) and
**cannot** reproduce the live `review-task-design` per-phase
**review→follow-up(mutates design.md)→next review** structure, whose defining
feature is follow-up mutation *between* phases (`doc/workflows.md`).

Resolution — **option (a)**: keep the merge but reframe it honestly as a
**deliberate topology redesign**, not a behaviour-preserving refactor.

- The merged step reads the design **once** and runs architecture, ambiguity,
  and inconsistency reviews as three back-to-back turns in **one shared session**
  against the **same un-followed-up** design (each later review sees the earlier
  reviews' replies in the live session context).
- The three per-phase `*-follow-up` steps **collapse into a single
  `design`-profile follow-up step** placed *after* the merged review step's
  post-drain route. It executes the items accumulated across all three reviews in
  one batch.
- The post-drain `pass-feedback-routing` over the three per-prompt reply outputs
  decides whether to repeat the merged-review→follow-up pass (REPEAT) or finish
  (DONE → `final-summary`), mirroring the live `clarity-status` REPEAT loop but
  at per-pass granularity instead of per-phase.
- Net behavioural trade: interleaved per-phase follow-up → **batch-review-then-
  follow-up**. Sanctioned by `λα. ¬compat(backward)` and justified by token
  efficiency + shared conversational context. The "faithful to the live
  topology" framing is withdrawn (Q7 revised).

Option (b) (keep the phases as separate review→follow-up steps and scope the
exemplar elsewhere) was rejected: it would unwind the Q7/Q8/Q11 design
investment (`pass-feedback-routing` equivalence, per-prompt reply addressing as
load-bearing), and the multi-prompt feature's value is precisely the
shared-session back-to-back turns the merged review exercises.

### D2 — One unified runtime path; authoring distinction is addressing capability

The pass-2 review noted the dual single-prompt path (keep `:contributions` as a
*separate* execution path, *not* the N=1 degenerate of `:prompts`, justified by
"byte-for-byte equivalence") fights `λone_way` (singular solution), `consistent`
(one idiom), and `λα. ¬compat(backward)` (its sole justification was a disclaimed
value), and risks two parallel runtime paths drifting.

Resolution — combine **both** reviewer options: **unify the runtime path** *and*
**re-justify the authoring distinction on a non-back-compat basis**.

- **One runtime path.** Both `:contributions` and `:prompts` normalize at IR time
  into the same internal **prompt-queue** representation (ordered prompt-groups);
  the runtime drives one queue. Single-prompt is the genuine **N=1 degenerate** —
  no separately maintained path, so no drift. This satisfies `λone_way` at the
  mechanism level and dissolves the "two parallel runtime paths" concern.
- **Authoring distinction = per-prompt addressing capability**, not back-compat.
  `:contributions` authors one **unnamed** prompt-group (terse, step-level
  surfaces only, no `:prompt` namespace); `:prompts` authors **named**
  prompt-groups (per-prompt addressing). The axis is the new capability this
  feature introduces — a forward-looking architectural distinction. Behaviour
  preservation for existing single-prompt authors is a *consequence* of N=1, not
  the driver.
- **`λone_way` at the decision level.** The obvious authoring is singular per
  intent: need multiple turns or named per-prompt addressing → `:prompts`;
  otherwise → `:contributions`. The degenerate one-element `:prompts` stays legal
  but is never the obvious path for an unaddressed single turn. Forcing every
  single-turn step into `:prompts [{:name …}]` was rejected as name/vector
  ceremony on the common case, harming terseness/simplicity (`simple(code)`,
  `context: minimal > comprehensive`).

This revises Q6/B3/AC-2 and the grammar precedence note accordingly.

## Ambiguity resolutions (E1–E3, pass 2)

These resolve the pass-2 ambiguity review's three findings. They refine, not
revise, the grammar and source-ref integration above.

### E1 — Shared sources are carried by the live session, not a step-level preload

The Q7/D1 "sources read once and reused" rationale is realized by the **live
shared child session**, not a step-level shared-preload field. Because the
`:contributions` **xor** `:prompts` precedence leaves a `:prompts` step with no
step-level `:contributions`, and the grammar deliberately avoids a canonical
`:preload` field (`doc/workflow-grammar-concepts.md`, "Session construction"),
the design **does not** add a step-level shared-contribution/preamble field. The
earlier undecided "list of prompt strings layered over a shared preload"
alternative is withdrawn.

Mechanism: the **first** prompt-group loads the shared source material (its own
`:contributions`/`:prompt-workflow` read the design + architecture sources) into
the child session on turn 1; every later prompt-group runs its turn against the
**same live child session**, so the model sees the already-loaded sources via
conversation memory rather than re-embedding them. The sources are therefore
assembled into the conversation exactly once. This is the same shared-context
mechanism that animates the feature (preserving conversational context across
turns) and keeps the queue representation uniform (each prompt-group is one
submission point). See the Architecture-alignment "Shared source material"
bullet.

### E2 — Prompt-group internal authoring precedence

Within a single prompt-group the prompt body is authored by `:prompt-workflow`
**xor** `:contributions`, mirroring the step-level xor one level down. Exactly
one must be present: **both** ⇒ IR-validation error; **neither** ⇒
IR-validation error (no prompt body to submit). Reported fail-fast at
workflow-load / IR-normalization time like the other grammar/source-ref
validation errors. Both forms materialize to one submitted prompt for that
group's turn; the step-level session config (`:model`/`:tools`/`:skills`) is
shared by all prompt-groups regardless of internal form. See the grammar
precedence note.

### E3 — Same-step / sibling-prompt-group `:prompt` refs are invalid (first cut)

The uniform `:prompt` source-ref resolution (Q12) resolves only against **prior
steps'** recorded per-prompt records. A `:prompt` ref whose `:step` is the
**same step currently being assembled** — a prompt-group referencing a sibling
prompt-group in its own step via `{:step <self> :prompt p :output k}` (forward
or back) — is **invalid in the first cut**, because that is exactly the
**cross-turn workflow data flow** Scope defers (template-injecting a prior
same-step turn's reply into a later prompt). This reconciles the "uniform
resolution" wording with the Scope deferral: uniform across the substrate means
*for refs to prior steps*; a self/same-step `:prompt` ref has no value at
assembly time and is rejected at IR-validation time (added to the "Source-ref
integration for `:prompt`" validation enumeration). Cross-turn context is still
available **at the model level** through the shared live session (E1); only
workflow-level template injection of a sibling's reply is withheld. Lifting this
restriction is the deferred cross-turn-data-flow follow-on.

## Inconsistency resolutions (C2, pass 2)

This resolves the pass-2 inconsistency review's finding. It refines, not
revises, the in-scope exemplar rewrite (Scope / Q7 / Q11).

### C2 — `final-summary` migrates to per-prompt `:output` refs after the merge

The referenced `review-task-design.edn`'s surviving `final-summary` step
currently consumes the three review phases via three contributions
`{:step "architecture-review" :yield :text}`,
`{:step "ambiguity-review" :yield :text}`, and
`{:step "inconsistency-review" :yield :text}`. The exemplar merge (Q7/D1)
collapses those three review **steps** into one multi-prompt `design-review`
step, so those three step names cease to exist and `final-summary` can no longer
address them.

`:yield` cannot recover the per-phase text after the merge: B1(b) makes
per-prompt `:yield` (`{:step s :prompt p :yield k}`) **invalid**, and the merged
step's step-level `:yield :text` resolves to only the **last** prompt's reply
(the unchanged session-step default, AC-3/B1). So the three reviews' text is no
longer reachable through `:yield`.

Resolution: as part of the in-scope exemplar rewrite, `final-summary`'s three
per-phase contributions migrate from
`{:step "<phase>-review" :yield :text}` to per-prompt **`:output`** refs against
the merged step:

```clojure
{:step "design-review" :prompt "architecture"   :output :final-llm-reply}
{:step "design-review" :prompt "ambiguity"      :output :final-llm-reply}
{:step "design-review" :prompt "inconsistency"  :output :final-llm-reply}
```

This is the **only** legal per-phase text addressing once the three review steps
merge: per-prompt `:final-llm-reply` is exactly the per-phase reply surface
(AC-3), reached via the `:prompt` discriminator on the canonical source ref
(Q12). It is the same per-prompt reply addressing already load-bearing for the
merged step's post-drain routing (Q8), so `final-summary` reuses the addressing
the merge already requires — no new surface is introduced. This makes the
exemplar internally consistent: the merge eliminates the three review step names,
and every downstream consumer (routing judge + `final-summary`) addresses the
per-phase replies through `{:step "design-review" :prompt "<phase>" :output
:final-llm-reply}`.

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
- **Q6 — Degenerate cases.** ✅ (Revised — D2 architectural-fit pass 2.) Empty
  `:prompts` = IR-validation error. Single-prompt `:contributions` authoring
  stays available but is **internally normalized into the one unified
  prompt-queue representation** (a one-element **unnamed** prompt-group), so the
  runtime has **one** execution path and single-prompt is the **N=1 degenerate**
  of it (not a separately maintained byte-for-byte path). Behaviour preservation
  is a *consequence* of N=1, not the design driver (`λα. ¬compat(backward)`).
- **Q7 — Exemplar.** ✅ (Revised — C1 inconsistency reconcile, then D1
  architectural-fit pass 2.) Merge the **three** review phases of
  `review-task-design.edn` — `architecture-review` + `ambiguity-review` +
  `inconsistency-review` — into one multi-prompt `:session` step so the task
  design + architecture sources are read once and reused for all three reviews
  (token efficiency + shared conversational context across the three reviews).

  **This is a deliberate topology redesign, not a faithful refactor (D1).** The
  live `review-task-design` topology is per-phase
  **review→follow-up(mutates design.md)→next review**: each `*-follow-up`
  executes the recorded items and mutates `design.md`/`design-steps.md` *before*
  the next phase reviews. A multi-prompt step is architecturally *N turns in one
  session → one post-drain route* (Q5/Q8), so it **cannot** reproduce the
  interleaved per-phase follow-up; merging reviews all three against the **same
  un-followed-up** design. The merge therefore deliberately **trades** the live
  topology's interleaved per-phase follow-up for **batch-review-then-follow-up**:
  the three per-phase `*-follow-up` steps collapse into a **single**
  `design`-profile follow-up step placed *after* the merged review step's
  post-drain route, executing the items accumulated across all three reviews in
  one pass. This is sanctioned by `λα. ¬compat(backward)` (topology change is
  allowed; behaviour preservation is not a goal). The earlier "matches the real
  workflow's three-phase topology / faithful to the live topology" framing is
  **withdrawn** as an architectural-fit overclaim (D1).

  The merge still (b) makes the post-drain **routing disjunction** the **exact**
  disjunction the real `clarity-status` already computes (Q8) — that equivalence
  is about the routing computation only, not the lost interleaved-follow-up
  structure — and (c) fully realizes the token-efficiency rationale (no review
  re-reads the sources separately). Routing for the merged step is over the
  **per-prompt reply outputs** via the existing `pass-feedback-routing` family
  (Q8) — the same workflow-data-flow routing the unmerged steps already use, not
  new filesystem-state routing. The surviving `final-summary` step likewise
  migrates its three per-phase `{:step "<phase>-review" :yield :text}`
  contributions to per-prompt `{:step "design-review" :prompt "<phase>" :output
  :final-llm-reply}` refs, the only legal per-phase text addressing after the
  merge (C2).

Resolved (continued):

- **Q8 — Routing/follow-up for the exemplar.** ✅ (Revised — A1 architectural-fit
  reconcile, then C1 inconsistency reconcile.) The merged step's judge routes on
  the **post-drain step result's per-prompt reply outputs**, reusing the
  **existing** `workflow/pass-feedback-routing` operation: each of the **three**
  review prompts (`architecture`, `ambiguity`, `inconsistency`) emits a
  `PASS_STATUS` token in its reply, and the judge takes one `*-text` arg per
  prompt
  (`{:architecture-text {:from {:step s :prompt "architecture" :output
  :final-llm-reply}} :ambiguity-text {:from {:step s :prompt "ambiguity" :output
  :final-llm-reply}} :inconsistency-text {:from {:step s :prompt "inconsistency"
  :output :final-llm-reply}}}`), REPEAT-ing while any prompt returned
  `ACTIONABLE_FEEDBACK`, DONE when all are `REVIEW_COMPLETE`. Because all three
  review phases are merged (Q7, revised), this is **exactly** the three-key
  disjunction the live `clarity-status` judge already computes via
  `pass-feedback-routing` over the unmerged `review-task-design` phase outputs
  (`:architecture-text`, `:ambiguity-text`, `:inconsistency-text`) — the
  equivalence is now genuine, with no `*-text` key dropped. (Per D1: this
  equivalence is about the post-drain **routing disjunction** only — the merged
  step does **not** reproduce the live per-phase interleaved follow-up; the three
  `*-follow-up` steps collapse into one post-route `design`-profile follow-up.
  See Q7.)

  **Why not filesystem-state routing.** An earlier draft proposed routing on
  whether `design-steps.md` still has unchecked items via a new
  `workflow/open-checklist-items-routing` that re-reads the file. Rejected: even
  though the workflow-runtime boundary (generic op + authored path) would be
  satisfied, routing on mutable external file state makes the routing decision
  depend on data **outside** the workflow data-flow / event log. That fights
  `doc/workflows.md`'s deliberate decision that `clarity-status` "remembers
  whether any phase returned `ACTIONABLE_FEEDBACK` from the phase outputs rather
  than re-reading task artifacts after follow-up execution," and the VSM
  `∀change → event → log → replayable` ethos (the judge contract normalizes a
  *step result*, not a filesystem snapshot). Routing on per-prompt reply outputs
  keeps the merged exemplar replay-faithful and deterministic and introduces no
  new routing operation. Consequence: the exemplar's routing **does** depend on
  per-prompt reply addressing, which is shipped in the first cut — see Q11.
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
    and is now **load-bearing for the exemplar's routing** (Q8, revised) and for
    the merged exemplar's `final-summary` step, whose three per-phase
    contributions migrate from `{:step "<phase>-review" :yield :text}` to
    per-prompt `{:step "design-review" :prompt "<phase>" :output
    :final-llm-reply}` refs once the three review steps merge (C2 — the only
    legal per-phase text addressing under B1(b));
  - **step-level** structured output bound to the **final** prompt's turn (no
    new structured-output machinery).
  Deferred to a follow-on task (until an exemplar needs it):
  - per-prompt structured `:outputs`.

- **Q12 — Per-prompt source-ref integration & per-turn recording (A2/A3).** ✅
  The `{:step s :prompt p :output k}` selector is an optional `:prompt`
  discriminator on the canonical prior-step source ref, resolved uniformly
  across the shared substrate (invoke args, contributions, template vars,
  delegated context), with compile-time validation that rejects `:prompt`
  against non-session steps, single-prompt session steps, unknown prompt-groups,
  and structured-output keys (mirroring the existing "output not exposed by that
  step type is invalid" rule). See "Source-ref integration for `:prompt`". Each
  queued prompt's turn result is recorded as a named entry in the canonical
  step-result/progression substrate (introspectable + replay-faithful), with the
  step still emitting one post-drain `:pending-actor-result` for one routing
  decision (reconciling Q5). See "Architecture alignment".

All open questions are resolved; the design is ready for planning.
