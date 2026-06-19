# Design Review — Architecture

- [x] Architecture review (2026-06-19): no architectural misfits found. Design correctly follows auto-session-name scaffold pattern, identifies both runtime and launcher catalog wiring points with parity awareness, uses correct `(:on api)` subscription and nullable API testing patterns.

# Design Review — Ambiguity

- [x] Launcher catalog entry shape: resolved in wiring details — design now shows the full 3-policy launcher entry shape (`:development`, `:installed`, `:jar`) matching `psi/auto-session-name`.
- [x] Ambiguity review (2026-06-19, re-review): no new ambiguities found. Design is well-specified with explicit wiring details, namespace names, event payload keys, and test approach following auto-session-name pattern.

# Design Review — Inconsistency

- [x] Event payload key: resolved — design now correctly specifies unqualified `:turn-id` matching the actual event payload in `turn/handlers.clj`.
- [x] `psi/ai` dependency: resolved — design now explicitly excludes `psi/ai` from deps.edn, noting the scaffold only needs Clojure core.
