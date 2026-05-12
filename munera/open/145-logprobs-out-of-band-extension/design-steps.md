# Design follow-up steps

Items from design ambiguity review pass 1 (2026-05-12). All resolved.

- [x] **A. Decide fate of `step_execution.clj` logprob formatting code.** Remove. `:transcript` output becomes assistant-message-only. Updated design.md, steps.md, plan.md.

- [x] **B. Specify session-id sourcing for `logprobs/perplexity` invoke step in workflow.** Add `:session-id` to session-step raw outputs. Invoke step uses `{:from {:step "run" :output :session-id}}`. Updated design.md, steps.md, plan.md.

- [x] **C. Specify `:assistant-message` structure in event payload.** Structured block-array form from execution-result. Updated design.md.

- [x] **D. Specify `:reply-text` extraction logic.** Derived via `turn-execution-contract/assistant-message-text`. Updated design.md, steps.md.

- [x] **E. Specify `report` step variable bindings in updated workflow.** Vars reference `run`'s `:final-llm-reply` and `perplexity`'s `:result` envelope via `:path`. Updated design.md, steps.md.

- [x] **F. Pick extension namespace convention.** `extensions.logprobs` (majority convention). Updated design.md, plan.md.
