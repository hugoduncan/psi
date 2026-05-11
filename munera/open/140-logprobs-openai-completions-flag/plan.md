# Plan

Implement the logprobs feature as a series of vertical slices, each independently
testable.

## Approach

1. **Schema + session state** — add `:logprobs-enabled` / `:top-logprobs` to
   `agent-session-schema` and `:logprobs` to `session-entry-kind-schema` in
   `session_state/model.clj`. No `initial-session` changes.

2. **Request building** — extend `build-request` in `chat_completions.clj` to inject
   `"logprobs": true` / `"top_logprobs": N` when `:logprobs-enabled` is set in options.
   Extend `session->request-options` in `prompt_request.clj` to propagate
   `:logprobs-enabled` and `:top-logprobs` from session data.

3. **SSE extraction** — add `extract-logprob-delta` to `chat_completions.clj`. Wire
   into `emit-chat-chunk!` (OpenAI per-chunk path) and `finish-chat-chunk!`
   (llama.cpp final-chunk path). Emit `:logprob-delta` events via `consume-fn`.
   Add `:logprob-delta` to the `case` in `make-provider-event-consumer` (`core.clj`)
   calling `call-action! :on-logprob-delta`.

4. **Turn-runtime accumulation** — extend `make-turn-actions` (`accumulator.clj`) with
   `:on-logprob-delta` handler: appends tokens to `:logprob-buffer` in `turn-data`.
   Extend `handle-done!` to flatten `:logprob-buffer` into `:logprobs` on `turn-data`
   before delivering `done-p`. Extend `execute-live-turn!` (`core.clj`) to read
   `:logprobs` from `@(:turn-data turn-ctx)` after the turn and return it in its map.
   Extend `execute-prepared-request!` to destructure `:logprobs` and include it as
   `:execution-result/logprobs` in the result (nil when not collected).

5. **Journal append + telemetry** — extend `build-record-response` in
   `prompt_recording.clj` to: (a) write `:last-turn-logprobs` to session telemetry,
   (b) append a `:logprobs` journal entry when logprobs are non-empty.

6. **Journal projection** — extend `journal->provider-messages` in `prompt_request.clj`
   to convert `:logprobs` entries to synthetic user messages. Add
   `logprob-uncertain-threshold` constant (0.90) and formatting logic.

7. **EQL resolver** — add `:psi.agent-session/last-turn-logprobs` resolver.

8. **`/logprobs` command** — add `set-logprobs-in!` to `session_settings.clj` (matching
   `set-thinking-level-in!` pattern). Implement `dispatch-logprobs-command` in
   `commands.clj` calling `session-settings/set-logprobs-in!`. Add `/logprobs` to
   `prefixed-command-prefixes`. Add `"/logprobs"` to `builtin-slash-commands` in
   `tui/app/shared.clj`. Add help text to `format-help`.

9. **Tests** — unit tests for: request building, options projection, SSE extraction
   (OpenAI + llama.cpp paths), accumulation, journal append, message projection.

## Risks

- Turn-runtime accumulator API may need a new hook point for `:logprob-delta` events —
  inspect `accumulator.clj` before implementing step 4.
- llama.cpp `completion_probabilities` extraction requires confirming the exact JSON
  field path in the final SSE object — validate against `finish-chat-chunk!` shape.
