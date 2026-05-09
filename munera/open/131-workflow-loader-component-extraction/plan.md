Approach:
- treat this as a concept-first extraction of workflow authored-definition loading ownership, not a generic workflow cleanup
- choose `workflow-loader` / `psi.workflow-loader.*` unless implementation proves a narrower or broader owner is more accurate
- treat the extracted surface as one coherent authored-definition loading owner with a small internal split for discovery, parsing, compilation, and authoring-preparation roles; any narrower extraction requires explicit written justification in `implementation.md`
- keep loader ownership distinct from workflow registry ownership, workflow runtime ownership, and step-derivation ownership
- move workflow-file authoring compilation helpers with the loader when they are load-time preparation logic rather than higher orchestration
- preserve current workflow loading behavior first, then record any residual mixed load/register/run edges explicitly rather than broadening the task
- keep `agent-session` and tool-facing entrypoints as higher orchestration surfaces that depend downward on the extracted loader component
- prefer a tree-like dependency shape and remove temporary forwarding seams before completion unless blocked

Planned outcomes:
1. review current workflow loading/discovery/authored-definition preparation surfaces and identify the true loader ownership boundary
2. choose the narrowest accurate component/namespace name for the extracted loader surface
3. decide whether one namespace or a small internal split best fits the extracted component, with the default expectation being one coherent authored-definition loading owner; if implementation instead lands on a smaller combined discovery + ingestion boundary, record explicit justification in `implementation.md` for why that is still the best current extraction
4. create the dedicated lower component and move the authoritative workflow loading/authored-definition preparation logic with minimal semantic change
5. preserve existing caller-visible loader behavior surfaces and their externally consumed call/output contracts unless a justified replacement is recorded, or record the new canonical lower loader entrypoints if the extraction introduces them
6. prefer one small canonical lower loader API where possible, centered on `load-workflow-definitions`, and classify remaining public vars as architecturally necessary versus temporarily preserved for caller safety
7. rewire higher workflow entrypoints, adapter surfaces, and affected tests to the new owner
8. decide whether previous mixed workflow-loading owners disappear entirely or remain only as tiny temporary forwarding seams, with justification if retained; unless a blocking reason is recorded, remove such seams before the task is considered complete
9. verify workflow behavior remains unchanged, loader ownership remains distinct from registry/runtime/step-derivation ownership, and any remaining mixed load-register-run boundary awkwardness is explicitly recorded as residual debt, including that the preferred downstream handoff artifact is canonical prepared workflow definitions plus loader diagnostics and whether the resulting dependency shape is acceptably tree-like or preserves graph edges for later cleanup
10. record the reviewed current surfaces, final boundary, loader responsibility shape, public surface, naming choice, responsibility inventory, registry/runtime boundary status including the downstream handoff artifact, transitional namespace status, and any residual dependency debt in `implementation.md`

Scope boundaries:
- no workflow runtime redesign
- no workflow registry redesign
- no workflow authoring-semantics redesign
- no public workflow API redesign
- no generic file-loader framework work outside workflow loading
- no recombining loader ownership with step session-config or step materialization
