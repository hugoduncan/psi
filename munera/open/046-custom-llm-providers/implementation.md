Issue provenance:
- GitHub issue: #25
- URL: https://github.com/hugoduncan/psi/issues/25

2026-04-22
- Reviewed the existing implementation and confirmed the runtime already supports the issue's requested capability through `models.edn` files.
- Chose a documentation-and-discoverability slice rather than inventing a second configuration mechanism, because the requested behavior is already present in code, tests, and spec.
- Added `doc/custom-providers.md` as the dedicated user-facing guide for custom providers.
- Documented:
  - user-global and project-local `models.edn` locations
  - supported compatible protocol families
  - provider definition shape
  - MiniMax-style OpenAI-compatible configuration
  - Anthropic-compatible configuration
  - auth options including `env:` keys, optional auth-header suppression, and custom headers
  - `/reload-models` and `/model` usage after editing config
  - multiple-provider configuration and precedence behavior
- Added discoverability links from `README.md` and `doc/configuration.md`.
- Tightened `/help` text for `/reload-models` so the relevant config files are named directly in-session.
- Added a focused command-help assertion proving the in-session help now mentions both `~/.psi/agent/models.edn` and `.psi/models.edn`.
