# Steps

- [x] Inventory all current `/logprobs`, `:logprobs-enabled`, and `:top-logprobs` ownership and record the canonical key-shape decision plus the workflow-only propagation boundary in `implementation.md` before code changes.
- [x] Add workflow session-config validation for `:logprobs` and optional `:top-logprobs`.
- [x] Extend workflow step/session shaping and `resolve-step-session-config` to carry logprob controls with explicit default semantics.
- [x] Widen workflow child-session creation/control surfaces so workflow-owned child sessions persist resolved logprob controls.
- [x] Ensure request-option projection and request building honor the persisted workflow-owned child-session logprob controls.
- [x] Remove the general `/logprobs` slash command surface, including dispatch, help text, autocomplete, and any dead command-only mutation/helper path.
- [x] Add focused tests for workflow config propagation, child-session persistence, request-option projection, and command-surface removal.
- [ ] Verify the motivating workflow case: workflow child session can combine `:response-mode :non-streaming` with logprob collection without needing a general `/logprobs` command.
- [ ] Add a focused proof that a workflow-authored session step/attempt carrying both `:response-mode :non-streaming` and enabled logprobs preserves both controls through child-session creation on the same execution path.
