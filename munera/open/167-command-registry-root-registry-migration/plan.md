# Plan

1. Audit the current `command-registry` implementation and tests against the new `root-registry` API to identify the exact compatibility layer needed.
2. Refactor `command-registry` internals so root-registry owns storage, while command-registry retains command-specific validation and public compatibility behavior.
3. Update and extend tests to prove both the preserved command-registry boundary contract and the intended lower-layer storage usage.
4. Simplify the migrated command-registry shape so compatibility logic is explicit and localized.
