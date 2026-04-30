# Implementation notes

- Created to preserve selected provider identity in OpenAI-compatible provider captures.
- This task is the OpenAI-compatible follow-up to `067-custom-provider-anthropic-auth`.
- Expected invariant carry-forward:
  - `:api` selects transport identity
  - `:provider` preserves selected provider configuration identity
- Intended seam: OpenAI transport capture construction, not auth-resolution redesign.
- Capture `:provider` must be sourced from resolved selected provider identity, not inferred from transport `:api`.
- OpenAI-compatible capture hard-coding lived in `components/ai/src/psi/ai/providers/openai/transport.clj`; unlike Anthropic, the transport helper emitted `:provider :openai` directly for both request and response captures.
- The minimal fix was to make the transport capture helpers derive provider identity from the resolved `model` (`(:provider model)` with built-in fallback `:openai`) and then thread `model` through all OpenAI-compatible request/reply/error capture call sites.
- Touched OpenAI-compatible seams consistently:
  - chat completions request captures
  - chat completions streamed response captures
  - chat completions error captures
  - codex request captures
  - codex streamed response captures
  - codex error captures
- Added focused regression coverage in `components/ai/test/psi/ai/providers/openai_test.clj` for:
  - built-in codex request/reply captures still reporting `:provider :openai`
  - custom `:openai-codex-responses` request/reply captures reporting selected provider identity while keeping `:api :openai-codex-responses`
  - custom `:openai-completions` request/reply captures reporting selected provider identity while keeping `:api :openai-completions`

## Review note

- Review outcome: implementation matched design and architecture; follow-up added the missing built-in `:openai-completions` request/reply capture identity regression. Decision: keep `069` closed and record this as a closed-task test-only follow-up.
