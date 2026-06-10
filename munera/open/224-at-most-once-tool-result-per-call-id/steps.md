# 224 Steps — at-most-once toolResult per tool-call-id

## Slice A — canonical recorded-ids state

- [ ] Add a `:state*` path helper for per-session recorded tool-result ids
      (e.g. `recorded-tool-result-ids-path [sid]` →
      `[:agent-session :sessions sid :recorded-tool-result-ids]`) in
      `components/session-state/src/psi/session_state/state.clj` (or the most
      consistent existing path-helper home), defaulting to `#{}`.
- [ ] Confirm naming/placement is consistent with existing per-session path
      helpers (`session-data-path`, `session-telemetry-path`); no behaviour
      change in this slice.

## Slice B — guarded handler (forward fix) + tests

- [ ] Write a **failing** characterization/regression test that reproduces the
      race end-to-end: start a tool call (pending), interrupt the turn (records a
      synthetic `"interrupted"` toolResult for the pending id), then dispatch the
      real result for the same `tool-call-id`; rebuild the provider conversation
      and assert **exactly one** `tool_result` per `tool_use` id. Verify it fails
      against current `main` behaviour (two results).
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
      recorded-ids set for `session-id` via `session/get-state-value-in`.
- [ ] Extract `tool-call-id` from `tool-result-msg` (`:tool-call-id`).
- [ ] Make the handler a pure both-or-neither transform:
      - if `tool-call-id` ∈ recorded-ids → return `{}` (no `:root-state-update`,
        no `:effects`);
      - else → return `{:root-state-update <conj id into recorded-ids> :effects
        [<agent-record-tool-result> <append-message-effect>]}`.
- [ ] Locate the session reset/clear boundary that discards the journal/history;
      clear `recorded-tool-result-ids` there (session lifetime), **not** at the
      per-turn `:pending-tool-calls` reset. If the only discard is whole-session
      removal, document that the set is naturally bounded and no explicit clear is
      needed; otherwise add the clear to the journal-discarding handler.
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
