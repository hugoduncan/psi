# Implementation Notes

## Design ambiguity review — pass 1 (2026-05-12)

Six ambiguities found:

1. **Duplicated logprob formatting in `step_execution.clj` not addressed.** `prompt_request.clj` removal is specified, but `psi.workflow-runtime.statechart-runtime.step-execution` has an independent copy of `format-logprob-message`, `format-logprob-line`, `logprob-uncertain-threshold`, and `transcript-with-logprobs`. This copy injects a synthetic user message into the workflow `:transcript` output for *all* session steps with logprobs. Design is silent on whether to keep or remove it. Needs explicit decision.

2. **`logprobs/perplexity` session-id sourcing in workflow invoke step.** Input is `{:session-id "..."}` but the `run` step creates a child session. Design doesn't specify how the invoke step obtains the child session-id. Workflow step outputs currently include `:text`, `:transcript`, `:logprobs`, `:final-llm-reply` — not `:session-id`. Either `:session-id` must be added as a step output surface, or an alternative sourcing mechanism must be specified.

3. **`:assistant-message` structure in event payload.** Design shows `{:role "assistant" :content [...]}` but doesn't state whether `:content` is the structured block array (`[{:type :text :text "..."}]`) or flattened string. Should specify: structured map as-is from `execution-result`.

4. **`:reply-text` derivation in `logprobs/perplexity` output.** Output includes `:reply-text` but stored data is structured `:assistant-message`. Design doesn't specify extraction logic. Should reference `turn-execution-contract/assistant-message-text` or equivalent.

5. **Workflow `report` step variable bindings unspecified.** Design says three steps (run → perplexity → report) but doesn't specify how `report` accesses both the perplexity result (from invoke step) and the original assistant text (from run step). Current `report` uses `{{transcript}}` from run — new bindings needed.

6. **Extension namespace convention.** Design says "same pattern as `psi/github`" (which uses `psi.github.*`) but other extensions use `extensions.*`. Should pick one explicitly.
