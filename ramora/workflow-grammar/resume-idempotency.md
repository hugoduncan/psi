# Multi-Prompt Sessions — Resume, Idempotency, and Abort

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
