# Implementation

Created the task for migrating `command-registry` onto the shared `root-registry` substrate from task `166`.

Starting point:

- `command-registry` is the strongest first migration target
- the goal is to move storage ownership to `root-registry`
- current command-registry public behavior should remain intact
- any compatibility behavior that differs from raw root-registry semantics should live at the command-registry layer

Next step:

- audit current command-registry storage mechanics and public contract against the new root-registry API, then shape the migration around the minimum compatibility adapter needed
