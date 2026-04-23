Goal: close issue #25 by making psi's existing custom LLM provider support discoverable, documented, and easy to configure for real users.

Issue provenance:
- GitHub issue: #25
- URL: https://github.com/hugoduncan/psi/issues/25

Context:
- The issue asks for support for custom providers such as MiniMax that expose OpenAI-compatible or Anthropic-compatible APIs.
- The issue also describes, as a nice-to-have, configuring new providers in a config file with type, URL, and key so users can define multiple providers and switch between them at runtime.
- The current codebase already implements this capability through `.psi/models.edn` and `~/.psi/agent/models.edn`:
  - custom providers can be declared with a provider id, API protocol, base URL, auth config, and models
  - custom providers dispatch through the built-in OpenAI-compatible or Anthropic-compatible transports according to the declared API
  - custom provider auth and custom headers are already supported
  - project-local and user-global model definition files are already loaded and can be reloaded
  - users can switch models at runtime through the existing model-selection surfaces
- What is missing is clear operator-facing guidance that tells a user this capability exists, how to configure it, which file to edit, how to reload it, and how to switch to the configured provider/model.

Problem:
- A user reading the current top-level docs would not reliably discover that psi already supports custom providers through `models.edn`.
- The repository has implementation, tests, and an internal spec for custom providers, but not a clear user-facing setup guide.
- As a result, the issue remains effectively unresolved from the user's point of view even though the runtime capability already exists.

Desired outcome:
- A user who wants to configure MiniMax or another OpenAI-compatible or Anthropic-compatible provider can do so by following repository documentation alone.
- The docs make the canonical configuration surface explicit:
  - `~/.psi/agent/models.edn` for user-global custom providers
  - `<worktree>/.psi/models.edn` for project-local custom providers
- The docs explain the required provider fields in practical terms:
  - provider id
  - API protocol type
  - base URL
  - auth configuration
  - models
- The docs explain how to reload definitions and how to select the configured model at runtime.
- The docs include at least one concrete example that maps closely to the issue request, such as MiniMax.

Scope:
In scope:
- add a dedicated user-facing guide at `doc/custom-providers.md`
- document the canonical `models.edn` file locations and precedence
- document the practical setup flow for OpenAI-compatible and Anthropic-compatible custom providers
- document auth options that materially affect setup, especially API keys and optional auth-header suppression
- document runtime usage after configuration, including reload and model switching
- add concise discoverability links from existing high-traffic docs such as `README.md` and `doc/configuration.md`
- tighten the `/reload-models` help text so it explicitly points users at the custom-model definition files

Out of scope:
- redesigning the custom provider data model
- adding a new configuration mechanism beyond `models.edn`
- changing model registry precedence or merge semantics
- adding new transport implementations
- changing provider execution semantics
- adding new commands for custom provider management
- changing the existing runtime model selection behavior

Canonical user flow to document:
1. Choose the config scope:
   - user-global: `~/.psi/agent/models.edn`
   - project-local: `.psi/models.edn`
2. Define a custom provider entry with:
   - a provider key
   - `:base-url`
   - `:api` set to the compatible wire protocol
   - optional `:auth`
   - one or more `:models`
3. Reload model definitions with `/reload-models` if psi is already running.
4. Select the configured model through the normal model-selection surface.
5. Use the model like any other provider/model in psi.

Design decisions:
- Treat the issue as a discoverability and operator-guidance gap, not as a missing runtime capability.
- Keep the implementation scoped to user-facing documentation and light discoverability improvements.
- Preserve the existing `models.edn` configuration shape as the canonical answer to the issue's requested config-file setup.
- Use examples that demonstrate both the required fields and the runtime workflow after editing the file.

Acceptance:
- repository documentation clearly states that psi supports custom LLM providers through `models.edn`
- repository documentation explains both supported custom-provider protocol families:
  - OpenAI-compatible
  - Anthropic-compatible
- repository documentation states where to put custom provider definitions for user-global and project-local scope
- repository documentation includes a concrete example for a custom provider similar to the issue request
- repository documentation explains how to reload the definitions and switch to the configured model at runtime
- discoverability is improved from existing entry-point docs so a user can find this capability without reading source or tests
- any in-session help text change remains aligned with the documented canonical behavior

Why this design is complete and unambiguous:
- the runtime capability already exists, so the remaining work is specifically to expose and explain that capability
- the canonical file surfaces, runtime commands, and supported protocol families already exist in code and tests
- the task does not depend on unresolved product decisions or new runtime semantics
- the scope is intentionally narrow: make the existing feature legible and usable from the docs and light UX copy
