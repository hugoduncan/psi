# 064 — Plan

1. Audit docs/examples after Tasks `060`–`063` land for syntax drift against the settled session-first authoring surface.
2. Update modular GitHub workflow examples to use the new surface where it helps, including explicit step `:name`, `{:from ...}`, `:projection ...`, and `:session :preload` where appropriate.
3. Decide/document the long-term role of prompt-binding convenience while preserving `:session` as the primary authoring abstraction.
4. Land any final validation/error-shaping improvements needed to reinforce the settled model.
5. Re-run focused and broad verification as needed.
