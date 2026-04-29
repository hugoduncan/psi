# Implementation notes

- Created to preserve selected provider identity in OpenAI-compatible provider captures.
- This task is the OpenAI-compatible follow-up to `067-custom-provider-anthropic-auth`.
- Expected invariant carry-forward:
  - `:api` selects transport identity
  - `:provider` preserves selected provider configuration identity
- Intended seam: OpenAI transport capture construction, not auth-resolution redesign.
- Capture `:provider` must be sourced from resolved selected provider identity, not inferred from transport `:api`.
