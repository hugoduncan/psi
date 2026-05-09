Approach:
- treat this as a workflow-specific review-and-cleanup pass after successful component extraction, not as a semantic workflow redesign
- review higher `agent-session` workflow surfaces first, because the lower component boundaries now appear largely coherent
- use the design rubrics to classify each reviewed surface consistently as keep, reshape, rename, merge, extract, or delete
- follow the design’s priority order unless review discovers a concrete dependency or sequencing reason to reorder it:
  1. `psi.agent-session.psi-tool-workflow`
  2. naming of extension workflow runtime surfaces
  3. workflow-specific assembly embedded in `psi.agent-session.context`
  4. duplicated workflow summary/projection logic across entrypoints
  5. workflow-related test ownership duplication
- prioritize removal or isolation of migration-era compatibility scaffolding before making finer projection, naming, or test-ownership decisions
- preserve workflow behavior and explicit higher-surface contracts while improving ownership, naming, and proof placement
- prefer direct rewiring and deletion of temporary workflow shims over retaining forwarding seams unless a concrete supported lifecycle still requires them
- implement only the smallest coherent cleanup slices whose direction is clear after review; if several independent slices emerge, record them and split follow-on tasks rather than broadening this task indiscriminately

Planned outcomes:
1. review the current workflow cleanup targets and classify each as keep, reshape, rename, merge, extract, or delete
2. decide the fate of workflow compatibility backfill currently embedded in `psi.agent-session.psi-tool-workflow` using the explicit compatibility rubric
3. decide whether workflow-specific runtime assembly should remain embedded in `psi.agent-session.context` or move to a dedicated workflow assembly owner
4. decide whether `psi.agent-session.workflow-execution` remains a justified session-facing API or should be collapsed/reframed using the façade rubric
5. decide whether `psi.agent-session.workflow-judge` remains a coherent higher impure orchestration owner or whether it needs a more specific cleanup
6. identify duplicated workflow projection/report shaping, distinguish contract-specific duplication from accidental duplication, and choose an explicit owner for any shared extracted logic
7. decide whether extension workflow runtime names under `agent-session` should be renamed for clarity against canonical deterministic workflow runtime surfaces
8. classify workflow-related tests into lower proof, higher integration proof, or historical duplication by comparing them against current lower proof ownership
9. choose the highest-value small cleanup slices whose direction is clear, record why those slices were selected over other reviewed items, and either implement them or explicitly defer broader slices with recorded reasons and follow-on tasks
10. record the final cleanup decisions, kept boundaries, renamed or removed seams, preserved contract surfaces, and any intentional residual debt in `implementation.md`

Scope boundaries:
- no redesign of workflow runtime semantics
- no redesign of workflow authoring semantics
- no recombining extracted lower workflow components
- no unrelated `agent-session` cleanup outside workflow-related surfaces
- no silent public behavior changes while performing structural cleanup