# Plan

## Approach

1. Make the task’s Munera execution surface explicit so design review, implementation execution, and later review passes all have canonical task-local artifacts:
   - `design.md` holds intent, scope, semantic decisions, and acceptance
   - `plan.md` holds the chosen implementation approach and proof strategy
   - `steps.md` holds executable implementation work only, including the initial artifact-maintenance obligation that keeps `design-steps.md` present as a maintained deliverable for this task
   - `design-steps.md` holds actionable design-review follow-up items only and is a maintained task-local artifact for this task rather than an assumed side file
   - `implementation.md` remains the append-only review/decision/blocker log

2. Use this task first as a semantic-classification task, then as a small implementation task:
   - classify which `deterministic-operation-registry` semantics should become shared lower `root-registry` semantics
   - classify which semantics stay adapter-owned
   - pin that target contract in task artifacts before implementation begins

3. Preserve the chosen alignment shape already implied by `design.md`:
   - add explicit duplicate-rejecting lower insertion semantics
   - keep explicit replace-capable lower registration semantics
   - keep lower error signaling result-oriented rather than exception-oriented
   - keep invoke-miss throwing and runtime-object concerns outside `root-registry`

4. Resolve the outstanding ordering ambiguity explicitly at the future migration boundary:
   - this task will classify registration-order semantics for the future `deterministic-operation-registry` target contract rather than leaving them merely deferred
   - the chosen classification is: preserved ordering remains part of the future deterministic-operation public/adapter boundary, but ordering storage/maintenance remains outside the shared `root-registry` lower contract for this task
   - if a later migration proves that adapter-owned ordering becomes too awkward or incoherent, create a named follow-on to add a principled lower ordering capability rather than silently expanding `root-registry` during migration

5. Keep `workflow-registry` as the immediate coherence check:
   - preserve current public behaviour
   - simplify any read-before-remove or exception-driven glue if the clarified lower result contract now makes direct mutation-result handling possible

## Proof strategy

- Task artifacts must agree on the shared-vs-adapter semantic split before code changes proceed.
- Focused tests should prove:
  - duplicate-rejecting lower insert semantics
  - explicit replace-capable lower registration semantics
  - outcome distinction between duplicate-id, ownership-conflict, and not-found
  - any `workflow-registry` follow-up simplification without public behaviour drift
- Task artifacts must also leave an unambiguous migration target for future `deterministic-operation-registry` work:
  - duplicate rejection via lower results
  - owner-scoped cleanup via shared owner semantics
  - invoke miss remains adapter/runtime owned
  - preserved registration-order semantics remain adapter/public-boundary owned and therefore are out of this task’s lower shared contract

## Constraints carried into execution

- Do not execute implementation work from `steps.md` during design-step follow-up passes.
- Do not broaden this task into full deterministic-operation migration.
- Do not silently redefine ordering semantics later in implementation; if pressure changes the choice, task artifacts must be updated first.
