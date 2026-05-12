# Steps

- [x] Remove logprob conversation projection from `prompt_request.clj`: delete `logprob-uncertain-threshold`, `format-logprob-line`, `format-logprob-message`, `format-token-str`, `format-prob`, and the `:logprobs` branch in `journal->provider-messages`. Update or remove affected tests.
  - commit: 6dac4426
- [x] Remove logprob formatting from `step_execution.clj`: delete `logprob-uncertain-threshold`, `format-logprob-line`, `format-logprob-message`, `format-token-str`, `format-prob`, and `transcript-with-logprobs`. Change `:transcript` raw output to `(when assistant-message [assistant-message])` (assistant message only, no synthetic logprob user message). Update affected tests.
  - commit: a4e33bab
- [x] Add `:session-id` to session-step raw outputs in `step_execution.clj`: include `(:session-id execution-session)` in the `raw-outputs` map of `execute-session-step!`.
  - commit: a4e33bab (combined with step 2)
- [x] Enrich `session_turn_finished` event payload: thread `:logprobs` and `:assistant-message` from `terminal-result` through `prompt-finish-base-result` into the `:notify/extension-dispatch` effect payload. Add focused test.
  - commit: da03445e
- [x] Create `extensions/logprobs/` extension with `extensions.logprobs` namespace: `init` subscribes to `session_turn_finished`, stores last logprob-bearing turn per session (logprobs + assistant-message + turn-id) in an atom — only replaces on non-empty logprobs, retains on empty. Registers `logprobs/perplexity` deterministic operation returning perplexity, token-count, turn-id, and reply-text (`:reply-text` derived via `turn-execution-contract/assistant-message-text`). Add focused tests for perplexity calculation, event-driven storage, and retention across logprob-free turns.
  - commit: f5fce64a
- [x] Update the `local-logprobs` workflow: replace the two-step layout with three steps (run → perplexity invoke → report). The `perplexity` invoke step sources `:session-id` from `{:from {:step "run" :output :session-id}}`. The `report` step's vars reference `run`'s `:final-llm-reply` and `perplexity`'s `:result` envelope (via `:path`) for perplexity and token-count values.
  - commit: e582e29d
- [x] Verify end-to-end: lint clean, focused tests green, no regressions in broader logprob/workflow test suites.
  - 27 focused tests, 80 assertions, 0 failures
  - 1896 total tests, 9 pre-existing failures (workflow-execution-test dynamic delegate), 0 new failures
  - lint: 0 errors, 0 warnings
