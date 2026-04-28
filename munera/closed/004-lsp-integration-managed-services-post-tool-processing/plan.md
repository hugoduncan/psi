SUPERSEDED by `065-remove-lsp-client` on 2026-04-28.

Approach:
- Treat this as a confidence and observability follow-on rather than a new architecture slice.
- Prefer replacing test seams with runtime-backed proof where affordable.
- Decide intentionally which debug instrumentation is permanent.

Likely steps:
1. inspect current live/debug test overlap
2. decide status of adapter debug atoms / telemetry surfaces
3. simplify overlapping tests using the babashka-backed proof as anchor
4. tighten service/telemetry query surfaces if needed

Risks:
- keeping too much test-only instrumentation alive as production surface
- losing useful diagnosis capability while simplifying tests
