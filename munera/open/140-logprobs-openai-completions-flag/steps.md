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

- [ ] **4. Turn-runtime accumulation** — extend accumulator:
  - Collect `:logprob-delta` events into transient buffer (inspect `accumulator.clj`)
  - Finalize into `:execution-result/logprobs` on `:done`

- [ ] **5. Journal append + telemetry** — `prompt_recording.clj` `build-record-response`:
  - Write `:last-turn-logprobs` to session via `root-state-update`
  - Conditionally append `:logprobs` journal entry effect when logprobs non-empty

- [ ] **6. Journal projection** — `prompt_request.clj` `journal->provider-messages`:
  - Handle `:logprobs` entries → synthetic user message
  - Add `logprob-uncertain-threshold` constant (0.90)
  - Format: uncertain tokens table + "All other tokens" line

- [ ] **7. EQL resolver** — add `:psi.agent-session/last-turn-logprobs` resolver

- [ ] **8. `/logprobs` command** — `commands.clj`:
  - Implement `dispatch-logprobs-command` (on/off/N/report)
  - Add `/logprobs` to `prefixed-command-prefixes`
  - Add `/logprobs` help line to `format-help` alongside `/model` and `/thinking`:
    `"  /logprobs [on|off|N] — toggle logprob collection or set top-N (1–20)\n"`
  - Add `"/logprobs"` to `builtin-slash-commands` in `tui/app/shared.clj`

- [ ] **9. Tests**:
  - `ai` component: request building with logprobs on/off; SSE extraction (OpenAI +
    llama.cpp); data normalization
  - `agent-session` component: options projection; journal append; message projection
    with `:logprobs` entries; compaction skips `:logprobs` entries
