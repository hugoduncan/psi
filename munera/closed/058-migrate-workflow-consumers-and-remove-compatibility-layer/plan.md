# 058 — Plan

## Approach

Converge in four passes: inventory → classify retained-vs-migratable surfaces → migrate runtime consumers before deleting code → collapse test/docs onto the final surface. Do not start by deleting the compatibility namespace. First prove exactly which compatibility responsibilities still exist, then either move them to canonical homes or explicitly retain one minimal boundary.

Preferred outcome: full removal of `workflow_statechart_compat.clj` and removal of legacy control-flow helpers from `workflow_progression.clj`.

Fallback outcome: retain only a tiny, explicit boundary if migration proves a genuinely different responsibility still exists. If anything is retained, it must have:
- a named responsibility
- a small public surface
- no duplication of canonical execution semantics
- explicit documentation in task output and canonical workflow docs

## Current known consumers / hypotheses

Initial inventory already suggests these likely migration targets:

1. **Run creation still depends on compatibility compilation**
   - `workflow_runtime.clj` currently calls `workflow_statechart_compat/compile-definition`
   - likely only to obtain `:initial-step-id`
   - probable migration: use canonical `workflow-statechart/initial-step-id` directly and remove compatibility compilation from run creation

2. **Cancellation still depends on legacy progression namespace**
   - `psi_tool_workflow.clj`
   - `mutations/canonical_workflows.clj`
   - likely migration: move cancel ownership to either `workflow_runtime.clj` or a clearly canonical run-lifecycle surface, then update both callers

3. **Tests still assert compatibility surfaces directly**
   - `workflow_statechart_test.clj`
   - `workflow_progression_test.clj`
   - `workflow_lifecycle_test.clj`
   - `workflow_tools_test.clj`
   - likely migration: split tests into canonical chart/runtime/recording/lifecycle assertions and remove tests that only prove transitional wrappers

4. **Documentation still describes retained compatibility surfaces as authoritative enough to mention**
   - `workflow_statechart_canonical.md`
   - task-local notes/files

These hypotheses must be confirmed with a final consumer inventory before code deletion.

## Phases

### Phase 1 — Consumer inventory and classification

Produce a precise inventory of every remaining compatibility surface and each of its callers.

Target surfaces:
- `psi.agent-session.workflow-statechart-compat/*`
- compatibility-only parts of `psi.agent-session.workflow-progression`
- compatibility framing in workflow docs/tests

For each caller, classify it as one of:
- canonical runtime path
- public mutation / tool path
- test-only
- documentation-only
- justified retained boundary

Deliverable:
- a short task note in `implementation.md` recording the inventory and the intended disposition of each surface

Decision gate:
- if a surface has no non-test caller, migrate/delete it before doing any deeper runtime work

### Phase 2 — Remove compatibility compilation from run creation

Goal: make run creation depend only on canonical workflow definition helpers, not compatibility compilation.

Expected work:
- replace `workflow_statechart_compat/compile-definition` usage in `workflow_runtime.clj`
- derive `:current-step-id` from canonical `initial-step-id`
- ensure no run-creation behavior changes
- update/create tests proving run creation uses canonical definition order and initial-step selection without compatibility metadata

Decision gate:
- after this phase, `workflow_statechart_compat.clj` should either have zero runtime callers or only one clearly named retained consumer

### Phase 3 — Canonicalize remaining run lifecycle operations

Goal: remove legacy control ownership from `workflow_progression.clj` where those operations are now part of canonical workflow runtime state management rather than sequential compatibility behavior.

Primary candidates:
- `cancel-run`
- `resume-blocked-run`
- possibly `submit-result-envelope`, `record-execution-failure`, `submit-judged-result` if they are no longer used by active runtime execution paths and only survive in tests/tool seams

Expected work:
- identify which operations are truly runtime lifecycle operations versus legacy sequential progression helpers
- move canonical lifecycle operations to the right home if needed
  - likely `workflow_runtime.clj` for pure root-state run lifecycle changes
  - or another explicitly canonical runtime namespace if a better home is structurally clearer
- update callers:
  - `psi_tool_workflow.clj`
  - `mutations/canonical_workflows.clj`
  - any other direct callers
- keep `workflow_progression_recording.clj` as the record-only substrate
- do not create a renamed compatibility layer

Decision rule:
- if a function mutates run lifecycle state but is still part of the active canonical runtime contract, move/canonicalize it
- if a function exists only to preserve the old sequential progression model, delete it after callers/tests are migrated

### Phase 4 — Collapse tests onto canonical surfaces

Goal: stop proving transitional wrappers and instead prove the canonical architecture directly.

Expected reshaping:
- `workflow_statechart_test.clj`
  - remove direct dependency on `workflow_statechart_compat`
  - keep assertions about canonical run event/status semantics only if still canonical
  - add/retain assertions for hierarchical chart compiler behavior where most useful
- `workflow_progression_test.clj`
  - keep only tests for surfaces that remain intentionally public and canonical
  - migrate record-only assertions toward `workflow_progression_recording`
  - drop tests whose only purpose is to preserve transitional wrapper behavior
- `workflow_lifecycle_test.clj` / `workflow_tools_test.clj`
  - route helpers through canonical runtime/lifecycle surfaces
  - stop using compatibility progression helpers where avoidable

Decision gate:
- no test should require `workflow_statechart_compat.clj` unless that namespace is intentionally retained
- no test should lock in transitional API shapes accidentally

### Phase 5 — Remove dead code and finalize docs

When runtime callers and tests are migrated:
- delete `workflow_statechart_compat.clj` if unused
- remove dead compatibility helpers from `workflow_progression.clj`
- tighten namespace docstrings so they no longer describe transitional ownership that no longer exists
- update `workflow_statechart_canonical.md` to describe only the final authoritative surfaces
- update `implementation.md` with the final retained/removed surface summary

## Verification plan

Run verification in widening rings:

1. Focused workflow namespaces/tests
   - statechart tests
   - progression / progression-recording tests
   - runtime / execution / lifecycle tests
   - tool and mutation tests touching workflow operations

2. Isolated workflow suite
   - existing isolated workflow test target if still available

3. Full unit suite
   - `bb clojure:test:unit`

4. Full project test suite
   - `bb test`

For each failure, decide whether it indicates:
- a real remaining consumer
- an over-eager deletion
- a test asserting obsolete transitional behavior

## Risks

- `workflow_runtime.clj` may still encode hidden assumptions from compatibility compilation beyond `:initial-step-id`
- cancel/resume may look compatibility-shaped but still be the de facto canonical public lifecycle contract
- tests may currently provide the only coverage for subtle status/history semantics, so deletion must preserve behavioral proof elsewhere
- documentation may lag the actual post-migration surface and accidentally reintroduce ambiguity

## Decision rules

- Do not retain a compatibility namespace just because tests mention it.
- Do not move code unless the new home is more canonical and simpler.
- Do not preserve duplicate lifecycle helpers in both runtime and progression namespaces.
- If one small retained seam remains, document it as a deliberate boundary, not as a transition artifact.
- The task is complete only when the authoritative workflow docs match the code and tests.