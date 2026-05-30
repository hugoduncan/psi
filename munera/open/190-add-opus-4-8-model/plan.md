# Plan — 190-add-opus-4-8-model

## Approach

Implement the task as five vertical slices, keeping each slice independently testable and aligned with the stable design. The fifth slice is the integration/coherence slice, not a separate unnumbered cleanup pass.

Key decisions from the design:

- Add `claude-opus-4-8` as an Anthropic adaptive-thinking model with native JSON Schema support and mid-conversation system-message support.
- Treat `/speed` as a session setting with optional `session|project|user` scope. `:normal` is the user-facing default; request shaping omits provider speed parameters for nil and `:normal`.
- Treat `/effort` as an optional provider reasoning-effort override, separate from `thinking-level`, with scoped persistence and explicit nil clearing for `none`.
- Make adaptive Anthropic `thinking-level :xhigh` and `/effort xhigh` send `output_config.effort = "highest"`; do not add fallback/retry behavior.
- Expose one Anthropic-compatible mid-system injection API for all supporting providers: valid only after the latest conversational user turn and before the next assistant generation.
- Preserve speed and effort as session-transient settings on cold resume; project/user config applies only to new root sessions.
- Preserve pre-cut active `:mid-system` instructions through compaction by coalescing and reattaching them only at a valid next-generation boundary.

## Risks

- Opus 4.8 pricing is a placeholder copied from Opus 4.7 until Anthropic publishes official pricing.
- Anthropic fast mode and `output_config.effort = "highest"` may be provider-side preview/unsupported in some environments; psi should shape locally valid requests and surface provider errors without hidden fallback.
- Persisted explicit defaults require presence-aware config resolution; accidentally adding `:speed-mode` or `:effort-override` to system defaults would break masking semantics.
- Mid-system placement has several edge cases: metadata after user turns, pending current-user replacement, provider request validation, and compaction boundaries. These need focused tests before broad verification.
- Runtime-resolved model capability checks must match request execution; resolver and dispatch gating must share the same predicate to avoid extension-visible drift.

## Slice order

1. **Model catalog and live model API tests** — Add Opus 4.8 catalog metadata, structured-output capability registration, mid-system model flag, unit coverage, and gated Anthropic Models API tests.
2. **Speed mode stack** — Add session schema/state, scoped command, persistence/config startup resolution, request-option propagation, provider request shaping, resolver/footer/docs, and speed tests.
3. **Effort override and xhigh stack** — Add session schema/state, scoped command, persistence/config startup resolution, request-option propagation, Anthropic/OpenAI/Codex effort shaping, resolver/footer/docs, and effort tests.
4. **Mid-conversation system messages** — Add schemas, journal projection, conversation/provider transforms, dispatch and extension mutation/API, EQL capability resolver, prepared-turn preservation, compaction preservation, docs, and focused tests.
5. **Integration and coherence pass** — Run focused tests per slice, then broader lint/test verification, update user-facing docs/changelog if behavior is user-visible, and record implementation notes.
