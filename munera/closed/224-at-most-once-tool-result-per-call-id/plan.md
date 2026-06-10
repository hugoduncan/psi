# 224 Plan — at-most-once toolResult per tool-call-id

Derived from the stable `design.md` (D1: Option C — canonical recorded-ids
predicate in `:state*`) and the resolved follow-ups in `design-steps.md`.

## Approach

Two complementary fixes plus docs, decomposed as vertical slices:

1. **Forward fix (prevent new duplicates).** Introduce a canonical, per-session
   `recorded-tool-result-ids` set in `:state*`, maintained only through dispatch.
   Turn the `:session/tool-agent-record-result` handler
   (`components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj:529`)
   into a **pure guarded both-or-neither transform**:
   - read the canonical recorded-ids set for `session-id` from `ctx`
     (`session/get-state-value-in`);
   - extract `tool-call-id` from `tool-result-msg` (`:tool-call-id`);
   - if `tool-call-id ∈ recorded-ids` → `applied? = false`: return **no**
     `:root-state-update` and emit **neither** effect (suppress both
     `:runtime/agent-record-tool-result` and the `append-message-effect`);
   - else → return a `:root-state-update` adding `tool-call-id` to recorded-ids
     **and** emit **both** effects.

   Atomicity comes from dispatch serialization (single writer to `:state*`), not
   a runtime test-and-set. The guard sits at the single funnel event all
   producers (three interrupt producers + the real-result re-dispatch) converge
   on, so it covers any producer regardless of count.

2. **Persistence/reset boundary.** `recorded-tool-result-ids` is **session
   lifetime** — cleared only at the session reset/clear boundary that discards
   the journal/history, **decoupled** from the per-turn `:pending-tool-calls`
   reset. The headline race is cross-turn (a late real result arrives after the
   turn that recorded the interrupt), so the id must outlive its turn. Natural
   bound: per-session tool-call count; removed with the session map.

3. **Defensive projection de-dup (recover already-wedged journals).** Add a
   single upstream chokepoint in `prompt_request/journal->provider-messages`
   (`components/agent-session/src/psi/agent_session/prompt_request.clj:111`) that
   drops a `toolResult`-role projected message whose `:tool-call-id` already
   appeared (first occurrence wins, purely derived from the journal). The
   downstream conversation rebuild (`turn_runtime/conversation.clj`
   `agent-messages->ai-conversation`, `conversation.clj:136`) consumes the
   de-duped list and is **not** a second de-dup site; the sole
   `tool_result`-block emitter is `conv/add-tool-result` (inside
   `append-tool-result-msg`, `conversation.clj:95`).

   **De-dup ordering = after `repair-dangling-tool-uses` (wrap its output).**
   `journal->provider-messages` currently returns
   `(repair-dangling-tool-uses (into [] …))` (`prompt_request.clj:119`). The
   de-dup must wrap that **repaired** list, not the pre-repair message list,
   because `repair-dangling-tool-uses` only scans the *contiguous* toolResult run
   after each assistant block (`split-with tool-result-message?`,
   `prompt_request.clj:96`): a real toolResult that is **non-contiguous** with its
   assistant tool-use block is treated as *missing* and a **synthetic**
   `interrupted-tool-result` is appended for the same id, so de-dup-before-repair
   can still leave two results for one id on a malformed (already-wedged) journal.
   De-dup applied to repair's output guarantees at-most-once **unconditionally**
   (de-dup removes extras *including* synthetics repair adds for non-contiguous
   ids; repair still adds missing for genuinely dangling blocks). Concretely:
   `(dedupe-tool-results (repair-dangling-tool-uses (into [] …)))`.

## Key decisions (from design, not re-litigated here)

- **Guard location = Option C** (canonical `:state*` predicate), Option B
  rejected — State-boundary + Dispatch-sequencing + cross-component-layering
  rationale in D1.
- **Deterministic guarantee = at-most-once**; model-visible winner =
  first-writer-wins by dispatch order. Not unconditional interrupt-first.
- **De-dup = single upstream chokepoint** at `journal->provider-messages`, keyed
  by journal `tool-call-id`, first occurrence wins.
- `:pending-tool-calls` (agent-core handle) retained for interrupt enumeration
  only; no longer gates effects.
- **recorded-ids default + clearing = init seeding** (plan/steps ambiguity
  follow-up item 3). Seed `:recorded-tool-result-ids #{}` in
  `initialize-session-slots` (`session_state/init.clj`, the journal/history-discard
  + session-init boundary alongside `:telemetry`). This supplies the `#{}` default
  **and** clears the set on every session-lifecycle reset, so no separate clear at
  the per-turn `:pending-tool-calls` reset and no standalone clear handler are
  needed. The read/update site keeps nil-safe `#{}` / `(fnil conj #{})` as
  defense-in-depth only.
- **Forward-fix repro = `:user-abort` synchronous `abort-in!` path** asserted at
  the **raw recorded layer** (plan/steps ambiguity follow-ups 1 & 2). The
  reproduction drives `turn.clj:233` `abort-in!` →
  `record-pending-tool-call-interrupts!` (not the statechart-effect
  `:deferred-interrupt` producer) and asserts exactly one `toolResult` entry for
  the id in the journal + agent-core in-memory history — not on the rebuilt
  provider conversation — so the Slice-C de-dup cannot mask a forward-fix
  regression. Slice-C keeps a separate projection-recovery test.

## Slice order

- **Slice A — canonical recorded-ids state + path helper + init seeding.** Add
  the `:state*` path helper and seed `:recorded-tool-result-ids #{}` in
  `initialize-session-slots` (alongside `:telemetry`). The init seeding is the
  default source and the session-lifecycle clearing boundary in one. No behaviour
  change yet.
- **Slice B — guarded handler (forward fix) + characterization tests.** Write the
  failing reproduction test first via the `:user-abort` synchronous `abort-in!`
  path (start a pending tool-call, drive `abort-in!`, then a late real result →
  assert exactly one `toolResult` entry for the id at the raw recorded layer:
  journal + agent-core in-memory history), then make the handler pure-guarded so
  it passes. Add normal-single-result coverage, interrupt-only coverage, and an
  **at-most-once concurrent-completion** test (real result recorded first → real
  result kept, interrupt suppressed; assert exactly one result, not which one,
  per the determinism framing). **Construction:** this test **directly
  dispatches** the two `:session/tool-agent-record-result` events for one
  `tool-call-id` (real result first, then a synthetic `"interrupted"` result for
  the same id) to exercise the handler chokepoint's first-writer suppression;
  `abort-in!` is **not** the vehicle, because the real result's record effect
  `disj`s the id from `:pending-tool-calls` (`agent_core/core.clj:407`) after its
  handler applies, so a sequential `abort-in!` (`turn.clj:233` →
  `record-pending-tool-call-interrupts!` `turn.clj:217`, which only enumerates
  still-pending ids `turn.clj:219-220`) dispatches no interrupt and the test would
  pass vacuously. The faithful enumeration window only exists under real
  apply/effect interleaving, not reproducible in sequential tests — direct
  dispatch of the two record events is the correct seam. No separate clearing
  wiring needed — handled by
  Slice-A init seeding; just confirm no journal-only `/clear` reset bypasses
  `initialize-session-slots`.
- **Slice C — defensive projection de-dup + test.** Drop duplicate `toolResult`
  projected messages in `journal->provider-messages` by de-duping
  `repair-dangling-tool-uses`'s **output** (first occurrence wins); add a test
  that a journal pre-populated with duplicate `toolResult` entries projects to
  exactly one `tool_result` per id through the rebuild (already-wedged session
  recovers), **including a non-contiguous duplicate** for one id so the test
  locks de-dup-after-repair (de-dup-before-repair would still emit two for the
  non-contiguous case).
- **Slice D — verify, docs, changelog.** `bb test` green, clj-kondo clean, update
  CHANGELOG ([Unreleased] → Fixed, user-visible: a tool-use no longer wedges the
  session after an abort), and any affected doc.

## Risks

- **Reading state in the handler.** The applied?/effects decision reads canonical
  `:state*` via `ctx` at handler-eval time; this is sound only because dispatch
  serializes handler evaluation+apply (single writer). Confirm the handler has
  `ctx` (it currently takes `_ctx` — rename to `ctx` and read). Do **not**
  introduce a test-and-set against the agent-core atom.
- **Clearing boundary discovery.** The design specifies "the same boundary that
  discards the journal/history". Must locate the concrete session-reset/clear
  path; if the only discard is whole-session removal, the set is bounded
  naturally and no explicit clear is needed — but verify there is no journal-only
  reset (`/clear`-style) that would leave a stale id set. Resolve during Slice B.
- **De-dup ordering vs `repair-dangling-tool-uses` (decided: de-dup *after*
  repair).** The de-dup removes *extra* duplicate `toolResult` messages;
  `repair-dangling-tool-uses` adds *missing* synthetic results. They address
  disjoint concerns, but the order is **load-bearing**: repair only scans the
  *contiguous* toolResult run per assistant block, so a non-contiguous real
  result for an id is treated as missing and gets a synthetic appended.
  De-dup-before-repair would therefore leave two results for one id on a malformed
  journal, while de-dup-after-repair (wrapping repair's output) guarantees ≤1
  unconditionally. Plan §3 pins de-dup to repair's output. The Slice-C recovery
  test must include a **non-contiguous** duplicate `toolResult` to lock this
  placement (a contiguous-only test passes under either order).
- **Concurrent-completion window is acceptable, not a bug.** Tests must assert
  *at-most-once* (exactly one result), not unconditionally "interrupt wins" — the
  real result legitimately wins in the narrow concurrent window.
- **No scope creep.** Do not reroute interrupt producers through
  `:session/tool-record-result`, do not touch async/background delegate delivery,
  do not refactor the interrupt subsystem, do not add an EQL resolver over
  recorded-ids (explicitly judged non-actionable scope creep in design review).
