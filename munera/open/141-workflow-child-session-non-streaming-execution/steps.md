# Steps

- [ ] Add `:response-mode` to the relevant session and/or child-session schemas with accepted values `:streaming | :non-streaming`.
- [ ] Extend workflow step/session config shaping so `resolve-step-session-config` carries `:response-mode` and defaults to `:streaming` when absent.
- [ ] Extend workflow attempt child-session creation opts and child-session state creation so workflow-owned child sessions persist `:response-mode`.
- [ ] Identify the narrowest turn/prompt execution seam for transport branching and record it in `implementation.md` before changing execution code.
- [ ] Add a non-streaming execution path below that seam.
- [ ] Add OpenAI chat-completions non-streaming request/response normalization for assistant text, tool calls, usage, stop reason, errors, and logprobs.
- [ ] Branch prompt execution on session `:response-mode` while preserving the canonical execution-result shape.
- [ ] Add focused tests for workflow config propagation, child-session persistence, non-streaming execution selection, and streaming-default regression.
- [ ] Verify the motivating case: workflow child session can avoid `tools + stream + logprobs` provider rejection by selecting `:response-mode :non-streaming`.
