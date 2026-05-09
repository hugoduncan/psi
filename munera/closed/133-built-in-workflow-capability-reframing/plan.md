Approach:
- treat this as a framing and bootstrap ownership change, not a workflow-runtime redesign
- preserve all extracted lower workflow components as authoritative owners
- audit the current `extensions/workflow-loader/` and `extensions/workflow-display/` surfaces and separate canonical built-in workflow wiring/display ownership from any genuinely optional residue
- prefer new higher core workflow namespaces under nested `workflow.*` families rather than flat `workflow-*` names
- move canonical workflow bootstrap/registration/command/tool/prompt/lifecycle wiring into built-in core assembly at the smallest coherent composition roots, preferring `system-bootstrap` first for built-in installation decisions
- prefer direct built-in wiring over compatibility layers or dual extension/core ownership
- preserve current user-facing workflow behavior while removing extension-packaged framing
- record clearly how workflow becomes a built-in capability in runtime/bootstrap/session assembly without broadening `agent-session` ownership incorrectly

Planned outcomes:
1. review the current `extensions/workflow-loader/` and `extensions/workflow-display/` responsibilities and classify them into built-in canonical behavior vs optional residue
2. identify the right built-in owner(s) for workflow bootstrap/wiring and workflow display/read-model ownership, preferring `system-bootstrap` for built-in installation decisions, `app-runtime` only for process/runtime assembly glue, `agent-session` only for session-scoped orchestration, or an existing lower workflow/core owner if display semantics already belong lower
3. preserve the existing lower workflow component owners and confirm none of their authoritative behavior moves upward in this task
4. move canonical workflow registration surfaces out of extension packaging and into built-in core assembly
5. rehome canonical workflow tool/command/prompt/lifecycle registration so workflow no longer depends on extension framing
6. decide whether canonical workflow display/read-model helpers should move into built-in core ownership or remain outside as truly optional residue; use the rule that helpers projecting canonical workflow state into stable built-in display/read-model forms belong in core
7. ensure capability-catalog, session-capability, and extension-install modeling no longer treat canonical workflow behavior as extension-originated, or explicitly record the residual exception
8. decide whether any workflow-adjacent extension surface remains outside core; if so, record why it satisfies the task's optionality rubric and remains genuinely optional and non-canonical
9. delete `extensions/workflow-loader/` if direct rewiring is possible; otherwise leave only a tiny explicitly transitional façade with a recorded blocking reason
10. move `extensions/workflow-display` into built-in core ownership when it matches the canonical display/read-model rubric, or explicitly record why it remains outside core under the optionality rubric
11. verify that canonical workflow behavior remains unchanged from a user perspective, preserving delegate/reload/load semantics while allowing incidental notification wording or namespace/test placement changes
12. record the reviewed responsibilities, chosen built-in owners, whether the implementation followed the preferred `system-bootstrap`-first composition rule, preserved lower boundaries, capability-model decision, display-ownership decision, and any remaining optional residue in `implementation.md`

Scope boundaries:
- no merging of extracted workflow components back into `agent-session`
- no redesign of workflow runtime, registry, loader, judge, step-materialization, or step-session-config semantics
- no redesign of workflow authoring/file format semantics
- no generic extension-runtime redesign beyond what this workflow reframing requires
- no broad workflow cleanup beyond the extension-to-core framing move
