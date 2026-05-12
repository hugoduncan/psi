# Implementation Notes

## Design ambiguity review — pass 1 (2026-05-12)

Six ambiguities found and resolved:

1. **Duplicated logprob formatting in `step_execution.clj` not addressed.** → **Resolved**: remove the duplicate. `:transcript` output becomes assistant-message-only. Updated design.md "Conversation injection removal" and "What is removed from core"; updated steps.md with new step.

2. **`logprobs/perplexity` session-id sourcing in workflow invoke step.** → **Resolved**: add `:session-id` to session-step raw outputs in `execute-session-step!`. Value is `(:session-id execution-session)`, already available. Invoke step references `{:from {:step "run" :output :session-id}}`. Updated design.md with new "Session step `:session-id` output" section; updated steps.md and plan.md.

3. **`:assistant-message` structure in event payload.** → **Resolved**: explicitly stated as structured block-array form from execution-result (`{:role "assistant" :content [{:type :text :text "..."}]}`). Updated design.md turn-finished event section.

4. **`:reply-text` derivation in `logprobs/perplexity` output.** → **Resolved**: derived via `turn-execution-contract/assistant-message-text`. Updated design.md perplexity operation section and steps.md.

5. **Workflow `report` step variable bindings unspecified.** → **Resolved**: `report` step vars reference `run`'s `:final-llm-reply` for reply-text, and `perplexity`'s `:result` envelope via `:path` for perplexity/token-count. Updated design.md with explicit `report` step variable bindings section; updated steps.md.

6. **Extension namespace convention.** → **Resolved**: `extensions.logprobs` (majority convention). `psi.github.*` is the outlier. Updated design.md constraints and plan.md.
