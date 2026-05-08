2026-05-07

Task created to extract canonical workflow-definition registration semantics into a lower component.

Creation rationale:
- workflow-related ownership is currently split across file loading, compilation, definition registration, runtime execution, and extension-local workflow-loader behavior
- the clearest missing lower seam is canonical workflow-definition registration/removal/query ownership
- `workflow-loader` is currently a good orchestrator for file-backed reloads, but it should delegate registry semantics rather than remain the de facto definition-registry owner
- recent registration extractions suggest a clearer split where lower components own registration/query semantics and higher layers keep orchestration and side effects

Initial boundary hypothesis:
- new lower owner: `workflow-registry` for canonical workflow-definition registration semantics over root state
- higher owners retained: `workflow-loader` for discovery/loading/reload and prompt contribution refresh; workflow runtime for create-run/execute-run/resume-run/cancel-run/progression; mutation/tool/resolver seams as higher-level adapters
- first cut should preserve canonical definition maps as provided and avoid broad reshaping or compiler ownership changes

Live source audit notes captured before implementation:
- current authoritative lower definition helpers live in `workflow_runtime.clj`, not in a separate workflow mutation helper namespace
- registration validates `workflow-target-ir-compiler/target-authored-workflow-definition?` and throws on invalid definitions
- `normalize-id` is part of the live contract: blank/missing ids generate UUID strings; keywords normalize via `name`; other values normalize via `str`
- registering an existing normalized `:definition-id` replaces the stored definition at that map entry
- direct lookup helper is nil-returning on miss
- lower remove helper throws on missing definitions; higher mutation layers translate that into `:removed? false` with an error string
- current public listing surfaces in workflow mutations, workflow resolvers, and `psi_tool_workflow.clj` all sort by `:definition-id`

Resolved design clarifications after review:
- first cut should preserve the current tuple-shaped pure lower API as the authoritative component programming surface
- public registry helpers that accept definition ids should normalize incoming ids before lookup/removal so the caller-facing id contract is consistent with registration/removal semantics
- canonical public listing/query helpers should live in the extracted component and preserve sorted-by-`definition-id` behavior for current public consumers
- replacement semantics are full-map replacement at the normalized id key, not field-wise merge
- first cut explicitly allows the extracted component to retain dependency on `workflow-target-ir-compiler/target-authored-workflow-definition?` for validation

Resolved final design clarifications after second review:
- `workflow-registry` should own workflow-definition-specific path helpers such as `definitions-path` and `definition-path`
- the minimal first-cut public query surface should be explicit and authoritative: `register-definition`, `remove-definition`, `workflow-definition`, `list-definitions`, and `definition-ids`
- resolver detail lookup should delegate to the public normalized registry lookup helper
- registry owns canonical storage/query behavior and ordering; mutations, resolvers, and `psi-tool` own only surface-specific projection/formatting
- acceptance should be read narrowly as preserving definition registration/removal/lookup/listing semantics and downstream run-creation lookup behavior, not every incidental message string

Remaining implementation choices:
- confirm whether a storage-facing exact-key helper such as `workflow-definition-in` remains worth keeping as an internal-only helper once public normalized lookup is extracted
- confirm the smallest useful count/helper surface beyond the core query API so the component stays authoritative without growing speculative convenience functions
Relationship to umbrella work:
- this should become a concrete workflow child extraction under `105-agent-session-component-extraction-map`
- it is related to but distinct from `077-deterministic-workflow-steps`, which is about workflow authoring/runtime semantics rather than the narrower definition-registry boundary
- it is structurally closer to `111-tool-registration-component-extraction` and `113-command-registration-component-extraction` than to the pure collection extractions in `112` and `114`