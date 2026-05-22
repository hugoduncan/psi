# Plan

## Approach

Execute task `171` only after task `172` is complete. That prerequisite is already satisfied (`munera/closed/172-deterministic-operation-registration-order-removal`).

Use a single migration path:

1. inventory the current deterministic-operation write/read/cleanup seams named in `design.md`
2. move canonical operation entry ownership onto shared `root-registry` storage
3. keep invoke and public registry-object behaviour adapter-owned
4. update focused lower and higher proofs for the migrated ownership split

## Task-local artifact roles

- `design.md` — intent, scope, semantic boundaries, acceptance
- `plan.md` — implementation approach and prerequisite guard
- `steps.md` — execution checklist for implementation work only
- `design-steps.md` — review-follow-up checklist for design clarification work
- `implementation.md` — append-only notes for review results, decisions, discoveries, and blockers

## Prerequisite guard

This task is not blocked on unresolved ordering semantics because task `172` is already closed. Task `171` must therefore preserve the unordered listing contract introduced by `172` and must not reintroduce adapter-owned ordering metadata or ordering guarantees while migrating storage to shared `root-registry`.
