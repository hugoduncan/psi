# Plan

1. Inspect the current shared AI transport/provider HTTP seams and existing tests to choose the narrowest proxy enrichment point consistent with the design.
2. Implement a shared proxy helper in `components/ai/src/psi/ai/` with pure normalization, resolution, parsing, and request-option projection functions plus one edge helper for env-backed request enrichment.
3. Integrate proxy request-option enrichment into the Anthropic and OpenAI `http/post` boundaries without changing provider protocol/config surfaces.
4. Add focused helper and provider transport tests covering precedence, selection, failures, no-proxy behavior, and request-option injection.
5. Update user-facing docs for the canonical environment-variable proxy story and any supported/unsupported details proven by the implementation.
6. Run focused verification, record results and any design deviations in `implementation.md`, and leave `steps.md` synchronized with completion state.
