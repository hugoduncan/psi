Approach:
- treat this as a concept-first extraction, not another generic workflow-runtime split
- preserve the current role split from `127`: step materialization remains distinct from step session-config
- move only workflow step input/session-conversation materialization ownership into a dedicated lower component
- preserve current source-resolution and projection semantics first, then record any further cleanup opportunity explicitly
- keep `agent-session` as the higher assembly/orchestration layer and avoid reclassifying this logic upward

Planned outcomes:
1. confirm the exact current responsibility inventory in `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution`, including source binding resolution, source-spec application, template rendering, step input materialization, child-session conversation materialization, prompt/preload splitting, and prompt derivation
2. choose the narrowest accurate component/namespace name for the extracted ownership surface
3. decide whether one namespace or a small internal split best fits the extracted component and whether `source-resolution` is intrinsic to that component or co-extracted as the smallest clean current boundary
4. create the dedicated lower component and move the authoritative materialization/source-resolution logic with minimal semantic change
5. preserve the preserved public behavior surfaces and preserve their externally consumed call and output contracts unless a justified replacement is recorded
6. classify preserved public vars as canonical long-term behavior surfaces versus currently consumed surfaces kept stable for extraction safety
7. rewire workflow-runtime, `agent-session.context`, `psi_tool_workflow`, and affected tests to the new owner
8. decide whether `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution` disappear entirely or remain only as tiny temporary forwarding seams, with justification if retained; unless a blocking reason is recorded, remove such seams before the task is considered complete
9. verify behavior remains unchanged, the role split with step session-config remains intact, no accidental runtime/adapter broadening occurred, and any remaining dependency on `psi.workflow-judge` is explicitly classified as legitimate shared lower workflow semantics or residual debt, including whether the resulting dependency shape remains acceptably tree-like or preserves a graph edge that should be revisited later
10. record the final boundary, public surface classification, naming choice, source-resolution ownership status, dependency/input shape, source-resolution dependency status, responsibility inventory, transitional namespace status, and any residual dependency debt in `implementation.md`

Scope boundaries:
- no workflow behavior redesign
- no source-spec or template-semantics redesign
- no execution-adapter redesign unless a compelling reason is recorded
- no public workflow API changes
- no step-session-config extraction or recombination in this task
- no generic templating or generic source-resolution framework work
