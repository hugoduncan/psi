# Steps

- [ ] Remove logprob conversation projection from `prompt_request.clj`: delete `logprob-uncertain-threshold`, `format-logprob-line`, `format-logprob-message`, and the `:logprobs` branch in `journal->provider-messages`. Update or remove affected tests.
- [ ] Enrich `session_turn_finished` event payload: thread `:logprobs` and `:assistant-message` from `terminal-result` through `prompt-finish-base-result` into the `:notify/extension-dispatch` effect payload. Add focused test.
- [ ] Create `extensions/logprobs/` extension: `init` subscribes to `session_turn_finished`, stores last logprob-bearing turn per session (logprobs + assistant-message + turn-id) in an atom — only replaces on non-empty logprobs, retains on empty. Registers `logprobs/perplexity` deterministic operation returning perplexity, token-count, turn-id, and reply-text. Add focused tests for perplexity calculation, event-driven storage, and retention across logprob-free turns.
- [ ] Update the `local-logprobs` workflow: replace the `report` session step with an `:invoke` step calling `logprobs/perplexity`, then a reporting step that includes the structured perplexity result.
- [ ] Verify end-to-end: lint clean, focused tests green, no regressions in broader logprob/workflow test suites.
