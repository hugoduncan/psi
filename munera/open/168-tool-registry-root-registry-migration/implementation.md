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

2026-05-21 inconsistency review:

- Actionable inconsistency: `implementation.md` says the ambiguity follow-up already updated `design.md`, `plan.md`, and `design-steps.md` so built-in ordering can be tested explicitly, but `steps.md` still has unchecked `Prove or narrow multi-provenance built-in ordering for all-tools-in` work. The task files disagree on whether that ordering obligation is already resolved at the task-artifact level or remains open as implementation/test work. Added one unchecked `design-steps.md` follow-up to align the artifact story rather than re-decide it during execution.

2026-05-21 inconsistency follow-up execution:

- Aligned the task artifacts around the already-resolved ordering obligation from the ambiguity follow-up.
- Marked `steps.md` `Prove or narrow multi-provenance built-in ordering for all-tools-in` done because the task-level design/plan already pins that proof target explicitly rather than leaving it as unresolved design work.
- No additional changes to `design.md` or `plan.md` were needed because they already state the preserved built-ins-first ordering rule and plan-level test obligation.

2026-05-21 implementation pass:

- Migrated `psi.tool-registry.registry` to the shared `root-registry` substrate using the same owner-entry pattern as task `167`.
- Kept tool-specific validation and canonical normalization adapter-owned: kebab-case name checks, `normalize-tool-def`, and required `:format-request` enforcement still happen in `tool-registry` before root registration.
- Replaced direct `:built-in-tools` / `:extensions ... :tools` storage ownership with root-registry owner entries under registry id `:tools`, while preserving extension registration preconditions from the surrounding root state.
- Preserved merged public read semantics: built-ins-first ordering, first-visible-wins name shadowing, extension registration-order projection, and caller-visible provenance fields on built-in and extension reads.
- Expanded focused tests to prove same-owner replacement semantics, built-in provenance ordering, root-registry-backed built-in storage, and preserved public lookup/listing behavior.
- Verification: `clojure -M:test --focus psi.tool-registry.registry-test` ✅; `clj-kondo --lint components/tool-registry/src components/tool-registry/test` ✅.

2026-05-21 implementation review:

- Actionable gap: focused tests prove built-in provenance/read-shape on stored entries and on `all-tools-in`, but do not explicitly assert that successful built-in `get-tool-in` lookups preserve the public `:ext-path` provenance id required by the task design.

2026-05-21 implementation review follow-up execution:

- Added a focused `get-tool-in` assertion proving built-in lookup preserves `:ext-path "built-in:workflow"` on the public read surface.
- Marked the follow-up step done in `steps.md`.
- Verification: `clojure -M:test --focus psi.tool-registry.registry-test` ✅; `clj-kondo --lint components/tool-registry/src components/tool-registry/test` ✅.
