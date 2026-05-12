# Steps

- [ ] Remove logprob conversation projection from `prompt_request.clj`: delete `logprob-uncertain-threshold`, `format-logprob-line`, `format-logprob-message`, and the `:logprobs` branch in `journal->provider-messages`. Update or remove affected tests.
- [ ] Enrich `session_turn_finished` event payload: thread logprobs from `terminal-result` through `prompt-finish-base-result` into the `:notify/extension-dispatch` effect payload. Add focused test.
- [ ] Create `extensions/logprobs/` extension: `init` subscribes to `session_turn_finished`, stores last-turn logprobs per session in an atom, registers `logprobs/perplexity` deterministic operation. Add focused tests for perplexity calculation and event-driven storage.
- [ ] Update the `local-logprobs` workflow: replace the `report` session step with an `:invoke` step calling `logprobs/perplexity`, then a reporting step that includes the structured perplexity result.
- [ ] Verify end-to-end: lint clean, focused tests green, no regressions in broader logprob/workflow test suites.
