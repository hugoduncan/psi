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
3. Refactor `workflow-registry` internals to use `root-registry` as the lower storage owner while preserving current public behavior at the adapter boundary.
4. Update higher read/projection seams to use authoritative `workflow-registry` helpers rather than legacy direct root-state shape where needed.
5. Add focused migration-guard tests:
   - lower `workflow-registry` contract tests
   - at least one higher consumer seam coherence test proving migrated definitions are visible through the authoritative read surface
6. Verify with focused tests plus full `bb test`, then record any preserved adapter-owned compatibility behavior in task artifacts.
