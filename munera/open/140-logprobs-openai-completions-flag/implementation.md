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
