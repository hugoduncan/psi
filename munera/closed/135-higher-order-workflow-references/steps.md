Slice 1 — Model and ownership alignment
- [x] Review current workflow grammar, IR compiler, source-resolution, and delegate runtime owners for static-target assumptions
- [x] Identify and record the canonical owner(s) for workflow-reference value semantics
- [x] Choose and record the preferred candidate external shape for workflow references: `{:type :workflow-ref :name "..."}`
- [x] Choose and record the preferred grammar shape for dynamic `:target`: `workflow-name | workflow-target-source-spec`
- [x] Choose and record the preferred IR-facing `delegate-spec` target union: static workflow-name string or source-spec-like map
- [x] Record the preferred layered validation split: authored target-shape validation in grammar/compiler, workflow-ref value validation in runtime source resolution, final availability validation in canonical delegate lookup/enforcement
- [x] Confirm the final exact IR-facing shape during implementation
- [x] Record the static delegate behaviors that must remain unchanged
- [x] Record that dynamic `:target` reuses the existing `source-spec` shape exactly rather than introducing a parallel target-source mini-language, including bare `:from` and optional `:path`/`:projection`
- [x] Record that if `:projection` is used in dynamic `:target`, the final resolved value must still be a valid workflow-reference value
- [x] Record that workflow references are expected to travel through structured data outputs rather than free-form yielded text
- [x] Record that workflow references are first-cut ordinary structured data values, not a new global output type system

Slice 2 — Grammar / IR / compiler extension
- [x] Extend delegate target modeling to support static workflow names and dynamic workflow-reference sources
- [x] Add explicit workflow-reference value semantics to the canonical workflow model
- [x] Update target-authored validation and IR compilation for the higher-order target path
- [x] Ensure malformed higher-order target shapes fail clearly
- [x] Preserve current static delegate compilation behavior

Slice 3 — Runtime resolution
- [x] Add explicit executable proof that valid-shaped dynamic workflow refs whose `:name` is unknown fail as lookup failures at delegation time
- [x] Add explicit executable proof that a previously selected workflow ref whose target is removed before delegation fails through the same lookup-failure path
- [x] Reconcile `steps.md` status with actual implemented/runtime-supported failure semantics so availability-only failure remains open and lookup failure is tracked separately
- [x] Keep authored-shape, runtime-type, lookup-failure, and availability-failure distinctions explicit in task notes after the follow-up proof lands
- [x] Preserve existing delegated yield/handoff behavior after target resolution while adding the follow-up failure proof
- [x] Re-verify deterministic/replay-friendly target resolution semantics after the added failure proof

Slice 4 — Proof shaping
- [x] Split `execute-run-dynamic-delegate-step-invokes-selected-workflow-reference-test` into focused tests for success, wrong-type failure, unknown-target lookup failure, and removed-before-delegation lookup failure
- [x] Add a shallow local helper in `components/agent-session/test/psi/agent_session/workflow_execution_test.clj` to compress repeated dynamic-delegate setup without hiding intent
- [x] Split `resolve-workflow-ref-source-spec-test` into separate success and rejection tests for clearer single-concern signal
- [x] Move malformed dynamic delegate-target compiler proof into its own dedicated compiler test with a name that matches the behavior under proof
- [x] Re-read shaped tests for clarity, signal, and robustness after the splits
- [x] Keep availability-only failure explicitly unproven/open unless a distinct runtime gate is implemented
- [x] Add at least one end-to-end higher-order workflow example proving selected workflow reference → later delegate execution
- [x] Verify downstream delegated yield/handoff behavior remains unchanged in the end-to-end proof

Slice 5 — Docs and coherence
- [x] Update workflow docs with the workflow-reference concept and dynamic delegation guidance
- [x] Document what higher-order workflow references are not, especially no runtime workflow generation
- [x] Add design-level worked examples for success, invalid plain-string dynamic target, and unavailable workflow target
- [x] Add a minimal worked example showing when to use static delegate targets vs workflow references in user-facing docs
- [x] Re-read task artifacts for coherence across design, plan, steps, and implementation notes
