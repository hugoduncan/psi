Goal: remove the remaining internal backward-compatibility scaffolding now that canonical runtime shapes are established.

Context:
- Major compatibility cleanup has already landed across prompt paths, adapters, payload shapes, and session directory semantics.
- Remaining work is concentrated in final prompt semantics convergence, persisted header cleanup, and proof/lint/test sweeps.

Acceptance:
- only canonical internal payload/runtime shapes remain where planned
- compatibility branches deleted do not regress behavior
- proof commands pass (`bb clojure:test`, `bb lint`, `bb fmt:check`)
