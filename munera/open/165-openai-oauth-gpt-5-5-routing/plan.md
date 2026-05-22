1. Add a shared auth-aware runtime-model resolution helper in `psi.ai.model-registry`.
   - Accept provider, model-id, and optional auth context.
   - Default to canonical catalog lookup.
   - For OpenAI `gpt-5.5`, when OpenAI OAuth credentials are present, return a Codex/ChatGPT transport variant of the same model id.

2. Update all runtime model-resolution callers to use the shared helper.
   - `psi.agent-session.prompt-request`
   - `psi.agent-session.commands`
   - `psi.app-runtime`
   - `psi.rpc.session`

3. Add focused regression tests.
   - shared helper tests for OAuth-backed vs non-OAuth-backed `gpt-5.5`
   - caller-level tests where practical for resolved runtime model semantics

4. Verify.
   - focused test namespaces green
   - live eval shows OAuth-backed `gpt-5.5` resolves to `:openai-codex-responses`
