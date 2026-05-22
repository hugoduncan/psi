# Plan

Implement this task as a focused semantic simplification pass before any shared-storage migration.

## Approach

1. Remove adapter-owned registration-order state from `deterministic-operation-registry` so `:operations` is the only authoritative local storage concept.
2. Update registry listing/count query surfaces to derive directly from registered operations without any ordering promise.
3. Rewrite focused tests to assert membership, cardinality, cleanup coherence, duplicate-rejection stability, and unchanged invoke semantics rather than insertion order.
4. Revisit higher proof surfaces that mention deterministic-operation listings and relax any ordered assertions to unordered membership assertions.
5. Update task `171` so its migration target no longer carries adapter-owned ordering metadata.

## Key decisions

- This task removes the ordering contract rather than replacing it with sorted output; listing order becomes explicitly unspecified.
- Assertions that need stable comparison should sort or compare sets at the test boundary, not in production registry code.
- This task stays separate from the root-registry migration; only order-related semantics and proof surfaces change here.

## Risks and checks

- Risk: a test or projection still depends on insertion order indirectly.
  - Check affected deterministic-operation registry tests and higher extension cleanup tests for ordered expectations.
- Risk: removing `:registration-order` accidentally changes duplicate or invoke semantics.
  - Keep focused proof coverage for duplicate rejection, no-op cleanup, and missing invoke errors.
- Risk: task `171` still documents the old target shape.
  - Update its design after this task's semantic direction is clear.
