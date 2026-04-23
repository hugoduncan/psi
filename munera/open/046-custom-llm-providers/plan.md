Approach:
- Treat issue #25 as a documentation/discoverability task because the runtime already supports custom providers through `models.edn`.
- Add one dedicated operator-facing guide instead of scattering the full explanation across multiple docs.
- Improve discoverability from existing entry points (`README.md`, configuration docs, and in-session help text).
- Keep code changes minimal and scoped to help-text discoverability plus a focused assertion proving the new help copy.

Decisions:
- Canonical setup guide lives in `doc/custom-providers.md`.
- The guide should document both user-global and project-local `models.edn` locations.
- The guide should cover both OpenAI-compatible and Anthropic-compatible providers.
- MiniMax should appear as the concrete example closest to the reported need.
- Examples are illustrative; docs should avoid claiming unverifiable provider-specific details beyond the config shape psi expects.

Risks:
- Provider-specific external details may drift over time, so examples should be framed as patterns users can adapt.
- Discoverability improvements should not imply a new config surface; they must stay consistent with the existing `models.edn` implementation.

Verification plan:
- Re-read changed docs and help text for consistency with the existing implementation.
- Run focused command/help tests for the changed in-session help copy.
