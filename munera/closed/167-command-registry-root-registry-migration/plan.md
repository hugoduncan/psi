# Plan

1. Audit the current `command-registry` implementation and tests against the new `root-registry` API to identify the exact compatibility layer needed, including which ordering and precedence behaviors are part of the preserved boundary contract.
2. Refactor `command-registry` internals so root-registry owns storage, while command-registry retains command-specific validation and public compatibility behavior.
3. Preserve explicit merged-read compatibility at the adapter layer: built-ins shadow same-name extension commands, and `all-commands-in` continues to list built-ins first, then extension commands in first-encounter order by extension registration order.
4. Do not broaden built-in/built-in collision semantics during this migration; preserve only the currently proven same-provenance replacement behavior and avoid introducing a new public winner rule unless implementation pressure makes a follow-on task necessary.
5. Update and extend tests to prove both the preserved command-registry boundary contract and the intended lower-layer storage usage.
6. Simplify the migrated command-registry shape so compatibility logic is explicit and localized.
