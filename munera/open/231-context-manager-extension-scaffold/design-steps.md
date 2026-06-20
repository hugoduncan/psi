# Design Review — Architecture

- [x] Architecture review (2026-06-19): no architectural misfits found. Design correctly follows auto-session-name scaffold pattern, identifies both runtime and launcher catalog wiring points with parity awareness, uses correct `(:on api)` subscription and nullable API testing patterns.

# Design Review — Ambiguity

- [x] Launcher catalog entry shape: resolved in wiring details — design now shows the full 3-policy launcher entry shape (`:development`, `:installed`, `:jar`) matching `psi/auto-session-name`.
- [x] Ambiguity review (2026-06-19, re-review): no new ambiguities found. Design is well-specified with explicit wiring details, namespace names, event payload keys, and test approach following auto-session-name pattern.

# Design Review — Inconsistency

- [x] Event payload key: resolved — design now correctly specifies unqualified `:turn-id` matching the actual event payload in `turn/handlers.clj`.
- [x] `psi/ai` dependency: resolved — design now explicitly excludes `psi/ai` from deps.edn, noting the scaffold only needs Clojure core.
- [x] Inconsistency review (2026-06-19, re-review): no new inconsistencies found. Design is internally consistent and consistent with auto-session-name reference, both catalogs, and extensions/deps.edn.

# Design Review — Ambiguity (re-review)

- [ ] Test namespace naming convention: design specifies `extensions.context_manager_test` (underscores in ns name) but codebase convention (e.g. `extensions.auto-session-name-test`) uses hyphens in namespace names. Clarify that the ns form should be `extensions.context-manager-test` to match convention.

# Design Review — Inconsistency (re-review)

- [x] Inconsistency review (2026-06-20): no new inconsistencies found. Design, plan, and steps are consistent with each other and with the auto-session-name reference pattern. Both catalogs, extensions/deps.edn wiring, and test approach are correctly specified.
