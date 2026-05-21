# Implementation

Created the task for migrating `tool-registry` onto the shared `root-registry` substrate after the `command-registry` adoption in task `167`.

Starting point:

- `tool-registry` is the clearest next direct adopter of `root-registry`
- the goal is to move storage ownership to `root-registry`
- current `tool-registry` public behavior should remain intact
- tool-specific validation and normalization must remain above the shared storage boundary

Next step:

- audit current `tool-registry` storage mechanics, focused tests, and direct callers against the completed `167` pattern, then shape the migration around the minimum compatibility adapter needed

2026-05-21 ambiguity review:

- Actionable ambiguities: built-in provenance ordering in merged `all-tools-in` is called out as both a must-preserve behavior and a question to resolve; required built-in vs extension provenance/read-shape fields are not pinned precisely enough for migration tests; same-owner duplicate replacement semantics are left open even though migration may need explicit contract-vs-policy treatment.

2026-05-21 ambiguity follow-up execution:

- Audited current `tool-registry`, its focused tests, the completed `167` command-registry migration, and direct caller usage in `app-runtime`.
- Resolved built-in ordering as preserved adapter behavior: built-ins remain first in `all-tools-in`, with built-in provenance registration order preserved ahead of extension registration order; duplicate names remain first-visible-wins on merged reads.
- Pinned caller-visible provenance/read-shape fields: built-ins expose `:source :built-in` plus `:ext-path` provenance id; extension-owned tool defs expose `:source :extension`, `:ext-path` owner path, and merged extension listings/lookup projections keep `:extension-path`.
- Decided same-owner duplicate replacement semantics are preserved for both extension-owned and built-in registrations, matching current assoc-by-name storage behavior and the `167` migration pattern.
- Updated `design.md`, `plan.md`, and `design-steps.md` so the migration can test these points explicitly instead of re-deciding them during implementation.
