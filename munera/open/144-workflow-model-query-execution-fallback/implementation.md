Implementation notes:
- task created to add workflow-local fallback across ranked `:model-query` candidates after observing `local-logprobs` fail on a first-ranked local candidate with `Connection refused`
- scope intent: mirror auto-session-name’s ranked-candidate resilience pattern, but keep ownership in workflow execution rather than global model selection or ordinary session execution
- to decide before code changes:
  - the narrowest seam where ranked candidates should be preserved or reintroduced
  - the fallback-worthy execution failure predicate
  - how fallback attempts should appear in workflow attempt/session bookkeeping without obscuring diagnosis
