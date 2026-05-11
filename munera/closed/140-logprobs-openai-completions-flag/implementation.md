# Implementation Notes

## Test review pass 1 (2026-05-11)

Reviewed task tests against design.md and the referenced test namespaces:
`components/ai/test/psi/ai/providers/openai_completions_logprobs_test.clj`,
`components/turn-runtime/test/psi/turn_runtime/accumulator_logprobs_test.clj`, and
`components/agent-session/test/psi/agent_session/{logprobs,compaction}_test.clj`.
No new actionable test feedback: request shaping, SSE extraction/normalization,
turn accumulation, journal append/projection, and compaction transparency are covered.
No new follow-up checklist items added.

## Follow-up execution pass 6 (2026-05-11)

Executed the remaining unchecked task-artifact follow-up items in `steps.md` after
verifying the implementation already lands them in code/tests:

1. Marked steps 1–9 done in `steps.md` after confirming the feature is present in the
   authoritative owners:
   - schema/session state in `components/session-state/src/psi/session_state/model.clj`
   - request building + SSE extraction in
     `components/ai/src/psi/ai/providers/openai/chat_completions.clj`
   - turn-runtime accumulation in `components/turn-runtime/src/psi/turn_runtime/{core,accumulator}.clj`
   - journal append/projection + `/logprobs` command + resolver in
     `components/agent-session/src/psi/agent_session/{prompt_recording,prompt_request,commands,session_settings,resolvers/session}.clj`
   - focused proofs in `components/ai/test/`, `components/turn-runtime/test/`, and
     `components/agent-session/test/`

2. No further executable code follow-up remained from the preloaded code-shape review;
   the remaining work was task-artifact synchronization only.

## Review pass 5 follow-up execution (2026-05-11)

Executed all three unchecked items from review pass 5:

1. **`:last-turn-logprobs` stale-data fix** — replaced `cond->` guard
   `(some? logprobs) (assoc :last-turn-logprobs logprobs)` with unconditional
   `(assoc % ... :last-turn-logprobs logprobs)` in `prompt_recording.clj`.
   Added `build-record-response-clears-last-turn-logprobs-when-nil-test` to
   `logprobs_test.clj`: applies nil-logprobs execution-result to a state already
   holding stale `:last-turn-logprobs`; asserts value is nil after update.

2. **Compaction test for `:logprobs` entries** — added
   `logprobs-entries-skipped-by-compaction-test` to `compaction_test.clj`.
   Verifies (a) `rebuild-messages-from-journal-entries` skips `:logprobs` entries
   (4 messages from 5 entries), (b) `prepare-compaction` does not select the
   `:logprobs` entry as a valid cut-point.

3. **`:logprobs` removed from assistant final message** — removed
   `(seq logprob-buffer) (assoc :logprobs logprobs)` cond branch from `final`
   map construction in `handle-done!` in `accumulator.clj`. Logprobs are stored
   on `@td` and extracted by `execute-live-turn!`; the `:logprobs` journal entry
   is the canonical store.

Verification: 1704 tests, 0 failures; lint 0 errors, 0 warnings.

## Implementation review pass 5 (2026-05-11)

1702 unit tests pass; lint clean (0 errors, 0 warnings). All nine plan steps are implemented and match the design. Three issues found:

1. **`:last-turn-logprobs` not cleared when logprobs=nil** — `build-record-response` uses `(some? logprobs) (assoc :last-turn-logprobs logprobs)`, so `:last-turn-logprobs` is never written when logprobs is nil. After enabling logprobs, running a turn, then disabling, the EQL resolver returns stale data from the previous logprob turn. Design acceptance says "nil when disabled or before any logprob turn". Fix: unconditionally update `:last-turn-logprobs` (write nil when no logprobs collected).

2. **Compaction test missing** — design acceptance criteria require "compaction skips `:logprobs` entries". `compaction.clj` is correct (`:logprobs` not in `message-like-entry?` or `valid-cut-point?`), but `compaction_test.clj` has no test for this. The acceptance criterion is unverified.

3. **`:logprobs` embedded in assistant final message** — `handle-done!` adds `:logprobs` to the `final` message map (line 322 of accumulator.clj) which is then stored in the journal `:message` entry via `persist/message-entry`. This duplicates logprob data in the journal (also in the separate `:logprobs` entry). `transform-messages` ignores it, so no functional harm, but the journal is bloated. Not mentioned in design.

## Review pass 4 follow-up execution (2026-05-11)

Executed all four unchecked items from review pass 4:

1. **Handler registration sub-bullet** — added explicit `register-core-handler!` bullet
   to steps.md step 8 and plan.md step 8, with the full
   `(session/session-update session-id #(assoc % :logprobs-enabled enabled? :top-logprobs (or top-n 3)))`
   call and nil-guard rationale.

2. **`root-state-update` syntax** — corrected design.md §Control toggle path: replaced
   bare `(assoc % ...)` with `(session/session-update session-id #(assoc % ...))` and
   `(or top-n 3)`. Added explanatory note that `:top-logprobs` is never written as nil.

3. **Misleading `builtin-slash-commands` qualifier** — removed "alongside `/model` and
   `/thinking`" from the `builtin-slash-commands` bullet in steps.md step 8. Those
   commands live in `prefixed-command-prefixes`, not `builtin-slash-commands`.

4. **nil `top-n` nil-guard** — `(or top-n 3)` now explicit in design.md §Control
   handler body, steps.md step 8, and plan.md step 8.

All four items marked `[x]` in design-steps.md.

## Review pass 4 — cross-file inconsistency scan (2026-05-11)

Four actionable inconsistencies found by reading design.md, plan.md, steps.md, and
codebase source files.

1. **`:session/set-logprobs` handler registration missing from plan.md and steps.md** —
   design.md §Control describes the `session_mutations` handler body but neither plan.md
   step 8 nor steps.md step 8 include a `register-core-handler!` call in
   `session_mutations.clj`. Without this handler the dispatch event is unrouted.

2. **`root-state-update` syntax in design.md is wrong** — design.md shows
   `{:root-state-update (assoc % :logprobs-enabled enabled? :top-logprobs top-n)}`.
   The actual pattern in `session_mutations.clj` is
   `{:root-state-update (session/session-update session-id #(assoc % ...))}`.
   Bare `(assoc % ...)` is not a valid `root-state-update` value.

3. **`builtin-slash-commands` "alongside /model and /thinking" is misleading** —
   steps.md step 8 says to add `/logprobs` "alongside `/model` and `/thinking`" to
   `builtin-slash-commands` in `shared.clj`, but `/model` and `/thinking` are NOT in
   `builtin-slash-commands` — they are in `prefixed-command-prefixes`. The step should
   not reference those commands as co-residents of `builtin-slash-commands`.

4. **nil `top-n` in handler body writes nil to `:top-logprobs`** — when `/logprobs on`
   is called without N, `set-logprobs-in!` passes `top-n=nil`. The handler body
   `(assoc % :top-logprobs top-n)` would store nil, violating the design invariant
   "absent = 3 when enabled". The handler must use `(or top-n 3)` or only assoc when
   top-n is non-nil. Neither design.md nor steps.md specifies this nil-guard.

## Review pass 1 — ambiguity scan (2026-05-11)

Reviewed design.md against codebase. No plan.md / steps.md / implementation.md existed
prior to this pass. Findings recorded in design-steps.md.

## Design-steps resolution pass (2026-05-11)

Resolved all 8 ambiguities in design-steps.md by reading codebase. Key decisions:

- **llama.cpp**: logprobs extracted from final SSE chunk only (`completion_probabilities`
  field); no separate non-streaming request. Extraction wired in `finish-chat-chunk!`.

- **SSE pipeline**: new `extract-logprob-delta` fn; emits `:logprob-delta` events via
  `consume-fn`; turn-runtime accumulator owns buffering; finalizes into
  `:execution-result/logprobs`.

- **StreamOptions**: `:logprobs-enabled` / `:top-logprobs` pass through as opaque extra
  keys (schema is `{:closed false}`). No schema change. Matches `:thinking-level` pattern.

- **agent-session-schema**: two optional entries added; `initial-session` unchanged
  (absent = disabled).

- **Compaction**: `:logprobs` entries are transparent to compaction (not message-like,
  not valid cut-points). Orphaned entries after compaction are silently dropped by
  `journal->provider-messages`.

- **turn-id sourcing**: `build-record-response` already has `turn-id` from
  `build-recording-decision`; logprobs append effect added alongside existing
  `append-message-effect`.

- **`/logprobs` command**: added to `prefixed-command-prefixes`; `builtin-slash-commands`
  in `tui/app/shared.clj` updated. Help text confirmed.

- **0.90 threshold**: fixed constant `logprob-uncertain-threshold` in projection
  namespace. Rationale: near-certain tokens add noise; configurable threshold deferred.

Created plan.md and steps.md.

## Design-steps follow-up execution pass (2026-05-11)

Executed all three unchecked items from the review pass 2 inconsistency scan:

1. **Accumulator key** — corrected design-steps.md item 2 resolution text from
   `:last-turn-logprobs` to `:execution-result/logprobs`; added parenthetical
   clarifying that `:last-turn-logprobs` is the downstream session-data slot.

2. **"Session telemetry" wording** — updated design.md §Surfacing §1 to "session-data
   slot" with a note referencing `session-update` and `:last-execution-result-summary`
   as the pattern. Updated §Acceptance to "session-data" accordingly. Session-data
   placement confirmed correct; no code change needed.

3. **`format-help` sub-bullet** — added explicit sub-bullet to steps.md step 8 calling
   out the `format-help` addition with the exact help string from design.md.

All three inconsistency items are now marked `[x]` in design-steps.md.

## Review pass 3 — codebase structural scan (2026-05-11)

Read `turn_runtime/core.clj`, `accumulator.clj`, `session_settings.clj`, and
`session_mutations.clj`. Three actionable gaps found.

1. **`:logprob-delta` silently dropped by event consumer** — `make-provider-event-consumer`
   in `core.clj` has an explicit `case` on `:type`; unknown types hit `nil` and are
   dropped. A `:logprob-delta` event emitted by `consume-fn` will never reach the
   accumulator. The design must specify adding `:logprob-delta` to the `case` dispatch
   in `make-provider-event-consumer` (calling `call-action! :on-logprob-delta`), and
   `make-turn-actions` must add a matching `:on-logprob-delta` action key.

2. **Logprob data has no path from turn-data → execution-result** — `execute-live-turn!`
   returns only `{:assistant-message ...}` (destructured from `turn-ctx` / `done-p`).
   `execute-prepared-request!` constructs the execution-result map from that return value
   and has no access to the turn-data atom's logprob buffer. The design says
   `:execution-result/logprobs` is populated but does not specify how: either
   `execute-live-turn!` must return `:logprobs` alongside `:assistant-message`, or
   `execute-prepared-request!` must read it from `turn-ctx`/`turn-data` directly after
   the turn completes.

3. **`/logprobs` toggle path missing `session_settings.clj` layer** — the actual pattern
   for `/thinking` and `/model` is: `commands.clj` → `session/set-X-in!` in
   `session_settings.clj` → `dispatch/dispatch!` → handler in `session_mutations.clj`.
   The design's toggle path description skips `session_settings.clj`. Clarify whether
   `set-logprobs-in!` is added to `session_settings.clj` (matching the established
   pattern) or whether `commands.clj` calls `dispatch/dispatch!` directly.

## Review pass 2 — cross-file inconsistency scan (2026-05-11)

Three actionable inconsistencies found across design.md / design-steps.md / plan.md / steps.md:

1. **Accumulator finalization key mismatch** — design-steps.md SSE extraction resolution
   (item 2) says the accumulator finalizes into `:last-turn-logprobs`, but design.md
   (§SSE extraction pipeline) and plan.md (step 4) both say `:execution-result/logprobs`.
   design-steps.md is wrong; `:execution-result/logprobs` is the correct intermediate key
   (the accumulator output); `:last-turn-logprobs` is the session-data slot written later
   by `build-record-response`.

2. **"Session telemetry" vs session-data path** — design.md (§Surfacing §1, Acceptance)
   calls `:last-turn-logprobs` a "session telemetry slot", but `session-update` (the
   mechanism used by `build-record-response`) writes to
   `[:agent-session :sessions sid :data]`, not
   `[:agent-session :sessions sid :telemetry k]`. Telemetry uses
   `session-telemetry-path`; the design conflates the two. The correct description is
   "session-data slot" (same as `:last-execution-result-summary`). No code change needed
   if session-data is intended; design.md wording must be corrected.

3. **`/logprobs` missing from `format-help`** — design.md specifies help text
   `"  /logprobs [on|off|N] — toggle logprob collection or set top-N (1–20)\n"` but
   steps.md step 8 and plan.md step 8 do not mention adding this line to `format-help`
   in `commands.clj`. The `/thinking` and `/model` commands both appear in `format-help`;
   `/logprobs` must too. Steps.md should explicitly call this out.

## Structural gap resolution pass (2026-05-11)

Executed all three unchecked structural gap items from review pass 3:

1. **`:logprob-delta` event routing** — confirmed `make-provider-event-consumer` in
   `core.clj` uses an explicit `case` with `nil` default; unknown events are silently
   dropped. Resolved by specifying: add `:logprob-delta` to the `case` calling
   `(call-action! :on-logprob-delta {:tokens (:tokens event)})`. Add private
   `handle-logprob-delta!` to `accumulator.clj` that conjoins the token vector onto
   `:logprob-buffer` in `turn-data`. Add `:on-logprob-delta` dispatch in
   `make-turn-actions`. `handle-done!` flattens `:logprob-buffer` into `:logprobs`
   before delivering `done-p`. Design.md §SSE extraction pipeline and plan.md step 3/4
   updated.

2. **turn-data → execution-result path** — confirmed `execute-live-turn!` returns only
   `{:turn-id :model :ai-options :turn-ctx :assistant-message}`; `execute-prepared-request!`
   destructures only `:assistant-message`. Resolved: `execute-live-turn!` reads
   `(get @(:turn-data turn-ctx) :logprobs)` after `await-assistant-message!` and
   includes `:logprobs` in its return map. `execute-prepared-request!` destructures
   `:logprobs` and includes it as `:execution-result/logprobs` (nil when not collected).
   Design.md §SSE extraction pipeline and plan.md step 4 updated.

3. **`session_settings.clj` layer** — confirmed the established pattern:
   `commands.clj` → `session-settings/set-X-in!` → `dispatch/dispatch!`. Resolved:
   add `set-logprobs-in!` to `session_settings.clj` matching `set-thinking-level-in!`.
   `commands.clj` calls `session-settings/set-logprobs-in!`. Design.md §Control toggle
   path and steps.md step 8 updated to name `set-logprobs-in!` explicitly.

All three items marked `[x]` in design-steps.md.
