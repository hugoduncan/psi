# Steps

- [x] Make `design-steps.md` an explicit maintained task artifact for this task’s execution/review surface so design follow-up work is tracked canonically outside `steps.md`.
- [x] Implement the chosen `root-registry` semantic split with explicit duplicate-rejecting insert and explicit replace-capable registration operations.
- [x] Add focused `root-registry` tests proving duplicate-id, ownership-conflict, replace, remove-miss, and owner-scoped clear result behaviour.
- [x] Audit `workflow-registry` call sites against the clarified lower contract and simplify any preflight/translation glue that is now unnecessary while preserving public behaviour.
- [x] Add focused `workflow-registry` tests proving any changed lower-interaction path while preserving current public behaviour.
- [ ] Record the final future-target migration guidance for `deterministic-operation-registry` in the task artifacts and verify all task files agree on the shared-vs-adapter semantic split.
