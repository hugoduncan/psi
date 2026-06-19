# Design Review — Architecture

- [x] Architecture review (2026-06-19): no architectural misfits found. Design correctly follows auto-session-name scaffold pattern, identifies both runtime and launcher catalog wiring points with parity awareness, uses correct `(:on api)` subscription and nullable API testing patterns.

# Design Review — Ambiguity

- [ ] Launcher catalog entry shape: design says "add the matching entry" but only shows the runtime's single-policy shape (`:installed`). Launcher entries have 3 policies (`:development`, `:installed`, `:jar`). Clarify the full launcher entry shape or explicitly direct to mirror an existing launcher entry (e.g. `psi/auto-session-name`).
