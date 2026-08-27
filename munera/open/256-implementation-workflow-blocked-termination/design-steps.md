# Design review follow-ups

- [ ] Define an authored, projection-compatible terminal-result topology for both `IMPLEMENTATION_COMPLETE` and `IMPLEMENTATION_BLOCKED`: the documented standalone projection reads the last declared workflow step rather than the last executed route, so two branch-specific terminal summaries cannot both be exposed by step ordering alone. Preserve the dedicated blocked handback and use an existing generic workflow contract/mechanism (or revise the design's stated mechanism) without changing generic runtime loop semantics or relying on declaration order accidentally.
