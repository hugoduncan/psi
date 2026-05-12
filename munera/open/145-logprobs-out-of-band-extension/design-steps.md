# Design follow-up steps

Unchecked items added by design ambiguity review pass 1 (2026-05-12).

- [ ] **A. Decide fate of `step_execution.clj` logprob formatting code.** `psi.workflow-runtime.statechart-runtime.step-execution` has `format-logprob-message`, `format-logprob-line`, `logprob-uncertain-threshold`, and `transcript-with-logprobs` — an independent copy of the `prompt_request.clj` code. These inject a synthetic user message into the workflow `:transcript` output for all session steps with logprobs. Decide: (a) remove them (consistent with "no synthetic messages" principle; `:transcript` output would no longer include logprob text), (b) keep them (workflow transcript remains enriched for non-logprobs-extension consumers), or (c) replace with a reference to the extension. Update design.md "What is removed from core" and steps.md accordingly.

- [ ] **B. Specify session-id sourcing for `logprobs/perplexity` invoke step in workflow.** The `run` session step creates a child session. The invoke step needs that child's session-id. Current workflow step raw outputs don't include `:session-id`. Either: (a) add `:session-id` to session-step raw outputs in `step_execution.clj`, or (b) use an alternative sourcing mechanism. Update design.md workflow section and steps.md with the chosen approach.

- [ ] **C. Specify `:assistant-message` structure in event payload.** State explicitly in design.md that `:assistant-message` in the `session_turn_finished` payload is the structured message map from the execution result (block array content, not flattened string).

- [ ] **D. Specify `:reply-text` extraction logic.** State in design.md how `logprobs/perplexity` derives `:reply-text` from the stored structured `:assistant-message`. Reference `turn-execution-contract/assistant-message-text` or define equivalent inline extraction.

- [ ] **E. Specify `report` step variable bindings in updated workflow.** Design says three steps (run → perplexity → report) but doesn't define `report`'s `:contributions` template or `:vars`. Specify how `report` accesses both the perplexity/token-count from the invoke step and the original assistant text from the run step.

- [ ] **F. Pick extension namespace convention.** `psi/github` uses `psi.github.*`; other extensions use `extensions.*`. Specify which convention the logprobs extension follows and update design.md.
