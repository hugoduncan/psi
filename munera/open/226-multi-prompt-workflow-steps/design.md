# 226 — Multi-prompt workflow steps

## Status

Design complete; ready for planning. Delivers the **capability** only; the
motivating consumer (merging `review-task-design.edn`'s review phases) is task
**227**, which depends on this.

## Intent

Let a single `:session` step carry an **ordered queue of prompts**. Each prompt
runs one model turn to completion in the **same** child session; the next is
submitted only after the prior turn finishes; routing/judging runs once, after
the queue drains. This expresses **scripted multi-turn conversation that
preserves context across turns** — impossible today without either cramming
asks into one prompt (no turn boundary) or using separate steps (separate child
sessions that lose prior context).

"Submitted only after the prior turn finishes" is a **logical ordering**
constraint, not a synchronous in-thread loop: each turn is an async
`ai/generate` effect that suspends the run, so the drain is realized as N
suspend/resume cycles within one statechart step, resuming from recorded
progression (see Architecture alignment, F1).

## Problem

A `:session` step today runs exactly one actor turn: `:contributions`
materialize into a conversation, `split-step-session-conversation` preloads all
but the final user message as **the** prompt, and `execute-session-step!` runs
one `execute-actor-turn!` then routes. There is no way to run prompt B as a
distinct turn after prompt A within one evolving session.

## Scope

In scope:

- `:session`-step grammar for an ordered prompt queue; sequential turns (prompt
  _n+1_ after prompt _n_ completes).
- Output surfaces: step-level (`:final-llm-reply` = last prompt, `:transcript` =
  accumulated) **plus** per-prompt text addressing `{:step s :prompt p :output
  k}`.
- Step-level **structured output** bound to the **final** prompt's turn (the
  existing single `:outputs` entry; unchanged granularity).
- `:prompt` source-ref discriminator on the shared data-reference substrate,
  with compile-time validation.
- Error/cancellation behaviour across the queue.
- Docs (`doc/workflow-grammar.md`, `-concepts.md`) + grammar/runtime tests.

Out of scope (follow-ons):

- The review-task-design exemplar rewrite — task **227**.
- **Per-prompt structured `:outputs`** (kept step-level / final-turn only).
- **Cross-turn workflow data flow** — a later prompt template-referencing an
  earlier same-step turn's reply via source-ref. (The model still sees prior
  turns via the shared live session; only workflow-level injection is withheld.)
- Conditional/looping queues (a queue is linear; loops use step `:on`/
  `:max-iterations`).
- Per-prompt tools/skills/model (session config is per-step, shared).
- Per-prompt judges/routing; multi-prompt `:judge` sub-steps; multi-prompt
  `:delegate` steps.

## Concepts

- **Prompt-group**: assembly material (`:prompt-workflow` xor `:contributions`)
  rendering to one submitted prompt for one turn; named when authored under
  `:prompts`.
- **Turn boundary**: a completed `execute-actor-turn!` (full reply incl. tool
  loop) — where the next queued prompt is submitted.
- **Drain**: routing is eligible only after every prompt's turn finishes (or the
  queue aborts on failure/cancellation).

## Acceptance criteria

1. A `:session` step can author N ≥ 1 prompts; they run in author order, each a
   separate turn in the **same** child session, prompt _n+1_ after prompt _n_.
2. A single-prompt step behaves exactly as today, as the **N=1 degenerate of one
   unified prompt-queue runtime path** (not a separately maintained path); the
   equivalence is a consequence of the unified mechanism, not a back-compat
   guarantee.
3. Output surfaces:
   - step-level `:final-llm-reply` = **last** prompt's reply; step-level
     `:transcript` = accumulated across all turns; `{:step s :output k}` without
     `:prompt` stays single-prompt back-compatible.
   - per-prompt `:final-llm-reply`/`:transcript` are **turn-local**, addressable
     via `{:step s :prompt p :output k}`.
   - the step's **yielded** value is the unchanged session-step default (text
     from the step-level `:final-llm-reply`); `:prompt` applies to `:output`
     refs only, never `:yield`.
   - a declared step-level structured output applies to the **final** turn.
4. Routing runs **once**, after the drain, over the post-drain step result; the
   step's own post-drain `:judge` may reference its prompt-groups' per-prompt
   surfaces via `{:step s :prompt p :output k}` — the **permitted** same-step
   `:prompt` case, since the judge resolves after every turn is recorded, unlike
   assembly-time contributions/templates (see Source-ref integration).
5. Intermediate turn error ⇒ queue stops, step `:failed` with payload naming the
   failing prompt, routing skipped; per-prompt turn records for prompts completed
   **before** the failing one are retained and introspectable (symmetric with
   AC-6's cancellation path on S4 introspectability); the failing prompt leaves
   **no** completed turn record — it is identified only by the `:failed` payload,
   which carries the error.
6. Cancellation between prompts ⇒ terminal `:cancelled` (distinct from `:failed`):
   queue stops, routing skipped, completed per-prompt turn records retained and
   introspectable.
7. On resume (async turn completion, process restart, replay) the queue
   continues at the next **un-run** prompt from recorded per-prompt progression;
   a prompt whose turn record already exists is never re-submitted (no
   re-fire of its `ai/generate` effect). Routing is reached only after every
   prompt has a recorded turn.
8. Docs describe the form; IR-validation + runtime tests cover ordering, drain,
   N=1 equivalence, per-prompt addressing + its validation, intermediate-failure
   abort, inter-prompt cancellation, and resume-from-progression idempotency
   (a mid-queue resume runs only the un-run prompts).

## Grammar

`:prompts` is an ordered vector of named prompt-groups, each materializing to one
submitted prompt and running one turn against the shared child session, in
author order. Each group reuses the existing
`materialize-step-session-conversation` + `split-step-session-conversation`, so
the turn primitive is unchanged — multi-prompt just loops it N times against the
same session id.

```clojure
{:name "design-review" :type :session
 :tools [...] :skills [...]                 ; per-step config, shared by all groups
 :prompts
 [{:name "architecture" :prompt-workflow "…-architecture-review.md"} ; group: :prompt-workflow XOR :contributions
  {:name "ambiguity"    :contributions [...]}]
 :outputs {...}?                            ; step-level; structured entry → final turn
 :judge {...} :on {...}}                    ; one judge, after drain
```

**Step-level precedence.** A step uses `:contributions`(/`:prompt-workflow`)
**xor** `:prompts`. Both normalize at IR time into the **same internal
prompt-queue representation**, so the runtime drives **one** queue path:
`:contributions` → one **unnamed** group (step-level surfaces only); `:prompts`
→ **named** groups (per-prompt addressing). The forms are not rewritten into
each other; they share the internal representation, and single-prompt is the N=1
degenerate (no separate path). The distinction is per-prompt **addressing
capability**, not back-compat. `λone_way`: multiple turns or named addressing →
`:prompts`; else → `:contributions`.

- empty `:prompts` ⇒ IR-validation error; one-element `:prompts` ⇒ valid.
- prompt-group `:name`s unique **within a step** (duplicates = error); names may
  repeat across steps, since `(step-name, prompt-name)` is the handle.

**Group-internal precedence.** Within a group the body is `:prompt-workflow`
**xor** `:contributions` (mirroring the step-level rule): both ⇒ error; neither
⇒ error. Step-level session config is shared regardless of which form a group
uses.

**Shared sources via the live session, not a step-level preload.** There is no
step-level shared `:contributions`/preamble (the xor forbids it; the grammar has
no `:preload` field). The first group loads shared sources on turn 1; later
groups run against the **same live child session**, so the model sees them via
conversation memory without re-embedding. This is the same shared-context
mechanism that animates the feature and keeps each group a single submission
point.

## Source-ref integration for `:prompt`

`{:step s :prompt p :output k}` is an **optional `:prompt` discriminator** on the
canonical prior-step ref `{:step s :output k}`, living in the shared
data-reference substrate — it resolves uniformly wherever a source ref is
admitted (invoke args, contributions source items, template vars, delegated
context), with no per-call-site code path.

- `{:step s :output k}` (no `:prompt`) against a multi-prompt step → step-level
  surface (last prompt's reply, accumulated transcript) — back-compat.
- `{:step s :prompt p :output k}` → group `p`'s turn-local surface in step `s`.

Compile-time IR validation (mirroring "an output not exposed by that step type is
invalid"). A `:prompt` selector is **invalid** when: step `s` is not a `:session`
step; `s` is single-prompt (no group namespace); `p` is not a declared group of
`s`; `k` is not a per-prompt **text** surface (`:final-llm-reply`/`:transcript`)
— structured keys are invalid (per-prompt structured deferred); it is a `:yield`
ref (`:prompt` is `:output`-only); or it targets the **same step being
assembled** (sibling-group ref, forward or back) — the deferred cross-turn data
flow. All reported fail-fast at workflow-load / IR-normalization.

**Post-drain judge exception.** The step's own post-drain `:judge` is **exempt**
from the same-step invalid rule: it may address its prompt-groups via
`{:step s :prompt p :output k}`. This is not an assembly-time sibling-group ref —
the judge resolves **after the drain**, once every prompt's turn record exists
(A3), so the value is present and deterministic, unlike a contribution/template
that would reference a sibling turn that has not yet run. This carve-out is what
makes AC-4 ("the judge may reference per-prompt surfaces") consistent with the
same-step prohibition on assembly-time refs.

## Architecture alignment

- The turn substrate already persists a child session id across calls
  (`execute-session-step!` → `execute-actor-turn!` → `execute-session-turn!`).
  Multi-prompt adds no turn mechanics; the change is queue-driving + IR/
  materialization shaping. Per the workflow-runtime boundary, the queue
  mechanism is generic runtime; concrete prompts are authored content.
- **One unified queue path**: both authoring forms normalize to one internal
  representation; single-prompt = N=1 degenerate (`λone_way`, no drift).
- **Per-turn results are recorded in the canonical step-result/progression
  substrate**, not loop locals: the step emits **one** post-drain
  `:pending-actor-result` (one routing decision), but it carries ordered
  per-prompt turn records (keyed by `:name`, each exposing
  `:final-llm-reply`/`:transcript`) plus the step-level rollup. This keeps every
  intermediate turn introspectable (S4) and replay-faithful, reconciling "one
  statechart step / one attempt / one route" (Q5) with N internal turns (an
  internal loop, not N statechart steps).
- **Model fallback is per turn** (`execute-with-ranked-fallback!`); a switch
  persists to later turns in the same session.
- **Resume/suspend contract for the internal queue (F1).** The canonical runtime
  is resume/suspend-driven (`psi.workflow-runtime.statechart-runtime`;
  `psi.workflow-runtime.core` run resume; `resume-and-execute-run!`), and
  `ai/generate` is an **async effect that suspends the run** and resumes on turn
  completion. So N queued prompts mean **N suspend points inside the one
  statechart step** — the "synchronous drain" is logical (route only after all
  turns finish), not a single blocking loop. The queue commits to
  **resume-from-progression**: on every resume (async turn completion, process
  restart, replay) the queue-driving loop reads the recorded per-prompt
  progression (the same `psi.workflow-runtime.progression-recording` substrate
  that A3 writes each turn into) and **continues at the next un-run prompt**,
  never re-submitting a prompt whose turn record already exists. This makes the
  step idempotent under resume: a completed turn's side-effectful,
  non-deterministic `ai/generate` effect never re-fires, upholding the VSM
  `∀change → event → log → replayable` ethos and the design's replay-faithful
  claim. The suspend/resume boundary sits **inside** the single statechart step
  (one step, N internal turns), so resume re-enters the step and consults
  progression rather than restarting the queue; the post-drain
  `:pending-actor-result` / routing (Q5) is reached only once every prompt has a
  recorded turn. (Distinct from A3, which only *records* results, and from Q5,
  which is the single post-drain route; F1 is the *consume-to-resume* rule that
  ties them to the async runtime.)
