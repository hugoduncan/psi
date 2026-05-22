# Plan

1. Audit the current `workflow-registry` implementation, tests, and higher callers to enumerate all write seams and all read/projection/introspection seams that touch workflow-definition storage.
2. Refine the task design with explicit compatibility decisions for:
   - id normalization
   - blank-id UUID generation
   - replace-on-register semantics
   - sorted reads
   - nil lookup miss
   - remove-miss behavior
   - tuple-shaped lower return contract
   - preservation of `[:workflows :definitions]` as the canonical persisted compatibility path for this migration
   - classification of extension-runtime `:loaded-definitions` as a coherent higher projection/cache rather than canonical persisted storage
3. Refactor `workflow-registry` internals to use `root-registry` as the lower storage owner while preserving current public behavior at the adapter boundary and preserving the canonical persisted path exposed by compatibility helpers/docs/tests.
4. Update higher read/projection seams to use authoritative `workflow-registry` helpers where they expose workflow-definition semantics, while keeping intentional canonical-path compatibility seams coherent.
5. Add focused migration-guard tests:
   - lower `workflow-registry` contract tests
   - canonical-path compatibility coverage where root-state layout is intentionally preserved
   - at least one higher consumer seam coherence test proving migrated definitions are visible through the authoritative read surface and remain coherent with any in-memory projection/cache such as `:loaded-definitions` where relevant
6. Verify with focused tests plus full `bb test`, then record any preserved adapter-owned compatibility behavior and seam classifications in task artifacts.
