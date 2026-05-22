Goal: Make OpenAI OAuth-backed sessions able to use `gpt-5.5` by routing the model through the ChatGPT/Codex backend when OAuth credentials are the active OpenAI auth path.

Why:
- `gpt-5.5` currently resolves to the OpenAI Platform chat-completions backend (`api.openai.com/v1`).
- In live runtime, OpenAI OAuth credentials are ChatGPT access tokens and work for the ChatGPT/Codex backend (`chatgpt.com/backend-api`) but can fail on the platform API with `insufficient_quota`.
- The user can use `gpt-5.5` from Codex with the same account, so psi should not force the platform backend when the active auth path is OAuth.

Scope:
- Preserve `gpt-5.5` as the canonical model id exposed to users.
- Make runtime model resolution auth-aware for OpenAI only.
- When OpenAI OAuth credentials are present and no explicit non-OAuth OpenAI auth override is selected for the request, resolve `openai/gpt-5.5` to a runtime model using the ChatGPT/Codex transport.
- Keep existing non-OAuth behavior for platform-style OpenAI auth.

Constraints:
- Prefer a localized change at model-resolution seams rather than broad provider-auth redesign.
- Do not require the user to choose a different visible model id.
- Keep built-in model catalog coherent and deterministic.
- Preserve existing behavior for other providers and for custom provider auth.

Acceptance:
- A runtime resolution helper can resolve `{:provider "openai" :id "gpt-5.5"}` to a ChatGPT/Codex-backed runtime model when OpenAI OAuth is available.
- Existing runtime callers (`prompt-request`, app-runtime, rpc, command paths) use the shared resolution helper instead of ad hoc direct registry lookup.
- Focused tests cover both OAuth-backed and non-OAuth-backed resolution for `gpt-5.5`.
- Live eval proves the OAuth-backed resolved runtime model for `gpt-5.5` uses `:openai-codex-responses` / `https://chatgpt.com/backend-api`.
