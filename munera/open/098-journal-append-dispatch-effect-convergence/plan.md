Plan:
- inspect the current `journal-append-in!` call graph and classify pure append, persistence side-effect, and callback wiring responsibilities
- choose the smallest canonical dispatch/effect shape that preserves the `state-kernel` -> `session-state` -> `agent-session` dependency slope
- move the authoritative append path first, then leave a narrow compatibility seam only if needed
- migrate representative callers and focused tests before considering any wider cleanup

Risks:
- journal append is touched by runtime, persistence, tests, and projections, so apparently local changes may expose hidden reliance on the callback seam
- replacing the callback path too broadly in one pass could entangle persistence and state update behavior unless the pure/effect boundary is kept crisp
