2026-05-07

Task created from workflow component-extraction review.

Creation rationale:
- the workflow runtime cluster is now the strongest remaining cohesive below-dispatch extraction candidate in `agent-session`
- current runtime ownership looks like historical placement rather than true session-core ownership
- extracting the runtime core should materially reduce workflow dependence on `agent-session` while preserving `agent-session` as the higher session orchestration layer
- the extraction should consume lower seams for judge/routing, step execution, and deterministic operation runtime rather than re-owning them

Initial boundary notes:
- authoritative extracted namespace family is expected to live under `psi.workflow-runtime.*`
- runtime ownership should include execution/progression/statechart coordination and attempt/runtime context behavior
- mutations, resolvers, and `psi-tool` stay above the boundary
- this task should remain tightly focused on below-dispatch workflow runtime ownership rather than broad workflow API redesign
- do not leave compatibility shims unless implementation proves a very small temporary seam is necessary
