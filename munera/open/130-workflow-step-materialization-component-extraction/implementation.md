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
