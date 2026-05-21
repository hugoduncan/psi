# Implementation

Created the task for migrating `command-registry` onto the shared `root-registry` substrate from task `166`.

Starting point:

- `command-registry` is the strongest first migration target
- the goal is to move storage ownership to `root-registry`
- current command-registry public behavior should remain intact
- any compatibility behavior that differs from raw root-registry semantics should live at the command-registry layer

Next step:

- audit current command-registry storage mechanics and public contract against the new root-registry API, then shape the migration around the minimum compatibility adapter needed

- 2026-05-21 ambiguity review: actionable feedback found. `design.md` / `plan.md` preserve merged visibility and collision behavior in broad terms, but they do not pin (1) how same-name collisions across multiple built-in provenance ids should behave once built-ins are stored through unordered root-registry entries, (2) whether current `all-commands-in` ordering guarantees (`built-ins first`, then first-encounter extension-registration order) are part of the migration contract, or (3) where design follow-up items should live for this task given `design-steps.md` was absent. Added new unchecked items to `design-steps.md`; no duplicates found.
