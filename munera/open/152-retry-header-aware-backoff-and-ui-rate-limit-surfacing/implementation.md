2026-05-14 ambiguity review
- Missing implementation.md at review start; created for review log continuity.
- Ambiguity: task requires new actionable follow-up items in `design-steps.md`, but task only had `steps.md`; review uses new `design-steps.md` as requested.
- Ambiguity: design says stale retry metadata must clear when retry waiting ends or is superseded, but does not define whether successful non-retry terminal completion (`on-agent-done`) must always clear it as the canonical owner rather than relying only on later replacement.
- Ambiguity: design requires one canonical retry-metadata surface across backend projections and RPC, but does not specify whether `session-summary`, Pathom resolvers, and RPC `session/updated` must expose the same nested shape/field names or may diverge into UI-specific flattened payloads; define one authoritative shape.
- Ambiguity: design requires Emacs and TUI to render retry timing/rate-limit information, but does not pin the exact backend-owned TUI-visible surface (status line vs footer usage/session-activity vs other summary surface), leaving acceptance subjective.
- Ambiguity: design requires provider headers to reach the retry scheduler, but does not identify the canonical error/result shape that must carry headers across transport → turn/runtime → session; without that contract, "preserve headers" is underspecified.
