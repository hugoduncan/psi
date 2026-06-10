# 224 Steps — at-most-once toolResult per tool-call-id

## Slice A — canonical recorded-ids state

- [x] Add a `:state*` path helper for per-session recorded tool-result ids
      (`session-recorded-tool-result-ids-path [sid]` →
      `[:agent-session :sessions sid :recorded-tool-result-ids]`) in
      `components/session-state/src/psi/session_state/state.clj`. The
      `session-`-prefixed name is required to match the existing per-session path
      helpers (`session-data-path` `:29`, `session-telemetry-path` `:30`,
      `session-turn-ctx-path` `:33`, `session-scheduler-path` `:34`,
      `session-scheduler-schedules-path` `:35`, `session-scheduler-queue-path`
      `:36`), every one of which carries the `session-` prefix.
- [x] **Default source = init seeding (decided; see Slice-A ambiguity follow-up
      item 3).** Seed `:recorded-tool-result-ids #{}` in
      `initialize-session-slots`
      (`components/session-state/src/psi/session_state/init.clj:78`) alongside
      `:telemetry initial-telemetry`. `initialize-session-slots` is the canonical
      journal/history-discard + session-init boundary (called on new, resume,
      fork, branch, child), so seeding here supplies the `#{}` default **and**
      clears the set on every session-lifecycle reset for free — this also
      resolves the Slice-B clearing-boundary item (no separate clear needed).
- [x] Confirm naming/placement is consistent with existing per-session path
      helpers (`session-data-path`, `session-telemetry-path`) and that the init
      seeding mirrors the existing `:telemetry` slot; no behaviour change in this
      slice.

## Slice B — guarded handler (forward fix) + tests

- [x] Write a **failing** characterization/regression test that reproduces the
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
- [x] Add a test that the **normal single-result** path still records exactly one
      real result (happy path unaffected).
- [x] Add a test that the **interrupt-only** path yields exactly one
      `"interrupted"` result.
- [x] Add a test asserting **at-most-once** under the concurrent-completion
      window (real result recorded first → real result kept, interrupt
      suppressed) — assert exactly one result, not which one, per the determinism
      framing. **Construction (pinned, ambiguity follow-up 3rd pass):** this test
      **directly dispatches** the two `:session/tool-agent-record-result` events
      for one `tool-call-id` — the **real** result first, then a **synthetic
      `"interrupted"`** result for the same id — to exercise the handler
      chokepoint's first-writer suppression. **Do not** drive this test through
      `abort-in!` (`turn.clj:233`): `abort-in!` →
      `record-pending-tool-call-interrupts!` (`turn.clj:217`) only enumerates ids
      still in `:pending-tool-calls` (`turn.clj:219-220`), but the real result's
      `:runtime/agent-record-tool-result` effect `disj`s the id from
      `:pending-tool-calls` (`agent_core/core.clj:407`) *after* its handler
      applies, so once the real result has fully run sequentially the id is gone
      and a subsequent `abort-in!` dispatches **no** interrupt — making an
      `abort-in!`-based test vacuously pass without ever dispatching the interrupt
      it claims to suppress. The genuine "still pending while recorded-ids already
      has the id" window only exists under real apply/effect interleaving, which
      sequential tests cannot reproduce; direct dispatch of the two record events
      is the faithful sequential seam for the chokepoint suppression.
- [x] Rename `_ctx` → `ctx` in the `:session/tool-agent-record-result` handler
      (`dispatch_handlers/session_mutations.clj:529`) and read the canonical
      recorded-ids set for `session-id` via `session/get-state-value-in`,
      nil-safe to `#{}` (defense-in-depth for pre-existing in-flight sessions;
      the canonical default is the Slice-A init seeding).
- [x] Extract `tool-call-id` from `tool-result-msg` (`:tool-call-id`).
- [x] Make the handler a pure both-or-neither transform:
      - if `tool-call-id` ∈ recorded-ids → return `{}` (no `:root-state-update`,
        no `:effects`);
      - else → return `{:root-state-update <(fnil conj #{}) id into recorded-ids>
        :effects [<agent-record-tool-result> <append-message-effect>]}`.
- [x] Session reset/clear boundary: **resolved in Slice A** — seeding
      `:recorded-tool-result-ids #{}` in `initialize-session-slots` (the
      journal/history-discard + session-init boundary) re-seeds the set on every
      session-lifecycle reset (new/resume/fork/branch/child), so the set is
      cleared on the same boundary that discards the journal/history and is
      naturally bounded by per-session tool-call count. **No** clear at the
      per-turn `:pending-tool-calls` reset; **no** separate explicit clear
      handler needed. Confirm there is no journal-only `/clear`-style reset that
      bypasses `initialize-session-slots`; if one exists, add the reseed there.
- [x] Run the reproduction + new tests; confirm all pass after the fix.
- [x] Run the existing agent-core / agent-session suites; confirm still green.

## Slice C — defensive projection de-dup + test

- [x] In `prompt_request/journal->provider-messages`
      (`prompt_request.clj:111`), drop any `toolResult`-role projected message
      whose `:tool-call-id` already appeared (first occurrence wins), keying off
      the journal-derived messages. Apply the de-dup to
      `repair-dangling-tool-uses`'s **output** — i.e. wrap the existing
      `(repair-dangling-tool-uses (into [] …))` (`prompt_request.clj:119`) as
      `(dedupe-tool-results (repair-dangling-tool-uses (into [] …)))` — **not**
      the pre-repair message list. Rationale: `repair-dangling-tool-uses` only
      scans the *contiguous* toolResult run per assistant block (`split-with`,
      `prompt_request.clj:96`), so a **non-contiguous** real result for an id is
      treated as missing and a **synthetic** `interrupted-tool-result` is appended
      for the same id; de-dup-before-repair would therefore leave two results for
      one id, while de-dup-after-repair guarantees ≤1 unconditionally. Keep
      ordering and non-`toolResult` messages intact (de-dup removes extras
      *including* synthetics repair adds for non-contiguous ids; repair still adds
      missing for genuinely dangling blocks).
- [x] Add a test: a journal pre-populated with **duplicate** `toolResult` entries
      for one `tool-call-id` projects to **exactly one** `tool_result` per id
      through the downstream conversation rebuild
      (`agent-messages->ai-conversation`), so an already-wedged session recovers
      on its next request. Include **two** duplicate shapes: (i) a **contiguous**
      duplicate (two adjacent `toolResult` entries for one id), and (ii) a
      **non-contiguous** duplicate (a `toolResult` for an id separated from its
      assistant tool-use block by an intervening non-`toolResult` message, so
      `repair-dangling-tool-uses` would otherwise append a synthetic for the same
      id). The non-contiguous case distinguishes and locks
      de-dup-after-repair: it must still yield exactly one `tool_result` for that
      id (de-dup-before-repair would emit two).
- [x] Confirm no independent de-dup is added at the conversation rebuild (single
      upstream chokepoint only).

## Slice D — verify, docs, changelog

- [x] `bb test` green.
- [x] `clj-kondo --lint` clean on all changed files; `clj-paren-repair` on edited
      Clojure files.
- [x] Update `CHANGELOG.md` `[Unreleased]` → `Fixed`: a tool-use no longer wedges
      the session after a turn abort (provider 400 "each tool_use must have a
      single result") — at-most-once toolResult per tool-call-id; already-wedged
      sessions recover via the provider-facing projection de-dup.
- [x] Update any affected doc if a user-facing behaviour/guarantee is documented
      (otherwise none).
- [x] Final coherence check: meta/spec(design)/tests/code/doc agree.

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

## Plan/steps ambiguity review follow-ups (second pass)

- [x] **Pin the Slice-C de-dup ordering relative to `repair-dangling-tool-uses`.**
      Plan §3 / steps Slice C only say de-dup goes "in `journal->provider-messages`"
      and to "ensure interaction … is correct", leaving it unspecified whether
      de-dup runs **before** or **after** `repair-dangling-tool-uses`. Repair only
      scans the *contiguous* toolResult run after each assistant message
      (`split-with`), so a non-contiguous real result for an id is treated as
      missing and gets a synthetic appended — meaning de-dup-before-repair can
      leave two results for one id on a malformed (already-wedged) journal, while
      de-dup-after-repair guarantees ≤1 unconditionally. Decide and state in plan
      §3 + steps Slice C that the de-dup applies to `repair-dangling-tool-uses`'s
      **output** (wrap the repaired list), so at-most-once holds even against
      synthetic results repair adds for non-contiguous ids. Then extend the
      Slice-C recovery test to include a **non-contiguous** duplicate `toolResult`
      for one id, so the test actually distinguishes and locks the chosen
      placement.
      → **Resolved: de-dup applies to `repair-dangling-tool-uses`'s output**
      (`(dedupe-tool-results (repair-dangling-tool-uses (into [] …)))`,
      `prompt_request.clj:119`). Code-verified that `repair-dangling-tool-uses`
      scans only the *contiguous* toolResult run per assistant block
      (`split-with tool-result-message?`, `prompt_request.clj:96`), so a
      non-contiguous real result is treated as missing and a synthetic is appended
      for the same id — de-dup-before-repair would leave two; de-dup-after-repair
      guarantees ≤1 unconditionally. plan.md §3, the de-dup-ordering risk, the
      Slice-C slice-order entry, and steps Slice C now pin de-dup-after-repair and
      require the Slice-C recovery test to include a **non-contiguous** duplicate
      (alongside a contiguous one) so the placement is locked.

## Plan/steps ambiguity review follow-ups (third pass)

- [x] **Pin the Slice-B concurrent-completion (4th) test's construction
      mechanism.** Slice B test 4 ("at-most-once under the concurrent-completion
      window — real result recorded first → real result kept, interrupt
      suppressed; assert exactly one result, not which one") pins the assertion
      but not the setup, and the obvious setup cannot exercise the suppression it
      claims. `abort-in!` (`turn.clj:233`) enumerates `:pending-tool-calls` and
      dispatches an interrupt record-event only for ids still in that set
      (`turn.clj:219-220`), but the id's `disj` from `:pending-tool-calls` happens
      *inside* the real result's `:runtime/agent-record-tool-result` effect
      (`agent_core/core.clj:407`), after that handler's apply. So in a sequential
      dispatch test, once the real result's `:session/tool-agent-record-result`
      has fully run, the id is gone from `:pending-tool-calls` and a later
      `abort-in!` dispatches **no** interrupt — the genuine "still pending while
      recorded-ids already has the id" window only exists under real apply/effect
      interleaving, which sequential tests can't reproduce. Decide and state in
      plan.md Slice B + steps Slice B that this test **directly dispatches** the
      two `:session/tool-agent-record-result` events for one id (real first, then
      a synthetic interrupt for the same id) to exercise the handler chokepoint's
      first-writer suppression, and explicitly note that `abort-in!` is **not**
      the vehicle for this test (the faithful enumeration window is not
      sequentially reproducible) — or specify the seam if `abort-in!` is required.
      Without this pin, interpretation (a) drives `abort-in!` and writes a
      vacuously-passing test that never dispatches the interrupt it claims to
      suppress.
      → **Resolved: direct dispatch of the two record events** (real first, then
      a synthetic `"interrupted"` for the same id); `abort-in!` explicitly **not**
      the vehicle. Code-verified: `record-pending-tool-call-interrupts!`
      (`turn.clj:217`) enumerates only still-pending ids (`turn.clj:219-220`), and
      the real result's `:runtime/agent-record-tool-result` effect `disj`s the id
      from `:pending-tool-calls` in `record-tool-result-in!`
      (`agent_core/core.clj:407`) *after* the handler applies — so a sequential
      `abort-in!` after the real result has fully run dispatches no interrupt and
      a test would pass vacuously. The faithful enumeration window only exists
      under real apply/effect interleaving (not sequentially reproducible), so the
      handler chokepoint's first-writer suppression is exercised by directly
      dispatching the two `:session/tool-agent-record-result` events. plan.md
      Slice B and steps.md Slice B test 4 now both pin this construction.

## Plan/steps inconsistency review follow-ups

- [x] **Reconcile the Slice-B test enumeration between plan.md and steps.md.**
      plan.md Slice order Slice B lists only three Slice-B tests (reproduction +
      "normal-single-result and interrupt-only coverage"); steps.md Slice B lists
      a **fourth** test — "asserting at-most-once under the concurrent-completion
      window (real result recorded first → real result kept, interrupt
      suppressed)". An implementer following plan.md writes 3 tests, following
      steps.md writes 4. Add the concurrent-completion at-most-once test to
      plan.md's Slice-B enumeration (it currently appears only in plan.md Risks
      as a constraint) so the two files agree on Slice-B coverage.
      → **Resolved:** plan.md Slice-B enumeration now lists four tests —
      reproduction + normal-single-result + interrupt-only + at-most-once
      concurrent-completion — matching steps.md Slice B.
- [x] **Fix the plan.md `conversation.clj` line citation to match design/code.**
      plan.md §3 ("Defensive projection de-dup") cites
      "`agent-messages->ai-conversation`, the sole `tool_result`-block emitter,
      `conversation.clj:95`", but `agent-messages->ai-conversation` is at
      `conversation.clj:136`; line `:95` is `conv/add-tool-result` (inside
      `append-tool-result-msg`), the actual sole block emitter. design.md's
      De-dup Location bullet distinguishes the rebuild fn (`:136`) from the block
      emitter (`:95`). Update plan.md to cite the rebuild at `:136` and attribute
      block emission to `conv/add-tool-result` `:95`.
      → **Resolved:** plan.md §3 now cites `agent-messages->ai-conversation` at
      `conversation.clj:136` and attributes block emission to `conv/add-tool-result`
      (inside `append-tool-result-msg`) `conversation.clj:95`. Line numbers
      re-verified against code (`add-tool-result` :95, rebuild fn :136).

## Plan/steps inconsistency review follow-ups (second pass)

- [x] **Reconcile design.md's forward-fix reproduction-test assertion layer with
      the plan/steps raw-recorded-layer decision.** design.md still pins the
      forward-fix reproduction test to the rebuilt provider conversation in two
      places — Scope ("asserts a single `tool_result` per `tool_use` in the
      rebuilt provider conversation", `design.md:225`) and Acceptance Criteria
      bullet 1 ("asserting exactly one `tool_result` per `tool_use` id in the
      provider-facing conversation", `design.md:396`). plan.md Key decisions and
      steps.md Slice B (resolved ambiguity follow-up item 1) instead assert the
      forward-fix reproduction at the **raw recorded layer** (journal +
      agent-core in-memory history), **not** on the rebuilt provider conversation,
      so the Slice-C `journal->provider-messages` de-dup cannot mask a forward-fix
      regression. An implementer following design.md asserts on the post-de-dup
      rebuild (masking the regression); following plan/steps asserts on the raw
      layer. Update design.md Scope (`:225`) + AC bullet 1 (`:396`) to assert the
      forward-fix reproduction at the raw recorded layer, keeping design AC bullet
      4 (already-wedged journal projection recovery, `:399-403`) on the rebuild /
      `journal->provider-messages` de-dup.
      → **Resolved:** design.md Scope characterization-test bullet and Acceptance
      Criteria bullet 1 now assert the forward-fix reproduction at the **raw
      recorded layer** — exactly one `toolResult` entry for the tool-call-id in
      the journal **and** the agent-core in-memory message history, via the
      `:user-abort` synchronous `abort-in!` path, explicitly **not** on the
      rebuilt provider conversation (so Slice-C de-dup cannot mask a forward-fix
      regression). AC bullet 4 (already-wedged journal projection recovery) left
      on the rebuild / `journal->provider-messages` de-dup. design.md now agrees
      with plan.md Key decisions and steps.md Slice B.

## Implementation review follow-ups (first pass)

- [x] **Seed in-flight tool state via the public `agent/emit-tool-start-in!`
      API, not a direct `:data-atom` swap, in the characterization tests.**
      `abort-races-real-result-yields-one-tool-result-test` and
      `interrupt-only-path-yields-one-result-test`
      (`tool_result_at_most_once_test.clj`) currently mark the tool pending with
      `(swap! (:data-atom agent-ctx) update :pending-tool-calls (fnil conj #{})
      tool-call-id)`, reaching into agent-core data-atom internals and
      re-implementing `agent/emit-tool-start-in!` (`agent_core/core.clj:420`).
      Replace with `(agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name
      "bash" :arguments "{}"})` so the tests exercise the real in-flight path
      (including the `:tool-execution-start` event) and stay robust to changes in
      the `:pending-tool-calls` representation. Leave
      `concurrent-completion-real-result-wins-test` on direct dispatch (its
      design-pinned seam). Re-run the suite to confirm still green.
      → **Resolved:** both `abort-races-real-result-yields-one-tool-result-test`
      and `interrupt-only-path-yields-one-result-test` now seed in-flight tool
      state via `(agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name
      "bash" :arguments "{}"})` instead of the direct `:data-atom` swap;
      `concurrent-completion-real-result-wins-test` left on its design-pinned
      direct dispatch. Focused suite green (4 tests / 14 assertions) and the full
      unit suite green.

## Plan/steps inconsistency review follow-ups (third pass)

- [x] **Reconcile the Slice-A path-helper name with the `session-` prefix
      convention the same step requires.** Slice A proposes the helper
      `recorded-tool-result-ids-path [sid]` (no `session-` prefix) yet its third
      bullet requires naming "consistent with existing per-session path helpers
      (`session-data-path`, `session-telemetry-path`)". Every existing
      per-session helper in `session_state/state.clj` is `session-`-prefixed
      (`session-data-path` `:29`, `session-telemetry-path` `:30`,
      `session-turn-ctx-path` `:33`, `session-scheduler-path` `:34`,
      `session-scheduler-schedules-path` `:35`, `session-scheduler-queue-path`
      `:36`), so the proposed name breaks the convention the step demands. Rename
      the proposed helper to the `session-`-prefixed form
      (`session-recorded-tool-result-ids-path [sid]` →
      `[:agent-session :sessions sid :recorded-tool-result-ids]`) in the Slice-A
      step so the proposed name and the convention bullet agree.
      → **Resolved:** Slice-A step 1 now names the helper
      `session-recorded-tool-result-ids-path [sid]` (with the `session-` prefix)
      and inlines the `session-`-prefixed existing helpers it must match, so the
      proposed name and the consistency requirement now agree.

## Implementation review follow-ups (second pass)

- [x] **Add a cross-turn regression test locking the session-scoped lifetime of
      recorded-ids.** The design's D1 persistence/reset boundary
      ("outcome-determining") requires recorded-ids to persist across turns (the
      headline race is cross-turn: the late real result arrives in a *later* turn
      than the interrupt that recorded it). No current test crosses a turn
      boundary between recording the interrupt and the late real result — all
      four record both within one in-flight sequence — so a future regression
      that clears recorded-ids at the per-turn boundary (mirroring
      `:pending-tool-calls` reset in `end-loop-in!` `agent_core/core.clj:449`)
      would pass the whole suite while reintroducing the duplicate. Add a test
      that records the synthetic `"interrupted"` result for a pending id, advances
      the turn (so `:pending-tool-calls` resets but recorded-ids must survive),
      then dispatches the real result for the same id, and asserts exactly one
      `toolResult` at the raw recorded layer (journal + in-memory history). The
      test must fail if recorded-ids were turn-scoped.
      → **Resolved:** added `recorded-ids-survive-turn-boundary-test` to
      `tool_result_at_most_once_test.clj`. It seeds the in-flight tool via
      `agent/emit-tool-start-in!`, records the synthetic interrupt via
      `session/abort-in!` (turn N), advances the turn with `agent/end-loop-in!`
      (asserting `:pending-tool-calls` is cleared — the per-turn reset the
      recorded-ids set must *not* share), then dispatches the late real result
      (turn N+1) and asserts exactly one `toolResult` for the id at the raw
      recorded layer (journal + agent-core in-memory history), interrupt
      first-writer-wins. Because recorded-ids lives in `:state*` (seeded only at
      session init, untouched by `end-loop-in!`'s agent-core data-atom reset), it
      survives the boundary; a turn-scoped clear would let the late real result
      record a second entry and fail the count assertions. Focused suite green
      (5 tests / 19 assertions).

## Test review follow-ups (first pass)

- [x] **Lock "first occurrence wins" in the projection de-dup test.**
      `journal-duplicate-tool-results-project-to-one-test`
      (`prompt_request_test.clj:397`) asserts only `count=1` per id, so it would
      pass even if `dedupe-tool-results` kept the *last* occurrence — yet
      design.md Scope pins the projection de-dup as "**first occurrence wins,
      purely derived from the journal**" and the forward-fix tests assert their
      winner. The contiguous case already uses distinct content (`first-contig`
      vs `dup-contig`); add an assertion that the surviving rebuilt
      `tool_result` for `id-contig` carries the **first** content
      (`first-contig`), not `dup-contig`, so the stated first-wins semantic is
      locked. (Leave the non-contiguous case on count-only — its survivor is the
      repair synthetic, an orthogonal subtlety.)
      → **Resolved:** added a `rebuilt-tool-results` helper (extracted from
      `rebuilt-tool-result-count`) and a third assertion to
      `journal-duplicate-tool-results-project-to-one-test` asserting the surviving
      rebuilt `tool_result` for `id-contig` carries `:content :text "first-contig"`
      (the **first** occurrence), not `dup-contig`. A last-wins `dedupe-tool-results`
      would now fail this assertion. Non-contiguous case left count-only (its
      survivor is the repair synthetic). Focused test green (1 test / 3
      assertions); clj-kondo clean, parens balanced.

## Test review follow-ups (second pass)

- [x] **Lock at-most-once *per tool-call-id* (not per-session) at the handler
      layer.** Every handler-layer test in `tool_result_at_most_once_test.clj`
      uses a single id per session, so a regression making the `:state*` guard
      per-session (a boolean "recorded" flag instead of the per-id set) would
      suppress every result after the first distinct call yet pass the suite. Add
      a handler-layer test that dispatches real results for **two distinct
      tool-call-ids** in one session via `record-result!` and asserts **both**
      are recorded at the raw recorded layer (each id →1 in journal + in-memory
      history; no cross-id suppression). The only existing two-distinct-id
      coverage is at the projection layer (`dedupe-tool-results`), not the
      `:state*` handler guard. A per-session guard must fail this test.
      → **Resolved:** added `distinct-tool-call-ids-both-recorded-test` to
      `tool_result_at_most_once_test.clj`. It dispatches real results for two
      distinct ids (`tc-distinct-a`, `tc-distinct-b`) in one session via
      `record-result!` and asserts **each** id records exactly one `toolResult`
      at the raw recorded layer (journal + agent-core in-memory history) with no
      cross-id suppression. A per-session boolean guard (suppress-after-first)
      would suppress `id-b` and fail the count-1 assertions for the second id.
      Focused suite green (6 tests / 25 assertions); clj-kondo clean, parens
      balanced.

## Test shaper review follow-ups (first pass)

- [x] **Normalize assertion style across the at-most-once suite**
      (`tool_result_at_most_once_test.clj`) — test-shaper `consistent(
      assertion_style)` ∧ `meaningful_failures`. Failure messages are present on
      the `count` assertions in `abort-races-real-result-yields-one-tool-result-
      test`, `recorded-ids-survive-turn-boundary-test`, `concurrent-completion-
      real-result-wins-test` (first count only), and `distinct-tool-call-ids-
      both-recorded-test`, but absent in `normal-single-result-path-unaffected-
      test`, `interrupt-only-path-yields-one-result-test`, and on the second
      `count` assertion of `concurrent-completion-real-result-wins-test`. Add
      consistent failure messages to those bare `count` assertions so a failure
      reports which layer (journal vs in-memory) and which expectation diverged.
      → **Resolved:** the bare `count` assertions in
      `normal-single-result-path-unaffected-test`,
      `interrupt-only-path-yields-one-result-test`, and the second `count`
      assertion in `concurrent-completion-real-result-wins-test` now all carry
      layer-naming failure messages ("…in the journal" / "…in the in-memory
      history"), matching the rest of the suite.
- [x] **Even up journal/memory winner symmetry** in the same suite —
      `normal-single-result-path-unaffected-test` and `interrupt-only-path-
      yields-one-result-test` assert the winning `:tool-name` only on `journal`,
      while the headline/cross-turn/concurrent tests assert it on **both**
      `journal` and `memory`. Assert the winning `:tool-name` on both layers in
      those two tests so the both-or-neither winner check is uniform across the
      suite (low-priority consistency polish; no behaviour gap).
      → **Resolved:** both `normal-single-result-path-unaffected-test`
      (`"bash"`) and `interrupt-only-path-yields-one-result-test`
      (`"interrupted"`) now assert the winning `:tool-name` on **both** `journal`
      and `memory`, matching the headline/cross-turn/concurrent tests. Focused
      suite green (6 tests / 27 assertions, up from 25); clj-kondo clean, parens
      balanced.

## Test shaper review follow-ups (second pass)

- [x] **Restore both-layer winner symmetry in
      `distinct-tool-call-ids-both-recorded-test`**
      (`tool_result_at_most_once_test.clj`). The test asserts the winning
      `:tool-name` (`"bash"`) only on `journal-a`/`journal-b`, not on
      `memory-a`/`memory-b` — breaking the suite-wide both-layer winner check the
      first test-shaper pass established for the other tests (which assert the
      winner on **both** `journal` and `memory`). The memory bindings already
      exist (used by the count assertions). Add `(is (= "bash" (:tool-name (first
      memory-a))))` and the `memory-b` equivalent so the winner check is uniform
      across the suite.
      → **Resolved:** `distinct-tool-call-ids-both-recorded-test` now asserts the
      winning `:tool-name` (`"bash"`) on `memory-a` and `memory-b` (already
      bound), matching the both-layer winner check used by the rest of the suite.
- [x] **Add layer-naming failure messages to the winner `:tool-name` assertions**
      across `tool_result_at_most_once_test.clj`
      (`consistent(assertion_style)` ∧ `meaningful_failures`). The first pass
      messaged every `count` assertion ("…in the journal" / "…in the in-memory
      history") but the `:tool-name` winner assertions remain bare in all tests,
      so a winner mismatch does not report which recorded layer diverged.
      Normalize the winner assertions to carry the same journal/memory layer
      messages. Low priority.
      → **Resolved:** every winner `:tool-name` assertion across the suite now
      carries a layer-naming message (e.g. "the interrupt wins on the journal
      layer" / "…on the in-memory history layer", "the real result wins on the
      … layer"), matching the layer-naming style the first pass applied to the
      `count` assertions. Focused suite green (6 tests / 29 assertions, up from
      27); clj-kondo clean, parens balanced.

## Test shaper review follow-ups (third pass)

- [x] **Compress the repeated both-layer count+winner assertion ceremony into a
      shared helper** in `tool_result_at_most_once_test.clj` (test-shaper
      `economical` ∧ `consistent(assertion_style)` ∧ robustness-against-drift).
      Five of the six handler-layer tests
      (`abort-races-real-result-yields-one-tool-result-test`,
      `recorded-ids-survive-turn-boundary-test`,
      `normal-single-result-path-unaffected-test`,
      `interrupt-only-path-yields-one-result-test`,
      `concurrent-completion-real-result-wins-test`) repeat the identical
      four-assertion block — `count=1` on `journal` and `memory`, plus the
      winning `:tool-name` on `journal` and `memory`, each with a layer-naming
      message. This ceremony is exactly what the prior two test-shaper passes had
      to repair *per test by hand* (first pass: missing failure messages; second
      pass: a test missing the memory winner assertion), so the duplication is
      the active source of divergence drift. Extract a single helper
      e.g. `(assert-single-recorded-result ctx session-id tool-call-id
      expected-tool-name)` that asserts `count=1` + winner on **both** layers
      with the established layer-naming messages, and call it from the five
      single-id tests; keep the winner name at the call site (passed as an arg)
      so intent stays locally visible — the helper compresses ceremony without
      hiding intent (`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`).
      `distinct-tool-call-ids-both-recorded-test` (two ids) calls it twice. This
      collapses the per-test divergence the prior passes fixed by hand into one
      enforced contract. Re-run the focused suite green.
      → **Resolved:** extracted `assert-single-recorded-result [ctx session-id
      tool-call-id expected-tool-name]` (asserts `count=1` + surviving
      `:tool-name` on **both** journal and in-memory layers, with the established
      layer-naming messages). The five single-id handler-layer tests now call it
      once each with the winner name at the call site (intent stays locally
      visible); `distinct-tool-call-ids-both-recorded-test` calls it twice (once
      per id). Removed the hand-rolled `let`/`is` ceremony from all six tests.
      Focused suite green (6 tests / 29 assertions — assertion count preserved);
      clj-kondo clean, parens balanced.

- [x] **Control time in the test message builders** (test-shaper
      `deterministic(control(time))` ∧ `minimal_incidental_setup`). `real-result-msg`
      and `interrupt-result-msg` (`tool_result_at_most_once_test.clj`) stamp
      `:timestamp (java.time.Instant/now)`, injecting uncontrolled wall-clock
      time into setup; the timestamp is never asserted and no de-dup/ordering
      keys off it, so it is incidental non-determinism. Replace with a fixed
      instant (e.g. a named `test`-constant or `java.time.Instant/EPOCH`) so the
      setup is fully deterministic and carries no incidental time detail. Low
      priority (no assertion currently depends on it).
      → **Resolved:** added a `test-instant` constant (`java.time.Instant/EPOCH`)
      and both `real-result-msg` and `interrupt-result-msg` now stamp
      `:timestamp test-instant` instead of `(java.time.Instant/now)`. Setup is
      fully deterministic; no assertion depends on the timestamp. Focused suite
      green.

## Code-shaper review follow-ups (first pass)

- [ ] **Return `{:effects []}` (not `{}`) from the guard's suppression branch**
      (`dispatch_handlers/session_mutations.clj:543`). `{}` is not a valid
      pure-result per `pure-result-schema`
      (`state_kernel/dispatch_schema.clj:12`, requires ≥1 recognized key) and
      only works via `normalize-handler-result`'s `{:return result}` coercion
      (`state_kernel/dispatch.clj:197`); `{:effects []}` is a valid pure-result
      that expresses "no effects, no state update" directly and matches the
      established no-op idiom already used in the same file
      (`:session/retarget-runtime-prompt-metadata`, `session_mutations.clj:429`)
      and namespace (`prompt_handlers.clj:172`, `statechart_actions.clj:195`).
      `consistent(idioms)` ∧ `shaped_by(formalisms) → enforceable(invariants)`.
      Re-run the at-most-once suite to confirm still green (suppression behaviour
      unchanged).
- [ ] **Bind `session-recorded-tool-result-ids-path` once in the handler `let`**
      (`session_mutations.clj`), reusing it for both the
      `session/get-state-value-in` read and the `:root-state-update`
      `update-in`, instead of recomputing it in the closure (DRY/clarity, low
      priority). Optionally factor a `record-tool-result-id-root-update`
      closure-builder in `session_state/state.clj` mirroring the existing
      `append-journal-entry-root-update` (`state.clj:108`) `*-root-update`
      convention so the path + `(fnil conj #{})` shape live in one named place.
