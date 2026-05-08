Approach:
- treat this as a narrow component extraction for registry-style skill ownership, not as a broad prompt-assets redesign
- make the first cut a pure component over vectors of registered skill maps rather than a new stateful runtime registry
- preserve current session behavior exactly: add-by-name only when absent, ignore duplicates, preserve first-registration order, and refresh the system prompt only when the session skill set changes

Planned sequence:
1. inspect the current skill registration path in `agent-session` and confirm the exact existing semantics
2. define the smallest useful `skill-registry` API for pure registered-skill collection operations
3. create the component and add focused registry tests first
4. implement minimal registration validation (`:name` present and non-blank string only, without importing prompt-assets discovery validation), register/list/get/name/count helpers, and explicit `:added?`/`:changed?` result reporting
5. delegate `:session/register-skill` to the extracted registry logic
6. keep prompt-assets and orchestration responsibilities explicit and unchanged
7. only clean up read-path helpers where the ownership shift is obvious and low-risk
8. run focused verification for the new component and affected `agent-session` behavior
9. update umbrella/task notes if the final boundary is sharper than currently described

Design constraints:
- do not move `SKILL.md` parsing, discovery, validation, prompt enrichment, or invocation expansion into the registry component
- do not introduce a long-lived stateful registry object in the first cut
- do not change the external session skill data contract unless there is a compelling simplification with low migration cost
- keep the component API expressed in terms of already-constructed registered skill maps, not filesystem paths
- prefer a small obvious API over anticipatory abstraction

Verification intent:
- new component tests should prove registration, duplicate handling, ordering, lookup, and minimal validation semantics
- existing `agent-session` tests should continue to prove orchestration-level behavior such as prompt refresh after added skills
- prompt/discovery behavior should remain proven through existing lower/higher tests rather than being rehomed into the new component
