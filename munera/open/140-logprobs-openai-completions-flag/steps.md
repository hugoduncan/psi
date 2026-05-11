# Steps

- [ ] **1. Schema** — add to `session_state/model.clj`:
  - `[:logprobs-enabled {:optional true} :boolean]` in `agent-session-schema`
  - `[:top-logprobs {:optional true} [:int {:min 1 :max 20}]]` in `agent-session-schema`
  - `:logprobs` in `session-entry-kind-schema`

- [ ] **2. Request building** — `chat_completions.clj` `build-request`:
  - When `:logprobs-enabled` in options: add `"logprobs": true` and `"top_logprobs": N`
    (default 3) to body
  - `prompt_request.clj` `session->request-options`: propagate `:logprobs-enabled` and
    `:top-logprobs` from session data (pattern: same as `:thinking-level`)

- [ ] **3. SSE extraction** — `chat_completions.clj`:
  - Add `extract-logprob-delta` fn (OpenAI: `choices[0].logprobs.content`; llama.cpp:
    `completion_probabilities`)
  - Wire into `emit-chat-chunk!` for OpenAI per-chunk path
  - Wire into `finish-chat-chunk!` for llama.cpp final-chunk path
  - Emit `{:type :logprob-delta :tokens [...normalized...]}` via `consume-fn`
  - Add `:logprob-delta` to `case` in `make-provider-event-consumer` (`core.clj`)
    calling `(call-action! :on-logprob-delta {:tokens (:tokens event)})`

- [ ] **4. Turn-runtime accumulation** — extend accumulator:
  - Add `handle-logprob-delta!` in `accumulator.clj`: `(swap! td update :logprob-buffer (fnil conj []) (:tokens data))`
  - Add `:on-logprob-delta` dispatch to `make-turn-actions` calling `handle-logprob-delta!`
  - Extend `handle-done!` to flatten `:logprob-buffer` into `:logprobs` on `turn-data`
    before `(deliver done-p final)`
  - Extend `execute-live-turn!` (`core.clj`) to read `:logprobs` from
    `@(:turn-data turn-ctx)` after `await-assistant-message!` and include in return map
  - Extend `execute-prepared-request!` to destructure `:logprobs` and include as
    `:execution-result/logprobs` (nil when not collected)

- [ ] **5. Journal append + telemetry** — `prompt_recording.clj` `build-record-response`:
  - Write `:last-turn-logprobs` to session via `root-state-update`
  - Conditionally append `:logprobs` journal entry effect when logprobs non-empty

- [ ] **6. Journal projection** — `prompt_request.clj` `journal->provider-messages`:
  - Handle `:logprobs` entries → synthetic user message
  - Add `logprob-uncertain-threshold` constant (0.90)
  - Format: uncertain tokens table + "All other tokens" line

- [ ] **7. EQL resolver** — add `:psi.agent-session/last-turn-logprobs` resolver

- [ ] **8. `/logprobs` command**:
  - Add `set-logprobs-in!` to `session_settings.clj` (pattern: `set-thinking-level-in!`):
    `(defn set-logprobs-in! [ctx session-id enabled? top-n] (dispatch/dispatch! ctx :session/set-logprobs ...))`
  - Implement `dispatch-logprobs-command` in `commands.clj` (on/off/N/report)
    calling `session-settings/set-logprobs-in!`
  - Add `/logprobs` to `prefixed-command-prefixes` in `commands.clj`
  - Add `/logprobs` help line to `format-help` alongside `/model` and `/thinking`:
    `"  /logprobs [on|off|N] — toggle logprob collection or set top-N (1–20)\n"`
  - Add `"/logprobs"` to `builtin-slash-commands` in `tui/app/shared.clj`

- [ ] **9. Tests**:
  - `ai` component: request building with logprobs on/off; SSE extraction (OpenAI +
    llama.cpp); data normalization
  - `agent-session` component: options projection; journal append; message projection
    with `:logprobs` entries; compaction skips `:logprobs` entries
