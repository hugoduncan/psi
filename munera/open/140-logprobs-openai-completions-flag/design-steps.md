# Design Steps

Clarification and resolution items surfaced during design review.

## Ambiguities

- [ ] **llama.cpp streaming vs non-streaming logprobs**: `completion_probabilities` is
  documented as a non-streaming field in llama.cpp. Clarify whether logprob data
  arrives in SSE chunks (and if so, in which chunk field) or only in a final
  non-streaming response body. If non-streaming, specify whether psi should issue a
  separate non-streaming request or disable llama.cpp logprob support.

- [ ] **SSE chunk logprob extraction path**: `process-chat-sse-line!` / `emit-chat-chunk!`
  currently only extract text, reasoning, and tool-call deltas. Specify where in the
  chunk processing pipeline logprob data is extracted (e.g. new branch in
  `emit-chat-chunk!`), what event type is emitted to the consumer (new `:logprob-data`
  event or inline accumulation), and how the accumulator receives and buffers per-chunk
  logprob arrays across the stream.

- [ ] **`session->request-options` vs `StreamOptions` schema**: The design says
  `:logprobs-enabled` and `:top-logprobs` are propagated via `session->request-options`
  into the `options` map consumed by `build-request`. Specify whether these keys are
  added to `StreamOptions` (which is `{:closed false}` so technically allowed) or kept
  opaque. If added, provide the malli schema entries.

- [ ] **`agent-session-schema` update**: `:logprobs-enabled` (boolean, optional) and
  `:top-logprobs` (int 1–20, optional) must be added to `agent-session-schema` in
  `session_state/model.clj`. Confirm the schema entries and whether `initial-session`
  should explicitly set them (currently design says "absent = disabled").

- [ ] **Compaction interaction with `:logprobs` journal entries**: `compaction.clj`
  hardcodes `#{:message :custom-message :branch-summary}` in `message-like-entry?` and
  `valid-cut-point?`. Specify whether `:logprobs` entries should be: (a) silently
  skipped by compaction (treated like `:label`/`:session-info`), (b) stripped from the
  compacted journal, or (c) preserved as-is after the compaction boundary. Also clarify
  whether `journal->provider-messages` (in `prompt_request.clj`) needs to handle
  `:logprobs` entries after compaction removes the corresponding `:message` entry.

- [ ] **`turn-id` sourcing at journal-append time**: The `:logprobs` entry data
  includes `{:turn-id "..."}`. At the point of post-turn recording
  (`prompt_recording.clj` / `build-record-response`), `turn-id` is available in
  `execution-result`. Confirm that the logprobs journal-append effect is added
  alongside the existing `append-message-effect` in `build-record-response`, and that
  it receives `turn-id` from the same `execution-result`.

- [ ] **`/logprobs` TUI autocomplete registration**: `builtin-slash-commands` in
  `components/tui/src/psi/tui/app/shared.clj` and the canonical list in
  `commands.clj` (line 643) must both be updated. Confirm this is in scope and specify
  the display string / description for autocomplete.

- [ ] **Hard-coded 0.90 probability threshold**: The synthetic LLM message uses
  `p < 0.90` as the "uncertain token" threshold. Clarify whether this is a fixed
  display constant or a configurable session/command parameter (e.g. `/logprobs
  threshold 0.85`). If fixed, document the rationale.
