# Plan

## Approach

Follow the same structural separation already established and exercised in `067-custom-provider-anthropic-auth`:

- selected provider identity belongs in capture `:provider`
- transport identity belongs in capture `:api`

The implementation should be minimal and should target the OpenAI transport capture layer rather than broader request/auth execution paths.

## Planned slices

1. Inspect OpenAI capture paths
   - locate the transport-layer request and response capture helpers for `:openai-completions` and `:openai-codex-responses`
   - confirm where built-in `:openai` is hard-coded
   - locate where the resolved selected provider identity is available in model/request data for transport-layer captures
   - confirm whether selected provider identity is already available but ignored or must be threaded through

2. Add focused regression coverage
   - custom `:openai-completions` request capture identity
   - custom `:openai-completions` response capture identity
   - custom `:openai-codex-responses` request capture identity
   - custom `:openai-codex-responses` response capture identity
   - built-in OpenAI request/response capture behavior unchanged

3. Implement minimal capture-identity fix
   - thread selected provider identity into the OpenAI transport capture helpers
   - preserve current `:api` values and request execution behavior
   - update the transport-layer capture constructors consistently rather than patching only one derived user-visible surface
   - avoid widening into auth or routing behaviour unless required for capture correctness

4. Verify focused tests
   - run OpenAI provider test coverage for the touched paths
   - confirm focused execution/auth regression coverage remains green for the affected OpenAI-compatible provider paths

## Risks

- OpenAI transport capture helpers are shared by multiple transport flavors; the change should preserve built-in OpenAI behavior while allowing custom provider identity to flow through.
- Codex responses have slightly different request/auth semantics; this task should avoid widening into auth behavior unless needed for capture correctness.
- The selected provider may already be present in resolved model/request data but not currently used by capture constructors; the fix should exploit that narrow seam if possible.
