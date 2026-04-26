# 057 — Steps

- [x] Slice 1: FIFO event pump proof + canonical `.acting` / `.blocked` / optional `.judging` chart compiler skeleton
- [x] Slice 2: Workflow context, attempt identity rules, authority rules, and actions-fn dispatch model
- [x] Slice 3: Linear-step execution with explicit success/failure/blocked recording ownership
- [x] Slice 4: Judged compound-step execution with same-attempt judging and `:judge/signal` routing
- [x] Slice 5: Guard purity and routing/retry rules from working-memory snapshots only
- [x] Slice 6: Phase B helper decomposition so recording survives but chart owns control flow
- [x] Slice 7: Cancel, terminal projection, and FIFO queue semantics
- [x] Slice 8: Existing test migration, imperative loop removal, and isolated workflow suite green

Follow-up cleanup from task review:
- [x] Decide and implement the canonical future of `workflow_statechart/compile-definition` (hierarchical default vs explicit compatibility surface)
- [x] Remove or quarantine residual `next-step-id-fn` compatibility usage from non-Phase-A paths
- [x] Extract shared workflow step-preparation helpers from `workflow_execution.clj` and `workflow_statechart_runtime.clj`
- [x] Remove or explicitly implement no-op statechart action hooks like `:step/exit` and `:judge/exit`
- [x] Align action naming/documentation drift between design and implementation (for example `:terminal/record` vs `:terminal/enter`)

Optional post-closure polish:
- [x] Split `workflow_progression.clj` into compatibility-era control helpers vs Phase A record-only helpers if ongoing workflow work makes the mixed surface burdensome
- [x] Consider separating compatibility compiler concerns from canonical hierarchical execution compiler concerns in `workflow_statechart.clj` if Phase B surfaces continue to shrink
- [x] Add a concise canonical-surfaces note across workflow namespaces documenting which execution/compiler/runtime surfaces are authoritative now
