# Steps

- [x] Add `:response-mode` to the relevant session and/or child-session schemas with accepted values `:streaming | :non-streaming`.
- [x] Extend workflow step/session config shaping so `resolve-step-session-config` carries `:response-mode` and defaults to `:streaming` when absent.
- [x] Extend workflow attempt child-session creation opts and child-session state creation so workflow-owned child sessions persist `:response-mode`.
  - [x] Widen `psi.workflow-runtime.attempts/create-step-attempt-session!` child-session opts to include `:response-mode`.
  - [x] Keep the workflow execution adapter handoff transport-agnostic while passing `:response-mode` through its create-child opts.
  - [x] Widen `psi.agent-session.context/create-workflow-child-session!` and `:session/create-child` params to carry `:response-mode`.
  - [x] Persist `:response-mode` in `psi.agent-session.child-session-state/child-session-base-state` before schema validation.
- [x] Identify the narrowest turn/prompt execution seam for transport branching and record it in `implementation.md` before changing execution code.
- [x] Add a non-streaming execution path below that seam.
- [x] Add OpenAI chat-completions non-streaming request/response normalization for assistant text, tool calls, usage, stop reason, errors, and logprobs.
- [x] Branch prompt execution on session `:response-mode` while preserving the canonical execution-result shape.
- [x] Add focused tests for workflow config propagation, child-session persistence, non-streaming execution selection, and streaming-default regression.
- [x] Verify the motivating case: workflow child session can avoid `tools + stream + logprobs` provider rejection by selecting `:response-mode :non-streaming`.
- [x] Preserve provider request/response captures in `:execution-result/provider-captures` for the non-streaming `:response-mode` path, and add focused proof that canonical capture shaping matches streaming execution.
