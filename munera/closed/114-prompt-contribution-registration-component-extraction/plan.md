Approach:
- treat this as a narrow extraction of prompt-contribution registration semantics, not a broad prompt-assets or turn redesign
- make the first cut a pure component over vectors of registered prompt contribution maps rather than a new long-lived runtime registry
- preserve current prompt contribution behavior exactly while making the registry contract explicit through focused tests

Planned sequence:
1. inspect the current prompt contribution handlers and verify the documented create/update/remove semantics to preserve
2. define the smallest useful `prompt-registry` API for contribution normalization, patching, register/update/unregister, pure collection queries, and explicit result contracts
3. create `components/prompt-registry/` and add focused contribution-registry tests first
4. move or re-express normalization and patch/merge helpers into the extracted component, making identity-field validation, invalid-identity behavior, canonical stored shape, patchability rules, and timestamp semantics explicit at the registry boundary
5. delegate prompt contribution collection semantics from `agent-session` prompt handlers to the extracted component
6. keep effective-prompt rebuilding and runtime prompt-update effects in `agent-session`
7. keep mutation/read-path surfaces stable unless trivial delegation sharpens ownership naturally, and make the registry-vs-prompt-composition ordering boundary explicit
8. run focused verification for the new component and affected higher-level prompt contribution behavior, including miss behavior, result/reporting contracts, and timestamp semantics
9. record final boundary decisions, count/reporting behavior, and any non-obvious tradeoffs in `implementation.md`

Design constraints:
- do not absorb system-prompt assembly into the new component
- do not broaden into prompt template registration in this first cut
- do not move prompt lifecycle or turn orchestration into the new component
- keep the component API expressed over already-stored contribution vectors rather than introducing a stateful runtime registry object
- prefer a small obvious pure API over anticipatory abstraction

Verification intent:
- new component tests should prove normalization, register/update/unregister behavior, identity semantics, and result reporting directly
- existing higher-level tests should continue to prove system-prompt refresh/orchestration behavior
- implementation must explicitly preserve the current identity rule of contribution ownership by `ext-path` + `id`
