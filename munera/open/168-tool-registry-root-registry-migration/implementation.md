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
