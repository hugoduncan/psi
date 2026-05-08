Approach:
- treat this as a narrow extraction of canonical workflow-definition registration semantics, not a workflow authoring or run-execution redesign
- make the first cut a registry-style component over root workflow-definition state
- preserve current workflow-definition behavior while making the registry contract explicit through focused tests

Planned sequence:
1. inspect the current workflow mutation, runtime, resolver, and psi-tool definition surfaces to verify the documented register/remove/list/get semantics to preserve
2. implement the settled first-cut `workflow-registry` API around the confirmed live contract, using the public surface `register-definition`, `remove-definition`, `workflow-definition`, `list-definitions`, and `definition-ids`, plus tuple-shaped lower result contracts, `normalize-id`, target-authored definition validation, and sorted listing/query helpers where public surfaces already rely on sorted order
3. create `components/workflow-registry/` and add focused registry tests first
4. move or re-express workflow-definition registration/removal/query helpers into the extracted component, including authoritative ownership of workflow-definition-specific path helpers such as `definitions-path` and `definition-path`, and make identity validation, replacement behavior, miss behavior, ordering, and lower tuple-shaped result contracts explicit at the registry boundary
5. delegate workflow mutation entrypoints downward into the extracted component, including workflow definition listing mutations in addition to register/remove mutations
6. delegate workflow resolver read paths downward into the extracted component, including detail lookup through the public normalized registry lookup helper
7. update `psi-tool` definition-listing/lookup paths to consume the extracted registry owner or a very thin delegating seam
8. keep `workflow-loader` as the orchestrator for file-backed load/reload/retirement while delegating canonical registration/removal downward
9. keep workflow-run creation/execution/resume/cancel/progression ownership unchanged outside the new component, but rewire registered-definition lookup used by run creation to the extracted registry
10. run focused verification for the new component and affected higher-level workflow definition behavior, including replacement/removal/listing semantics, resolver root/detail behavior, loader retirement behavior, and run-creation lookup behavior
11. record final boundary decisions, ordering behavior, and any non-obvious tradeoffs in `implementation.md`

Design constraints:
- do not absorb workflow-file discovery/loading into the new component
- do not absorb workflow-file parsing/compilation or task `077` authoring semantics into the new component
- do not absorb workflow-run execution/progression into the new component
- keep mutation/tool/resolver surfaces as thin higher-level adapters unless a tiny delegation cleanup falls out naturally
- prefer one obvious lower owner over anticipatory workflow abstraction

Verification intent:
- new component tests should prove definition register/remove/get/list/id-query semantics directly
- higher-level verification should cover mutation, resolver, `psi-tool`, and loader consumer paths where the ownership shift is now explicit
- run-creation lookup behavior should remain verified where registered-definition resolution crosses from workflow runtime into the extracted registry
- implementation must explicitly verify and record definition identity, replacement semantics, remove-miss behavior, lookup-miss behavior, ordering behavior, and lower-vs-higher result-contract distinctions rather than leaving them implicit