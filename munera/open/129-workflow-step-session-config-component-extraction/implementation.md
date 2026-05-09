2026-05-07

Task created from post-123/124/125/126/127/128 workflow boundary review.

Creation rationale:
- the main workflow-runtime extraction is now complete enough that the remaining question is conceptual fit, not whether workflow code can move below public entrypoints at all
- `psi.workflow-runtime.step-session-config` is coherent lower-owned code, but its role is workflow child-session configuration policy rather than workflow runtime execution semantics
- `127` already split session-config shaping from step materialization, which makes this extraction a natural next boundary cleanup
- the intent is to give workflow child-session config policy a more precise lower component home without moving it back upward into `agent-session`

Initial boundary notes:
- likely owned responsibilities: parent-session selection/fallback, tool/skill/model inheritance, workflow meta merge rules, and child-session developer-prompt/config derivation
- expected dependency crossing to preserve: `psi.workflow-runtime.execution-adapter`
- expected non-goal: do not bundle `step-materialization` into this extraction unless implementation proves the design is wrong
