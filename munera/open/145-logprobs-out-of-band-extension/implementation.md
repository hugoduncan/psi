# Implementation Notes

## Design ambiguity review — pass 1 (2026-05-12)

Six ambiguities found and resolved:

1. **Duplicated logprob formatting in `step_execution.clj` not addressed.** → **Resolved**: remove the duplicate. `:transcript` output becomes assistant-message-only. Updated design.md "Conversation injection removal" and "What is removed from core"; updated steps.md with new step.

2. **`logprobs/perplexity` session-id sourcing in workflow invoke step.** → **Resolved**: add `:session-id` to session-step raw outputs in `execute-session-step!`. Value is `(:session-id execution-session)`, already available. Invoke step references `{:from {:step "run" :output :session-id}}`. Updated design.md with new "Session step `:session-id` output" section; updated steps.md and plan.md.

3. **`:assistant-message` structure in event payload.** → **Resolved**: explicitly stated as structured block-array form from execution-result (`{:role "assistant" :content [{:type :text :text "..."}]}`). Updated design.md turn-finished event section.

4. **`:reply-text` derivation in `logprobs/perplexity` output.** → **Resolved**: derived via `turn-execution-contract/assistant-message-text`. Updated design.md perplexity operation section and steps.md.

5. **Workflow `report` step variable bindings unspecified.** → **Resolved**: `report` step vars reference `run`'s `:final-llm-reply` for reply-text, and `perplexity`'s `:result` envelope via `:path` for perplexity/token-count. Updated design.md with explicit `report` step variable bindings section; updated steps.md.

6. **Extension namespace convention.** → **Resolved**: `extensions.logprobs` (majority convention). `psi.github.*` is the outlier. Updated design.md constraints and plan.md.

## Design inconsistency review — pass 1 (2026-05-12)

One inconsistency found:

1. **`prompt_request.clj` removal list omits `format-token-str` and `format-prob`.** design.md "What is removed from core" and steps.md step 1 enumerate only `format-logprob-message`, `format-logprob-line`, and `logprob-uncertain-threshold` for `prompt_request.clj`. But `format-token-str` (line 18) and `format-prob` (line 27) are private helpers in `prompt_request.clj` exclusively called by the functions being removed — they become dead code. The parallel `step_execution.clj` enumeration correctly includes all six functions. design.md and steps.md should list `format-token-str` and `format-prob` for `prompt_request.clj` removal too.
