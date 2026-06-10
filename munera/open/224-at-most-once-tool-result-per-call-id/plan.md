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
   `agent-messages->ai-conversation`, the sole `tool_result`-block emitter,
   `conversation.clj:95`) consumes the de-duped list and is **not** a second
   de-dup site.

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

## Slice order

- **Slice A — canonical recorded-ids state + path helper.** Add the `:state*`
  path and (if helpful) a small predicate/update helper. No behaviour change yet.
- **Slice B — guarded handler (forward fix) + characterization tests.** Write the
  failing reproduction test first (interrupt with a pending tool-call, then a
  late real result → assert exactly one `tool_result` per `tool_use` in the
  rebuilt provider conversation), then make the handler pure-guarded so it passes.
  Add normal-single-result and interrupt-only coverage. Wire the
  session-reset/clear clearing of recorded-ids.
- **Slice C — defensive projection de-dup + test.** Drop duplicate `toolResult`
  projected messages in `journal->provider-messages`; add a test that a journal
  pre-populated with duplicate `toolResult` entries projects to exactly one
  `tool_result` per id through the rebuild (already-wedged session recovers).
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
- **De-dup ordering vs `repair-dangling-tool-uses`.** The de-dup removes *extra*
  duplicate `toolResult` messages; `repair-dangling-tool-uses` adds *missing*
  synthetic results. They address disjoint concerns; ensure de-dup runs such that
  the rebuild sees ≤1 `toolResult` per id without breaking dangling repair.
- **Concurrent-completion window is acceptable, not a bug.** Tests must assert
  *at-most-once* (exactly one result), not unconditionally "interrupt wins" — the
  real result legitimately wins in the narrow concurrent window.
- **No scope creep.** Do not reroute interrupt producers through
  `:session/tool-record-result`, do not touch async/background delegate delivery,
  do not refactor the interrupt subsystem, do not add an EQL resolver over
  recorded-ids (explicitly judged non-actionable scope creep in design review).
