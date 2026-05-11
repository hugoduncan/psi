# Design Steps

Clarification and resolution items surfaced during design review.

## Ambiguities

- [x] **llama.cpp streaming vs non-streaming logprobs**: `completion_probabilities` is
  documented as a non-streaming field in llama.cpp. Clarify whether logprob data
  arrives in SSE chunks (and if so, in which chunk field) or only in a final
  non-streaming response body. If non-streaming, specify whether psi should issue a
  separate non-streaming request or disable llama.cpp logprob support.
  > **Resolved**: llama.cpp returns `completion_probabilities` only in the final
  > non-streaming response body; it is absent from individual SSE delta chunks. Psi
  > does NOT issue a separate non-streaming request. Instead, llama.cpp logprob support
  > is limited to the final chunk: `process-chat-sse-line!` extracts
  > `completion_probabilities` from the last SSE object (the one containing usage or
  > `finish_reason`). If that field is absent (i.e. the server is OpenAI proper), the
  > OpenAI per-chunk path is used instead. Design updated accordingly.

- [x] **SSE chunk logprob extraction path**: `process-chat-sse-line!` / `emit-chat-chunk!`
  currently only extract text, reasoning, and tool-call deltas. Specify where in the
  chunk processing pipeline logprob data is extracted (e.g. new branch in
  `emit-chat-chunk!`), what event type is emitted to the consumer (new `:logprob-data`
  event or inline accumulation), and how the accumulator receives and buffers per-chunk
  logprob arrays across the stream.
  > **Resolved**: A new private fn `extract-logprob-delta` is added to
  > `chat_completions.clj`. It is called from `emit-chat-chunk!` (for per-chunk OpenAI
  > logprobs from `choices[0].logprobs.content`) and from `finish-chat-chunk!` (for the
  > final-chunk llama.cpp `completion_probabilities` field). When non-nil, a
  > `{:type :logprob-delta :tokens [...normalized...]}` event is emitted via
  > `consume-fn`. The turn-runtime accumulator collects these events into a transient
  > buffer; on `:done`, the buffer is finalized into `:execution-result/logprobs`. No
  > per-chunk inline accumulation inside `chat_completions.clj`; accumulation is owned
  > by the turn-runtime layer. (`:last-turn-logprobs` is the downstream session-data
  > slot written by `build-record-response`, not the accumulator output key.)

- [x] **`session->request-options` vs `StreamOptions` schema**: The design says
  `:logprobs-enabled` and `:top-logprobs` are propagated via `session->request-options`
  into the `options` map consumed by `build-request`. Specify whether these keys are
  added to `StreamOptions` (which is `{:closed false}` so technically allowed) or kept
  opaque. If added, provide the malli schema entries.
  > **Resolved**: `:logprobs-enabled` and `:top-logprobs` are kept **opaque** — they
  > pass through `StreamOptions` as extra keys (the schema is `{:closed false}`) without
  > adding explicit schema entries. This matches the existing pattern for
  > `:thinking-level`, `:no-auth-header`, etc. No schema change to `StreamOptions`.

- [x] **`agent-session-schema` update**: `:logprobs-enabled` (boolean, optional) and
  `:top-logprobs` (int 1–20, optional) must be added to `agent-session-schema` in
  `session_state/model.clj`. Confirm the schema entries and whether `initial-session`
  should explicitly set them (currently design says "absent = disabled").
  > **Resolved**: Add to `agent-session-schema`:
  > ```clojure
  > [:logprobs-enabled {:optional true} :boolean]
  > [:top-logprobs     {:optional true} [:int {:min 1 :max 20}]]
  > ```
  > `initial-session` does NOT set them explicitly; absent = disabled, consistent with
  > the design. No default value in `initial-session`.

- [x] **Compaction interaction with `:logprobs` journal entries**: `compaction.clj`
  hardcodes `#{:message :custom-message :branch-summary}` in `message-like-entry?` and
  `valid-cut-point?`. Specify whether `:logprobs` entries should be: (a) silently
  skipped by compaction (treated like `:label`/`:session-info`), (b) stripped from the
  compacted journal, or (c) preserved as-is after the compaction boundary. Also clarify
  whether `journal->provider-messages` (in `prompt_request.clj`) needs to handle
  `:logprobs` entries after compaction removes the corresponding `:message` entry.
  > **Resolved**: (a) `:logprobs` entries are silently skipped by compaction — they are
  > not message-like and not valid cut-points. `message-like-entry?` and
  > `valid-cut-point?` in `compaction.clj` require no change. `:logprobs` entries that
  > fall before the compaction cut-point are simply excluded from the kept suffix (same
  > as `:label`/`:session-info`). Entries after the cut-point are preserved as-is.
  > `journal->provider-messages` in `prompt_request.clj` must handle `:logprobs`
  > entries: it converts them to synthetic user messages (the same format as the
  > synthetic LLM message defined in the design). The existing `keep` filter on
  > `:message` kind is extended with a new branch for `:logprobs`. Orphaned `:logprobs`
  > entries (whose corresponding `:message` was compacted away) are silently dropped by
  > `journal->provider-messages` — no special handling needed since the compacted
  > summary already captures the prior context.

- [x] **`turn-id` sourcing at journal-append time**: The `:logprobs` entry data
  includes `{:turn-id "..."}`. At the point of post-turn recording
  (`prompt_recording.clj` / `build-record-response`), `turn-id` is available in
  `execution-result`. Confirm that the logprobs journal-append effect is added
  alongside the existing `append-message-effect` in `build-record-response`, and that
  it receives `turn-id` from the same `execution-result`.
  > **Resolved**: Confirmed. `build-record-response` already destructures `turn-id`
  > from `(turn-recording/build-recording-decision execution-result)`. The logprobs
  > journal-append effect is added to the `:effects` vector alongside
  > `append-message-effect`, receiving `turn-id` from the same binding. The logprob
  > token vector comes from `(:execution-result/logprobs execution-result)` (a new key
  > populated by the turn-runtime accumulator). When that key is nil or empty (logprobs
  > disabled), no `:logprobs` journal entry is appended.

- [x] **`/logprobs` TUI autocomplete registration**: `builtin-slash-commands` in
  `components/tui/src/psi/tui/app/shared.clj` and the canonical list in
  `commands.clj` (line 643) must both be updated. Confirm this is in scope and specify
  the display string / description for autocomplete.
  > **Resolved**: In scope. `/logprobs` is added to `prefixed-command-prefixes` in
  > `commands.clj` (alongside `/model`, `/thinking`, etc.) so it handles bare
  > `/logprobs` and `/logprobs <arg>` forms. `builtin-slash-commands` in
  > `tui/app/shared.clj` gains `"/logprobs"` for TUI autocomplete. Display string:
  > `"/logprobs"`. Help text (used in `/help` output):
  > `"  /logprobs [on|off|N] — toggle logprob collection or set top-N (1–20)\n"`.

## Inconsistencies (review pass 2)

- [x] **Accumulator key in design-steps.md** — design-steps.md item 2 resolution says
  "finalized into `:last-turn-logprobs`" but design.md and plan.md both say
  `:execution-result/logprobs` is the accumulator output key. Correct design-steps.md
  to read "finalized into `:execution-result/logprobs`"; `:last-turn-logprobs` is the
  downstream session-data slot written by `build-record-response`.
  > **Resolved**: design-steps.md item 2 resolution text corrected to say "finalized
  > into `:execution-result/logprobs`" with a note that `:last-turn-logprobs` is the
  > downstream session-data slot.

- [x] **"Session telemetry" wording in design.md** — design.md §Surfacing §1 and
  §Acceptance describe `:last-turn-logprobs` as a "session telemetry slot". The actual
  write path (`session-update` in `build-record-response`) targets session-data
  (`[:agent-session :sessions sid :data]`), not the telemetry sub-map
  (`[:agent-session :sessions sid :telemetry k]`). Update design.md to say
  "session-data slot" (consistent with `:last-execution-result-summary`). If telemetry
  placement is intentional, the implementation must use `session-telemetry-path` and a
  direct `update-in` instead of `session-update`.
  > **Resolved**: design.md §Surfacing §1 updated to "session-data slot" with a note
  > that `session-update` is used (same path as `:last-execution-result-summary`).
  > §Acceptance updated to "session-data" accordingly. Session-data placement is
  > confirmed correct; no code path change needed.

- [x] **`format-help` line missing from steps.md** — design.md specifies the help text
  `"  /logprobs [on|off|N] — toggle logprob collection or set top-N (1–20)\n"` but
  steps.md step 8 does not mention adding this line to `format-help` in `commands.clj`.
  Add an explicit sub-bullet to step 8: "Add `/logprobs` help line to `format-help`
  alongside `/model` and `/thinking`".
  > **Resolved**: steps.md step 8 updated with explicit sub-bullet: "Add `/logprobs`
  > help line to `format-help` alongside `/model` and `/thinking`" with the exact help
  > string from design.md.

- [x] **Hard-coded 0.90 probability threshold**: The synthetic LLM message uses
  `p < 0.90` as the "uncertain token" threshold. Clarify whether this is a fixed
  display constant or a configurable session/command parameter (e.g. `/logprobs
  threshold 0.85`). If fixed, document the rationale.
  > **Resolved**: Fixed display constant. Rationale: 0.90 is a practical signal
  > boundary — tokens above it are near-certain and add noise to the LLM context;
  > tokens below it represent meaningful uncertainty. Keeping it fixed avoids
  > command-surface complexity for an initial implementation. It can be made
  > configurable in a follow-on task if needed. The constant is named
  > `logprob-uncertain-threshold` and lives in the projection namespace.

## Structural gaps (review pass 3)

- [ ] **`:logprob-delta` event not routed in `make-provider-event-consumer`** —
  `core.clj`'s `make-provider-event-consumer` has an explicit `case` on `:type`; events
  with unknown types hit `nil` and are silently dropped. A `:logprob-delta` event emitted
  by `consume-fn` will never reach the accumulator. Specify: (a) add `:logprob-delta` to
  the `case` dispatch calling `call-action! :on-logprob-delta`, and (b) add a matching
  `:on-logprob-delta` action key to `make-turn-actions` in `accumulator.clj` that
  appends tokens to a transient buffer in `turn-data`. Update design.md §SSE extraction
  pipeline and plan.md step 3/4 accordingly.

- [ ] **No path for logprob data from turn-data → execution-result** — `execute-live-turn!`
  returns only `{:assistant-message ...}`; `execute-prepared-request!` builds
  `execution-result` from that and has no access to the turn-data atom's logprob buffer.
  Specify how `:execution-result/logprobs` is populated: either `execute-live-turn!`
  returns `:logprobs` alongside `:assistant-message` (reading from `@(:turn-data
  turn-ctx)` after the turn), or `execute-prepared-request!` reads it from `turn-ctx`
  directly. Update design.md §SSE extraction pipeline and plan.md step 4 to name the
  exact extraction point.

- [ ] **`session_settings.clj` layer for `/logprobs` toggle** — the established pattern
  for `/thinking` and `/model` commands routes through a dedicated `set-X-in!` fn in
  `session_settings.clj` before calling `dispatch/dispatch!`. The design's toggle path
  description skips this layer. Clarify: add `set-logprobs-in!` to `session_settings.clj`
  (matching the pattern), or call `dispatch/dispatch!` directly from `commands.clj`.
  Update design.md §Control toggle path and steps.md step 8 to reflect the chosen path.
