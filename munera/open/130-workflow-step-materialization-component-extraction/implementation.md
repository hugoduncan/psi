2026-05-07

Task created from post-123/124/125/126/127/128/129 workflow boundary review.

Creation rationale:
- after the workflow runtime extraction and the workflow step session-config follow-on task, the strongest remaining lower workflow extraction candidate is workflow step materialization and source-resolution ownership
- `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution` are cohesive lower-owned derivation logic, but they are not runtime-core execution/progression/statechart semantics
- the intent is to give workflow step input/session-conversation materialization a more precise lower component home without moving it back upward into `agent-session` or recombining it with step session-config policy

Initial boundary notes:
- likely owned responsibilities: source binding resolution, source-spec application, template rendering, step input materialization, child-session conversation materialization, prompt/preload splitting, and prompt derivation
- expected non-goal: do not bundle step session-config back into this extraction
- expected review point: `source-resolution` currently appears to depend on workflow-judge projection semantics, so preserve behavior first and record whether that dependency still belongs here or should become later cleanup

2026-05-07 — Implementation landed locally.

Final component/namespace name
- chosen component name: `workflow-step-materialization`
- chosen namespace family: `psi.workflow-step-materialization.*`
- final namespace shape: small internal split
  - `psi.workflow-step-materialization.core`
  - `psi.workflow-step-materialization.source-resolution`
- naming decision: kept the narrower `workflow-step-materialization` name rather than broader `workflow-materialization` because the owned surface remains specifically about workflow step input derivation, child-session conversation materialization, and adjacent source-resolution/template semantics used directly by that step-materialization surface

Responsibility review and final inventory
- reviewed prior authoritative owners `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution`
- confirmed they jointly owned exactly the task-targeted lower workflow materialization cluster:
  - binding/source reference resolution
  - source-spec application
  - workflow-authored template rendering
  - step input materialization
  - child-session conversation materialization for session steps
  - prompt/preload splitting from materialized conversation
  - prompt derivation via `step-prompt`
  - invoke-arg materialization
  - delegate prompt/context materialization
- final extracted component keeps all of those responsibilities together as one cohesive lower workflow-domain derivation surface

Source-resolution ownership status
- `source-resolution` was treated as intrinsic to the extracted workflow step materialization component rather than as a separate remaining runtime-core owner
- reason: current source-spec/source-ref/template semantics are the derivation substrate used directly by step input materialization, session conversation materialization, delegate prompt/context materialization, and invoke-arg resolution; splitting them back apart would have left the extracted component artificially thin and the runtime component broader than intended

Public surface
- preserved public vars in `psi.workflow-step-materialization.core`
  - `binding-source-value`
  - `materialize-step-inputs`
  - `materialize-step-session-conversation`
  - `split-step-session-conversation`
  - `step-prompt`
- canonical long-term behavior surfaces
  - `materialize-step-inputs`
  - `materialize-step-session-conversation`
  - `split-step-session-conversation`
  - `step-prompt`
- currently consumed surface preserved for extraction safety
  - `binding-source-value` remains public because existing runtime-adoption proof sites intentionally consume the canonical binding-ref surface directly
- preserved public vars in `psi.workflow-step-materialization.source-resolution`
  - `get-path*`
  - `source-spec?`
  - `resolve-source-ref`
  - `apply-source-spec`
  - `materialize-template-vars`
  - `render-template-contribution`
  - `materialize-contribution`
  - `materialize-contributions`
  - `resolve-invoke-args`
  - `resolve-delegate-context`
  - `render-delegate-prompt-string`
  - `resolve-binding-ref`
- preserved call contracts and caller-visible output contracts exactly; this task only changed namespace/component ownership and direct consumers

Dependency/input shape
- `psi.workflow-step-materialization.core` now derives from provided workflow-run/effective-definition inputs plus its sibling lower source-resolution namespace
- the extracted component no longer depends on `psi.workflow-runtime.statechart` or `psi.workflow-runtime.ir`; it locally computes effective-step lookup and locally preserves canonical output/yield resolution semantics from the prior lower runtime owners
- `workflow-runtime` now depends downward on the extracted component for invoke/delegate source materialization needs and higher `agent-session` callback assembly depends downward on the extracted component for session-step conversation materialization

Source-resolution dependency status
- the extracted component retains a direct dependency on `psi.workflow-judge` projection behavior
- classification: legitimate shared lower workflow semantics for now
- reason: `:projection` application is part of the current canonical source-spec behavior surface, and `psi.workflow-judge/project-messages` remains the authoritative lower owner for those transcript/message projection semantics
- resulting dependency shape is acceptably tree-like for now: `workflow-step-materialization.source-resolution` depends downward on `workflow-judge`, while `workflow-runtime` depends on `workflow-step-materialization`; no upward dependency into session/public workflow entrypoints remains
- no additional adapter seam broadening was needed

Rewiring summary
- created new component:
  - `components/workflow-step-materialization/`
- created authoritative namespaces:
  - `components/workflow-step-materialization/src/psi/workflow_step_materialization/core.clj`
  - `components/workflow-step-materialization/src/psi/workflow_step_materialization/source_resolution.clj`
- rewired higher and lower consumers to the new owner:
  - `psi.agent-session.context`
  - `psi.agent-session.psi-tool-workflow`
  - `psi.agent-session.test-support`
  - `psi.workflow-runtime.statechart-runtime.step-execution`
  - `psi.workflow-runtime.statechart-runtime.delegate`
  - runtime-adoption and lower proof namespaces
- moved lower proofs into the new component:
  - `components/workflow-step-materialization/test/psi/workflow_step_materialization/core_test.clj`
  - `components/workflow-step-materialization/test/psi/workflow_step_materialization/source_resolution_test.clj`
- updated top-level `deps.edn`, component deps, and `tests.edn` to load the new component and test paths

Role split and execution seam status
- the role split from task `127` remains intact: step session-config stayed in `psi.workflow-step-session-config.core`
- this task did not broaden the workflow execution adapter seam
- `agent-session` remains the higher assembly/orchestration layer only

Transitional namespace status
- `psi.workflow-runtime.step-materialization` removed entirely after rewiring
- `psi.workflow-runtime.source-resolution` removed entirely after rewiring
- no forwarding façades remain
- no workflow-runtime namespace still depends on the old owners; residual dependency debt from old namespace retention is eliminated rather than recorded

Behavior verification
- focused extraction verification:
  - `clojure -M:test --focus psi.workflow-step-materialization.core-test --focus psi.workflow-step-materialization.source-resolution-test --focus psi.workflow-runtime.ir-runtime-adoption-test` → green (`16 tests, 35 assertions, 0 failures`)
- broader workflow/session verification after rewiring runtime and session consumers:
  - `clojure -M:test --focus psi.workflow-runtime.statechart-runtime.step-execution-test --focus psi.workflow-runtime.statechart-runtime.public-test --focus psi.agent-session.workflow-execution-test --focus psi.workflow-step-materialization.core-test --focus psi.workflow-step-materialization.source-resolution-test --focus psi.workflow-runtime.ir-runtime-adoption-test` → green (`24 tests, 79 assertions, 0 failures`)
- lint:
  - `clojure -M:lint --lint components/workflow-step-materialization components/workflow-runtime components/agent-session deps.edn tests.edn` → green (`0 errors, 0 warnings`)

Review note
- terse review: boundary extraction is correct and rewiring is complete, but copied effective-step and output/yield semantics should be reconverged behind one lower shared owner to avoid drift

Follow-up execution — shared semantics reconvergence
- chose the smallest lower shared owner inside the extracted component itself: `psi.workflow-step-materialization.semantics`
- this owner now holds:
  - canonical effective-step lookup
  - canonical output-surface resolution
  - canonical yield-field resolution
  - projection application via `psi.workflow-judge/project-messages`
- this avoided re-expanding `workflow-runtime` ownership while removing the copied semantics from both `psi.workflow-step-materialization.core` and `psi.workflow-step-materialization.source-resolution`
- rewired:
  - `psi.workflow-step-materialization.core` → `semantics/effective-step-def`
  - `psi.workflow-step-materialization.source-resolution` → `semantics/effective-step-def`, `semantics/step-output-value`, `semantics/step-yield-field-value`, and `semantics/project-source-value`
- focused reconvergence verification:
  - `clojure -M:test --focus psi.workflow-step-materialization.core-test --focus psi.workflow-step-materialization.source-resolution-test --focus psi.workflow-runtime.ir-runtime-adoption-test --focus psi.workflow-runtime.statechart-runtime.step-execution-test --focus psi.workflow-runtime.statechart-runtime.public-test --focus psi.agent-session.workflow-execution-test` → green (`24 tests, 79 assertions, 0 failures`)
- lint remained green:
  - `clojure -M:lint --lint components/workflow-step-materialization components/workflow-runtime components/agent-session deps.edn tests.edn` → green (`0 errors, 0 warnings`)

Residual debt
- the review-found duplication inside the extracted component has been eliminated by `psi.workflow-step-materialization.semantics`
- remaining intentional duplication still exists across component boundaries with `psi.workflow-runtime.statechart/effective-steps` and `psi.workflow-runtime.ir` output/yield helpers
- that remaining overlap is now narrower and explicit: the extracted component owns its own lower shared semantics without depending upward on runtime-core owners, preserving the task-130 boundary decision
- aside from that deliberate cross-component overlap in service of boundary direction, there is no remaining forwarding-seam or rewiring debt from this extraction boundary
