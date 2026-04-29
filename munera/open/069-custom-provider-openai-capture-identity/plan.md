# Plan

## Approach

Follow the same structural separation already established for Anthropic-compatible providers:

- selected provider identity belongs in capture `:provider`
- transport identity belongs in capture `:api`

The implementation should be minimal and should target the OpenAI transport capture layer rather than broader request/auth execution paths.

## Planned slices

1. Inspect OpenAI capture paths
   - locate request/response capture helpers for chat completions and codex responses
   - confirm where built-in `:openai` is hard-coded

2. Add focused regression coverage
   - custom `:openai-completions` provider capture identity
   - custom `:openai-codex-responses` provider capture identity
   - built-in OpenAI provider unchanged behavior

3. Implement minimal capture-identity fix
   - thread selected provider identity into shared OpenAI transport capture helpers
   - preserve current `:api` values and request execution behavior

4. Verify focused tests
   - run OpenAI provider test coverage for the touched paths

## Risks

- OpenAI transport capture helpers are shared by multiple transport flavors; the change should preserve built-in OpenAI behavior while allowing custom provider identity to flow through.
- Codex responses have slightly different auth/token semantics; this task should avoid widening into auth behavior unless needed for capture correctness.
