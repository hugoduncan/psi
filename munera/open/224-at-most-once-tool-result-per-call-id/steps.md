# 224 Steps — at-most-once toolResult per tool-call-id

## Slice A — canonical recorded-ids state

- [ ] Add a `:state*` path helper for per-session recorded tool-result ids
      (e.g. `recorded-tool-result-ids-path [sid]` →
      `[:agent-session :sessions sid :recorded-tool-result-ids]`) in
      `components/session-state/src/psi/session_state/state.clj` (consistent with
      existing per-session path helpers).
- [ ] **Default source = init seeding (decided; see Slice-A ambiguity follow-up
      item 3).** Seed `:recorded-tool-result-ids #{}` in
      `initialize-session-slots`
      (`components/session-state/src/psi/session_state/init.clj:78`) alongside
      `:telemetry initial-telemetry`. `initialize-session-slots` is the canonical
      journal/history-discard + session-init boundary (called on new, resume,
      fork, branch, child), so seeding here supplies the `#{}` default **and**
      clears the set on every session-lifecycle reset for free — this also
      resolves the Slice-B clearing-boundary item (no separate clear needed).
- [ ] Confirm naming/placement is consistent with existing per-session path
      helpers (`session-data-path`, `session-telemetry-path`) and that the init
      seeding mirrors the existing `:telemetry` slot; no behaviour change in this
      slice.

## Slice B — guarded handler (forward fix) + tests

- [ ] Write a **failing** characterization/regression test that reproduces the
      race end-to-end via the **`:user-abort` synchronous `abort-in!` path**
      (`turn.clj:233` → `record-pending-tool-call-interrupts!` `turn.clj:217`;
      decided, see ambiguity follow-up item 2 — *not* the statechart-effect
      `:deferred-interrupt` producer, which has different first-writer ordering):
      start a tool call (pending), drive `:user-abort` `abort-in!` (records a
      synthetic `"interrupted"` toolResult for the still-pending id), then
      dispatch the real result for the same `tool-call-id`. Assert at the **raw
      recorded layer**: **exactly one** `toolResult` entry for that
      `tool-call-id` in the journal **and** in the agent-core in-memory message
      history (decided, see ambiguity follow-up item 1 — *not* on the rebuilt
      provider conversation, so the Slice-C `journal->provider-messages` de-dup
      cannot mask a forward-fix regression). Verify it fails against current
      `main` behaviour (two recorded entries).
- [ ] Add a test that the **normal single-result** path still records exactly one
      real result (happy path unaffected).
- [ ] Add a test that the **interrupt-only** path yields exactly one
      `"interrupted"` result.
- [ ] Add a test asserting **at-most-once** under the concurrent-completion
      window (real result recorded first → real result kept, interrupt
      suppressed) — assert exactly one result, not which one, per the determinism
      framing.
- [ ] Rename `_ctx` → `ctx` in the `:session/tool-agent-record-result` handler
      (`dispatch_handlers/session_mutations.clj:529`) and read the canonical
      recorded-ids set for `session-id` via `session/get-state-value-in`,
      nil-safe to `#{}` (defense-in-depth for pre-existing in-flight sessions;
      the canonical default is the Slice-A init seeding).
- [ ] Extract `tool-call-id` from `tool-result-msg` (`:tool-call-id`).
- [ ] Make the handler a pure both-or-neither transform:
      - if `tool-call-id` ∈ recorded-ids → return `{}` (no `:root-state-update`,
        no `:effects`);
      - else → return `{:root-state-update <(fnil conj #{}) id into recorded-ids>
        :effects [<agent-record-tool-result> <append-message-effect>]}`.
- [ ] Session reset/clear boundary: **resolved in Slice A** — seeding
      `:recorded-tool-result-ids #{}` in `initialize-session-slots` (the
      journal/history-discard + session-init boundary) re-seeds the set on every
      session-lifecycle reset (new/resume/fork/branch/child), so the set is
      cleared on the same boundary that discards the journal/history and is
      naturally bounded by per-session tool-call count. **No** clear at the
      per-turn `:pending-tool-calls` reset; **no** separate explicit clear
      handler needed. Confirm there is no journal-only `/clear`-style reset that
      bypasses `initialize-session-slots`; if one exists, add the reseed there.
- [ ] Run the reproduction + new tests; confirm all pass after the fix.
- [ ] Run the existing agent-core / agent-session suites; confirm still green.

## Slice C — defensive projection de-dup + test

- [ ] In `prompt_request/journal->provider-messages`
      (`prompt_request.clj:111`), drop any `toolResult`-role projected message
      whose `:tool-call-id` already appeared (first occurrence wins), keying off
      the journal-derived messages. Keep ordering and non-`toolResult` messages
      intact; ensure interaction with `repair-dangling-tool-uses` is correct
      (de-dup removes extras; repair adds missing).
- [ ] Add a test: a journal pre-populated with **duplicate** `toolResult` entries
      for one `tool-call-id` projects to **exactly one** `tool_result` per id
      through the downstream conversation rebuild
      (`agent-messages->ai-conversation`), so an already-wedged session recovers
      on its next request.
- [ ] Confirm no independent de-dup is added at the conversation rebuild (single
      upstream chokepoint only).

## Slice D — verify, docs, changelog

- [ ] `bb test` green.
- [ ] `clj-kondo --lint` clean on all changed files; `clj-paren-repair` on edited
      Clojure files.
- [ ] Update `CHANGELOG.md` `[Unreleased]` → `Fixed`: a tool-use no longer wedges
      the session after a turn abort (provider 400 "each tool_use must have a
      single result") — at-most-once toolResult per tool-call-id; already-wedged
      sessions recover via the provider-facing projection de-dup.
- [ ] Update any affected doc if a user-facing behaviour/guarantee is documented
      (otherwise none).
- [ ] Final coherence check: meta/spec(design)/tests/code/doc agree.

## Plan/steps ambiguity review follow-ups

- [x] Pin the **forward-fix reproduction test assertion layer** to the raw
      recorded layer: assert **exactly one `toolResult` entry** in the journal +
      in-memory message history (not only on the rebuilt provider conversation),
      so the Slice-C `journal->provider-messages` de-dup cannot mask a forward-fix
      regression. Keep a separate Slice-C test for the projection-level recovery.
      → **Resolved:** Slice-B repro step now asserts at the raw recorded layer
      (journal + in-memory history); Slice-C keeps the separate projection-recovery
      test.
- [x] Specify in Slice B that the reproduction drives the **`:user-abort`
      synchronous `abort-in!` path** (`turn.clj:233`), matching the design
      Evidence, rather than an unspecified "interrupt the turn" (the three
      producers have different first-writer ordering).
      → **Resolved:** Slice-B repro step now names the `:user-abort` synchronous
      `abort-in!` path (`turn.clj:233` → `record-pending-tool-call-interrupts!`),
      excluding the statechart-effect `:deferred-interrupt` producer.
- [x] Resolve the Slice-A **default source** for recorded-tool-result-ids: decide
      and state whether it is (a) seeded in the session model/init
      (`init.clj` `initialize-session-slots` / `model/initial-session`, alongside
      `initial-telemetry`) or (b) supplied nil-safe at the Slice-B read/update
      site (`get-state-value-in … #{}` + `(fnil conj #{})`). A path helper alone
      cannot "default"; the choice interacts with the clearing-boundary decision.
      → **Resolved: choice (a)** — seed `#{}` in `initialize-session-slots`
      (journal/history-discard + session-init boundary), which supplies the
      default **and** clears on session-lifecycle reset (resolving the Slice-B
      clearing-boundary item); the read/update site keeps nil-safe `#{}` /
      `(fnil conj #{})` as defense-in-depth only.

## Plan/steps inconsistency review follow-ups

- [ ] **Reconcile the Slice-B test enumeration between plan.md and steps.md.**
      plan.md Slice order Slice B lists only three Slice-B tests (reproduction +
      "normal-single-result and interrupt-only coverage"); steps.md Slice B lists
      a **fourth** test — "asserting at-most-once under the concurrent-completion
      window (real result recorded first → real result kept, interrupt
      suppressed)". An implementer following plan.md writes 3 tests, following
      steps.md writes 4. Add the concurrent-completion at-most-once test to
      plan.md's Slice-B enumeration (it currently appears only in plan.md Risks
      as a constraint) so the two files agree on Slice-B coverage.
- [ ] **Fix the plan.md `conversation.clj` line citation to match design/code.**
      plan.md §3 ("Defensive projection de-dup") cites
      "`agent-messages->ai-conversation`, the sole `tool_result`-block emitter,
      `conversation.clj:95`", but `agent-messages->ai-conversation` is at
      `conversation.clj:136`; line `:95` is `conv/add-tool-result` (inside
      `append-tool-result-msg`), the actual sole block emitter. design.md's
      De-dup Location bullet distinguishes the rebuild fn (`:136`) from the block
      emitter (`:95`). Update plan.md to cite the rebuild at `:136` and attribute
      block emission to `conv/add-tool-result` `:95`.
