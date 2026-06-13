# 226 — Multi-prompt workflow steps

## Status

Design — complete and ready for planning. This task delivers the **capability**
(multi-prompt `:session` steps). The motivating consumer — merging the
`review-task-design.edn` review phases into one multi-prompt step — is split out
as task **227** (depends on this task), to keep this task to a single intent.

## Intent

Allow a single `:session` workflow step to carry **more than one prompt**, as an
ordered queue. The first prompt is submitted to the step's child session and
runs one model turn to completion; when that turn finishes, the next queued
prompt is submitted into the **same** child session and runs its own turn; and
so on until the queue drains. Routing/judging happens once, after the drain.

The animating goal is **scripted multi-turn conversation within one session
step, preserving conversational context across turns**, without forcing authors
to split work into separate session steps (which each construct a *fresh* child
session and therefore lose prior turns' context).

## Problem

Today a `:session` step runs exactly one actor turn:

- `materialize-step-session-conversation` assembles ordered `:contributions`
  into a conversation.
- `split-step-session-conversation` preloads all but the final user text message
  and treats that final message as **the** prompt.
- `execute-session-step!` runs **one** `execute-actor-turn!` and then routes.

So "do A, then once A is done, do B in the same context" can only be expressed by
(1) cramming both asks into one prompt (no enforced turn boundary; no
intermediate reply to anchor B on), or (2) two session steps (separate child
sessions; B loses A's conversation, tool history, accumulated reasoning).
Neither expresses sequential prompts sharing one evolving session with a real
model turn boundary between them.

## Scope

In scope:

- A `:session`-step grammar extension expressing an **ordered list of prompts**,
  each submitted as its own turn into the same child session.
- Sequential execution: prompt _n+1_ is submitted only after prompt _n_'s turn
  completes.
- Output surfaces: step-level (`:final-llm-reply` = last prompt, `:transcript` =
  accumulated) **plus per-prompt text addressing** `{:step s :prompt p :output
  k}` (the addressable handle that falls out of named prompt-groups; its first
  consumer is task 227).
- Step-level **structured output** applied to the **final** prompt's turn (the
  existing single `:outputs` structured entry; unchanged granularity).
- The `:prompt` source-ref discriminator integrated uniformly into the shared
  data-reference substrate, with compile-time validation.
- Error/cancellation behaviour across the queue.
- Documentation (`doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`)
  and grammar/runtime tests.

Out of scope (candidate follow-on tasks):

- **The review-task-design exemplar rewrite** — task **227**.
- **Per-prompt structured `:outputs`.** First cut keeps structured output
  step-level / final-turn only.
- **Cross-turn workflow data flow**: a later prompt template-referencing an
  earlier same-step turn's reply via source-refs. Prompts share the live session
  context (the model sees prior turns), but no workflow-level source-ref injects
  a sibling same-step turn's reply text into a later prompt's template.
- Conditional/looping prompt queues (a queue is linear; loops still use
  step-level `:on`/`:max-iterations`).
- Per-prompt distinct tools/skills/model (session config is per-step, shared).
- Per-prompt judges/routing (a step has exactly one judge + `:on`, after drain).
- Multi-prompt LLM `:judge` sub-steps (judging stays single-turn).
- Multi-prompt `:delegate` steps (delegation boundary unchanged).

## Minimum concepts

- **Prompt queue**: an ordered sequence of prompt-groups on a `:session` step.
- **Prompt-group**: assembly material (`:prompt-workflow` xor `:contributions`)
  rendering to one submitted prompt for one turn, plus a `:name` when authored
  under `:prompts`.
- **Turn boundary**: a completed `execute-actor-turn!` (full model reply incl.
  tool loop) is where the next queued prompt is submitted.
- **Drain**: the step is complete and eligible for routing only after every
  queued prompt's turn finishes (or the queue aborts on failure/cancellation).

## Acceptance criteria

1. A `:session` step can author N ≥ 1 prompts; they execute in author order,
   each a separate turn in the **same** child session, prompt _n+1_ submitted
   only after prompt _n_'s turn completes.
2. A single-prompt step behaves exactly as today **as the N=1 degenerate of one
   unified prompt-queue runtime path** (not a separately maintained path). The
   `:contributions` single-prompt authoring keeps working because a length-1
   queue reproduces today's single-turn behaviour — a *consequence* of the
   unified mechanism, not a back-compat guarantee.
3. Output surfaces:
   - step-level `:final-llm-reply` = **last** prompt's reply; step-level
     `:transcript` = conversation accumulated across all turns; so
     `{:step s :output k}` without `:prompt` stays single-prompt back-compatible.
   - per-prompt `:final-llm-reply`/`:transcript` are **turn-local** (that
     prompt's own reply / its own turn slice), addressable via
     `{:step s :prompt p :output k}`.
   - the step's **yielded** value as a whole is the unchanged session-step
     default — text sourced from the step-level `:final-llm-reply`; the `:prompt`
     discriminator applies to `:output` refs only, never to `:yield`.
   - a step-level structured output, if declared, applies to the **final**
     prompt's turn.
4. The judge/`:on` routing runs **once**, after the queue drains, against the
   post-drain step result; the judge may reference per-prompt output surfaces.
5. If an intermediate turn errors, the queue stops there and the step surfaces a
   `:failed` outcome whose payload names the failing prompt; no further prompts
   are submitted; routing is skipped.
6. Cancellation between queued prompts short-circuits to a terminal
   `:cancelled` outcome (distinct from AC-5 `:failed`): no further prompts are
   submitted, the judge/`:on` routing does **not** run, and already-completed
   per-prompt turn records are retained and introspectable.
7. `doc/workflow-grammar.md` + concepts doc describe the multi-prompt form;
   grammar IR validation and runtime tests cover ordering, drain, single-prompt
   N=1 equivalence, per-prompt addressing + its validation, intermediate-failure
   abort, and cancellation between prompts.

## Grammar shape

The grammar deliberately avoids canonical `:prompt`/`:input` fields and subsumes
conversation assembly into ordered `:contributions`. The multi-prompt form fits
that philosophy: **`:prompts` is an ordered vector of named prompt-groups**, each
materializing to one submitted prompt and running one turn against the shared
child session, in author order.

```clojure
{:name "design-review"
 :type :session
 :tools [...] :skills [...]                 ; per-step session config (shared by all groups)
 :prompts
 [{:name "architecture"
   :prompt-workflow "…-architecture-review.md"} ; per group: :prompt-workflow XOR :contributions
  {:name "ambiguity"
   :contributions [...]}]
 :outputs {...}?                             ; step-level; structured entry → final turn
 :judge {...}                                ; one judge, after drain
 :on {...}}
```

Each prompt-group reuses the existing
`materialize-step-session-conversation` + `split-step-session-conversation`, so
the runtime turn primitive is unchanged — multi-prompt loops the one-turn
primitive N times against the same session id.

**Authoring precedence.** A `:session` step uses `:contributions`
(/`:prompt-workflow`) **xor** `:prompts`. **Both forms normalize at IR time into
the same internal prompt-queue representation** (an ordered vector of
prompt-groups), so the runtime drives **one** queue path: `:contributions`
normalizes to a queue of one **unnamed** prompt-group (step-level surfaces only,
no `:prompt` namespace); `:prompts` normalizes to **named** prompt-groups
(per-prompt addressing). The two authoring surfaces are not rewritten *into each
other* — they share the internal representation. The distinction is **per-prompt
addressing capability** (the forward-looking axis this feature introduces), not
backward compatibility; single-prompt is the N=1 degenerate, not a separate
path. Choosing the obvious form is singular per intent (`λone_way`): need
multiple turns or named addressing → `:prompts`; otherwise → `:contributions`.

- empty `:prompts` ⇒ IR-validation error;
- one-element `:prompts` ⇒ valid; its single named group exposes per-prompt
  addressing;
- prompt-group `:name`s must be **unique within the step** (duplicates =
  IR-validation error); `(step-name, prompt-name)` is the addressing handle, so
  names may repeat across distinct steps.

**Prompt-group internal precedence.** Within a group the body is authored by
`:prompt-workflow` **xor** `:contributions`, mirroring the step-level rule one
level down: **both** ⇒ IR-validation error; **neither** ⇒ IR-validation error
(no body to submit). Both forms materialize to one submitted prompt; the
step-level session config (`:model`/`:tools`/`:skills`) is shared by all groups.

**Shared source material is carried by the live session, not a step-level
preload.** There is no step-level shared `:contributions`/preamble distinct from
the prompt-groups (the xor precedence forbids step-level `:contributions` on a
`:prompts` step, and the grammar deliberately has no canonical `:preload`
field). The **first** prompt-group loads shared sources into the child session
on turn 1; every later group's turn runs against that **same live child
session**, so the model sees the already-loaded sources via conversation memory
without re-embedding them. This is the same shared-context mechanism that
animates the feature (preserving context across turns) and keeps the queue
representation uniform (each group is one submission point).

## Source-ref integration for `:prompt`

The per-prompt selector `{:step s :prompt p :output k}` is an **optional
`:prompt` discriminator** layered onto the canonical prior-step source ref
`{:step s :output k}`. It lives in the **shared** data-reference substrate, so it
resolves uniformly everywhere a source ref is admitted (invoke `:args`, session
`:contributions` source items, template `:vars`, delegated context) — no
selector-specific code path per call site.

Resolution:

- `{:step s :output k}` (no `:prompt`) against a multi-prompt step resolves to
  the **step-level** surface (last prompt's `:final-llm-reply`, accumulated
  `:transcript`), preserving single-prompt back-compat.
- `{:step s :prompt p :output k}` resolves to prompt-group `p`'s own turn-local
  surface within step `s`'s recorded per-prompt turn records.

Compile-time IR validation (mirroring the existing "a reference that selects an
output not exposed by that step type is invalid" rule). A `:prompt` selector is
**invalid** when:

- step `s` is not a `:session` step;
- step `s` is single-prompt (`:contributions`/`:prompt-workflow`, no
  prompt-group namespace);
- `p` does not name a declared prompt-group of `s`;
- `k` is not a per-prompt **text** surface (`:final-llm-reply`/`:transcript`); a
  `:prompt` selector against a structured `:output` key is invalid (per-prompt
  structured output is deferred);
- it is a `:yield` ref — `{:step s :prompt p :yield k}` is invalid (the yielded
  value is the step's value as a whole; `:prompt` applies to `:output` only);
- it targets the **same step currently being assembled** (a prompt-group
  referencing a sibling group in its own step, forward or back) — this is the
  deferred cross-turn workflow data flow. Sibling turns remain **visible to the
  model** through the shared live session; what is withheld is template-injecting
  a sibling's reply via a source-ref.

All reported fail-fast at workflow-load / IR-normalization time.

## Architecture alignment

- The conversation/turn substrate already exists (`execute-session-step!` →
  `execute-actor-turn!` → `execute-session-turn!` runs one bounded turn against a
  persistent child session id). Multi-prompt is "call the existing turn primitive
  N times against the same session id" — no new turn mechanics; the change is in
  how the step drives the queue and in IR/materialization shaping.
- Workflow-runtime boundary (`AGENTS.md`): the prompt **queue mechanism** is
  generic runtime; concrete prompts are authored content. No workflow-specific
  business rules enter runtime code.
- **One unified queue path.** Both authoring forms normalize into the one
  internal prompt-queue representation; single-prompt is its N=1 degenerate.
  Satisfies `λone_way` at the mechanism level; no parallel paths to drift.
- **Per-turn results recorded in the canonical step-result/progression
  substrate, not transient loop locals.** Each queued prompt's completed turn is
  recorded as a named prompt-group entry, so every intermediate turn is
  introspectable (S4) and replay-faithful (`∀change → event → log → replayable`).
  The step still emits **one** post-drain `:pending-actor-result` for the **one**
  routing decision, but that envelope carries an ordered collection of per-prompt
  turn records (keyed by prompt `:name`, each exposing
  `:final-llm-reply`/`:transcript`) plus the step-level rollup. This reconciles
  "one statechart step / one attempt / one route" (Q5) with "N internal turns":
  the N turns are an internal loop **inside** one statechart step attempt, not N
  statechart steps.
- **Model fallback applies per turn.** Each queued prompt's turn independently
  attempts ranked-model fallback (`execute-with-ranked-fallback!`); a model
  switch persists to subsequent turns in the same session.

## Resolved design decisions

- **Grammar = `:prompts` of named groups**; `:contributions` xor `:prompts`;
  both normalize to one internal queue; single-prompt = N=1 degenerate.
- **Turn = one full `execute-actor-turn!`** (model reply incl. tool loop); next
  prompt submits only after it completes.
- **Output surfaces** as AC-3: step-level rollup + per-prompt turn-local text
  addressing; `:prompt` on `:output` only, never `:yield`; per-prompt
  `:transcript` = that prompt's own turn slice.
- **Structured output** stays step-level, bound to the **final** prompt's turn;
  per-prompt structured output deferred.
- **Statechart accounting**: N turns = internal loop in one step attempt, one
  pending-actor result, one routing decision; per-turn records canonical.
- **Cancellation** between prompts ⇒ terminal `:cancelled` (no routing, partial
  records retained); **intermediate error** ⇒ `:failed` + failing-prompt name
  (no routing).
- **Model fallback** per turn; switch persists across later turns.
- **`:prompt` source-ref** discriminator on the shared substrate with the
  compile-time validation enumerated above.
