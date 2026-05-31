Goal: continue shaping canonical, queryable dispatch observability around the dispatch pipeline.

Context:
- bounded dispatch trace storage and EQL query surfaces already exist
- dispatch-id threading is explicit and stable
- current trace coverage spans dispatch receipt, interceptors, handler/effect stages, managed-service activity, and completion/failure
- open questions remain around LSP findings as events, broader trace coverage, and service lifecycle trace entries

Acceptance:
- dispatch trace remains the canonical end-to-end query surface for dispatch flows
- any new trace coverage stays bounded and coherent by `dispatch-id`
- the surface reduces reliance on ad hoc debug instrumentation
