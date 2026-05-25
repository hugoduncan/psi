# Plan

1. Re-read tasks `164`–`177` outcomes plus the current session-state/lifecycle/workflow shaping seams to inventory the remaining registry/session asymmetries from highest architectural leverage downward.
2. Inventory each in-scope domain in this order because later domains depend on earlier classifications:
   - `skill-registry`
   - `prompt-registry`
   - `tool-registry`
   - `workflow-registry`
   - `deterministic-operation-registry`
3. For each domain, classify the current and intended model using one primary bucket:
   - membership-id in session
   - derived effective payload in session
   - runtime-adapter owned surface
   - out of scope / do not force into the pattern
4. Use the current code seams to record, per domain:
   - canonical definition owner
   - authoritative session field, if any
   - derived execution payloads and where they are computed
   - compatibility-only projections that must remain or should later be removed
   - lifecycle / child-session / workflow narrowing rules
5. Resolve the tool question explicitly: decide whether `:tool-defs` remains the authoritative session surface as a principled derived-payload exception, or whether future work should migrate toward session membership ids with `:tool-defs` demoted to derived compatibility/execution payload.
6. Update `design.md` so the desired invariant is concrete for each domain instead of only umbrella guidance, and so bootstrap/new/resume/fork/child-session shaping rules are stated in terms of canonical authority vs derived projections.
7. Decompose implementation into follow-on task guidance only; this task should refine architecture and identify slices rather than implement the follow-ons directly.
8. This task should leave architectural guidance and recommended follow-on slices, not create/refine separate task directories in the same pass unless a later user request explicitly asks for that task-creation work.
