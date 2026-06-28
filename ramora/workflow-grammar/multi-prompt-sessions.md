# Workflow Grammar — Multi-Prompt Sessions

A session step authors **either** a single-prompt `:contributions` body **or**
an ordered `:prompts` queue of named prompt-groups — never both. The two forms
share one internal prompt-queue mechanism; single-prompt `:contributions` is the
N=1 degenerate (one unnamed group). The distinction is per-prompt **addressing
capability**, not behaviour: author `:prompts` when you want multiple turns in
one shared session or named per-prompt addressing; otherwise use
`:contributions`.

```clojure
{:name "design-review"
 :type :session
 :tools ["read"]                                ; session config is per-step, shared by all groups
 :prompts
 [{:name "architecture" :prompt-workflow "review-architecture.md"}
  {:name "ambiguity"    :contributions [{:type :template :text "..." :vars {}}]}]}
```

Each prompt-group materializes to one submitted prompt that runs one model turn
against the **same** child session, in author order; the next group's turn is
submitted only after the prior turn completes. Session config (`:model`,
`:tools`, `:skills`, …) is declared once at the step level and shared by every
group — there is no per-prompt model/tools/skills, and there is no step-level
shared `:contributions` preamble (the first group loads shared sources on turn
1; later groups see them via the live session's conversation memory).

## Precedence and Validation Rules

- **Step-level precedence** — `:contributions`/`:prompt-workflow` **xor**
  `:prompts`. Declaring both on one session step is an error; a session step
  must declare exactly one prompt source.
- **Group-internal precedence** — within a prompt-group the body is
  `:prompt-workflow` **xor** `:contributions` (mirroring the step-level rule):
  declaring both, or neither, is an error.
- **Non-empty** — an empty `:prompts` vector is rejected; a one-element
  `:prompts` is valid (and runs the multi-prompt path with per-prompt
  addressing, distinct from the `:contributions` single-prompt form).
- **Named, unique within a step** — every `:prompts` group is named, and group
  names are unique within a step (`(step-name, prompt-name)` is the addressing
  handle). Names may repeat across different steps.

All of these rules are reported fail-fast at workflow load / IR validation.

## Later-Group Single-Submission Limitation

Each prompt-group submits **one** message per turn. The **first** group's
materialized conversation is split into preloaded messages plus a final prompt,
and the preloaded messages are injected when the shared child session is spawned
— so a multi-message first group is honoured in full. **Later** groups, however,
submit **only** their final message: a later group's body materializes against
the already-live session and is split the same way, but its preloaded (non-final)
messages are **not** re-injected mid-session. Later groups instead rely on the
live session's conversation memory for shared context (the first group's loaded
sources persist across turns).

Consequently, a later prompt-group whose `:contributions` materialize to **more
than one message** silently drops every non-final message. Author multi-message
bodies as the first group, or keep later groups to a single submission — the
common `:prompt-workflow` (single user message) form always satisfies this.

## Drain and Routing

The prompt-queue **drains** before the step routes: every group's turn runs (in
author order) and is recorded, and only then does the step's single post-drain
result — and any step `:judge`/`:on` routing — run. A multi-prompt step is still
**one** workflow step with **one** routing decision; the N turns are internal to
that step, not separate steps. Concretely:

- The step's `:final-llm-reply` is the **last** group's reply; its `:transcript`
  is **accumulated** across every group's turn.
- A declared step-level structured `:output` is requested on the **final** turn
  only.
- Each **named** group additionally records a per-prompt turn record (its own
  turn-local `:final-llm-reply`/`:transcript`), so completed turns are
  introspectable; the unnamed single-prompt (`:contributions`) degenerate
  records only the step-level rollup. Other steps (and the step's own post-drain
  `:judge`) address a named group's turn-local surface via the `:prompt`
  source-ref discriminator — see *Per-prompt output surfaces* in
  [workflow-grammar-concepts.md](../workflow-grammar-concepts.md).

## Resume and Idempotency

The queue's position is reconstructed **purely from the recorded per-prompt turn
records**, never from an in-memory counter. The realized guarantee is a
**structural progression guard**: on **every** iteration the queue-driving loop
re-reads which group indices already have a recorded turn and submits the
**lowest un-run** group next, so the next prompt is derived from recorded
progression alone. A prompt whose turn record already exists is **never**
re-submitted, so its non-deterministic model turn (`ai/generate`) never re-fires.

This makes a multi-prompt step idempotent: re-driving a partially recorded queue
runs only the remaining un-run prompts and reproduces the same ordered per-prompt
records, and re-driving a fully recorded queue runs **zero** turns and proceeds
straight to the single post-drain result/route. The post-drain route is reached
only once every group has a recorded turn. The idempotency property is validated
by re-driving against a **reconstructed** queue state — the same observable an
async restart or replay would produce.

> **Realized vs. target.** As built the drain is **synchronous**: the whole
> queue drains inside one step action with no mid-drain suspend, so an async
> turn-completion resume, a process restart, or an event-log replay re-entering
> mid-drain is a **not-yet-realized target**, not an occurring runtime path. What
> *is* realized today is the structural progression guard above — the per-iteration
> re-read of recorded per-prompt progression. The async suspend/resume contract
> (continue-from-progression across an actual process restart / replay) is the F1
> target the synchronous drain stands in for; the progression guard is exactly
> the mechanism a future async resume would reconstruct from.

## Abort, Cancellation, and Blocked Outcomes

When a turn does not complete successfully the queue stops and the step routing
is **skipped** — the drain never produces a successful post-drain result. The
per-prompt turn records of groups that completed **before** the aborting turn are
retained and introspectable; the aborting group itself leaves **no** completed
turn record. There are three non-success terminal outcomes:

- **`:failed`** — a turn errors. The failure payload names the failing prompt
  (`:failed-prompt {:index … :name …}`). An error at **any** position — including
  the **last** prompt — follows the same `:failed` abort. The single-prompt
  (`:contributions`) degenerate fails the same way, with no prompt name (no named
  group), byte-equivalent to today's single-prompt failure.
- **`:cancelled`** — the run is cancelled. Whether the cancel lands between turns
  or while a turn is in flight, the outcome is the same terminal `:cancelled`; an
  interrupted in-flight prompt leaves no record, and only prompts completed
  before the cancel are retained.
- **`:blocked`** — structured-output viability fails. An invalid structured-output
  **request** is checked **upfront before turn 1** (fail-fast: zero turns run,
  zero records). An `:unsupported-structured-output` or `:invalid-structured-output`
  block can only arise on the **final** turn (structured output is requested on
  the final turn only), yielding a terminal `:blocked` after the prior turns ran
  and were recorded, with the blocking final prompt leaving no record.
