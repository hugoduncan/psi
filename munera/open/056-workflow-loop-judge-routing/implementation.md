# 056 — Implementation notes

## Provenance

Designed in conversation 2026-04-25. Key design evolution:

1. Started from "loops as the most valuable next workflow extension"
2. Explored result-driven vs definition-driven vs judge-step approaches
3. Judge-as-separate-agent selected for separation of concerns and actor reusability
4. Projection model introduced so judge can control context window into actor session
5. Projection elevated from inline keys to named strategy (`:none`, `:full`, `{:type :tail ...}`)
6. Routing table (`:on`) introduced — maps judge signals to directives
7. Key insight: every step already has an implicit routing table; judge+`:on` just makes it explicit
8. Linear pipeline is the zero-configuration degenerate case — full backward compatibility
