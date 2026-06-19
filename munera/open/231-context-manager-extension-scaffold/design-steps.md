# Design Review — Architecture

- [x] Architecture review (2026-06-19): no architectural misfits found. Design correctly follows auto-session-name scaffold pattern, identifies both runtime and launcher catalog wiring points with parity awareness, uses correct `(:on api)` subscription and nullable API testing patterns.

# Design Review — Ambiguity

- [ ] Launcher catalog entry shape: design says "add the matching entry" but only shows the runtime's single-policy shape (`:installed`). Launcher entries have 3 policies (`:development`, `:installed`, `:jar`). Clarify the full launcher entry shape or explicitly direct to mirror an existing launcher entry (e.g. `psi/auto-session-name`).

# Design Review — Inconsistency

- [ ] Event payload key: design says `:psi.agent-session/turn-id` but the actual event payload in `turn/handlers.clj` uses unqualified `:turn-id`. Align the design to match the real payload key.
- [ ] `psi/ai` dependency: design says "no dependencies beyond Clojure core and timbre" and "performs no action beyond logging", but says to mirror `auto-session-name/deps.edn` which includes `psi/ai`. A logging-only scaffold does not need `psi/ai`. Remove it from the deps.edn spec or justify its inclusion.
