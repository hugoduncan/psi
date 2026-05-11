# Plan

Implement this as one vertical slice with a lower execution seam extension, while keeping the user-visible scope workflow-only.

## Approach

1. **Propagate execution mode through workflow child-session config**
   - extend workflow step/session shaping to carry `:response-mode`
   - extend child-session state/schema to store `:response-mode`
   - preserve `:streaming` as the default when absent

2. **Extend the lower turn execution seam**
   - inspect current prompt execution entrypoints and identify the narrowest place to branch between streaming and non-streaming execution
   - add a non-streaming execution path that still returns the canonical execution-result shape
   - keep workflow runtime above the seam unchanged

3. **Implement provider support for the motivating path first**
   - add OpenAI chat-completions non-streaming request/response handling
   - normalize assistant content, tool calls, usage, stop reason, and logprobs into existing turn execution results
   - avoid widening this task into cross-provider completeness unless straightforward and local

4. **Add focused proofs**
   - workflow session-config propagation
   - child session state persistence
   - prompt execution branching on `:response-mode`
   - regression that absent `:response-mode` remains streaming

## Risks

- The current turn runtime is strongly structured around streamed events; the smallest coherent non-streaming path may need a second lower normalization function rather than trying to fake SSE events.
- Provider-specific response normalization may be the real complexity center; keep the first slice focused on OpenAI chat-completions-compatible models.
- The canonical execution-result contract must remain identical across streaming and non-streaming paths, otherwise workflow runtime consumers will leak transport knowledge.
