# Implementation Notes

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
