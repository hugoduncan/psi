Implementation notes:
- task created to add workflow-local fallback across ranked `:model-query` candidates after observing `local-logprobs` fail on a first-ranked local candidate with `Connection refused`
- scope intent: mirror auto-session-name’s ranked-candidate resilience pattern, but keep ownership in workflow execution rather than global model selection or ordinary session execution
- to decide before code changes:
  - the narrowest seam where ranked candidates should be preserved or reintroduced
  - the fallback-worthy execution failure predicate
  - how fallback attempts should appear in workflow attempt/session bookkeeping without obscuring diagnosis
- 2026-05-11 ambiguity review:
  - design/plan leave the authoritative ranked-sequence carrier ambiguous: current `resolve-step-session-config` collapses `:model-query` to one concrete `:model`, but the task text still allows either carrying ranked metadata in session-config or re-resolving at execution; choose one authoritative shape so implementation and proof do not drift.
  - design/acceptance do not yet define the public failure contract for ranked-candidate exhaustion: specify where aggregate candidate failures live and how the terminal error appears in workflow attempt/result bookkeeping so fallback diagnostics are testable rather than implicit.
