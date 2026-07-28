# Design follow-ups — 246

## Ambiguity review

- [ ] Resolve concrete catalog metadata for each variant: (a) state whether `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna` share identical metadata (name, pricing, context-window, reasoning/thinking-level map) or differ per variant, and (b) resolve which context-window/pricing the Codex-routed entries carry — the design cites both a "272K short-context tier default" and a "larger context window with long-context pricing" without choosing. Implementation needs unambiguous per-id values.
