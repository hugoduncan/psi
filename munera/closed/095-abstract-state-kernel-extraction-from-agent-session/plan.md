Approach:
- treat this as a boundary-extraction task, not a redesign of dispatch semantics
- first classify candidate namespaces and namespace parts into: generic kernel, domain-specific state, domain behavior, and orchestration
- establish the narrowed kernel environment contract before moving code so the extraction is not just a file move with the old `ctx` leakage preserved
- extract only the smallest coherent application-independent substrate needed to establish a true lower component boundary
- prefer splitting mixed namespaces over over-extracting domain behavior into the kernel
- keep `agent-session` as the first consumer of the kernel while ensuring the kernel does not depend back on `agent-session`
- keep query/bootstrap/global registration concerns above the kernel throughout

Likely steps:
1. inspect `dispatch.clj`, `dispatch_schema.clj`, `dispatch_effects.clj`, `context.clj`, `agent-session.bootstrap`, and `system-bootstrap.core` in detail and record what is generic vs domain-specific
2. record the settled boundary decisions explicitly in task notes: component path `components/state-kernel/`, narrowed environment-map contract, pure-result contract ownership, kernel-owned apply path, kernel-owned bounded event-log/trace substrate, query/bootstrap exclusion, and the chosen strategy of removing `agent-session -> system-bootstrap`
3. validate the settled kernel environment contract against current dispatch/effect code and record any required refinements only when they are demonstrably domain-independent
4. create `components/state-kernel/` with deps and namespace family for the kernel
5. move the generic dispatch pipeline, schema/contract, apply path, and bounded event-log/trace substrate into the new component
6. split `dispatch_effects.clj` into generic effect-execution substrate vs application-specific effect methods only if the substrate can be expressed without app effect assumptions; otherwise defer that split explicitly
7. split any generic listener/publication helpers from `context.clj` only if needed to keep generic dispatch code out of `agent-session/context.clj`; otherwise defer that move explicitly
8. update `agent-session` and any other direct consumers to require the new kernel component/namespaces through the narrowed environment contract
9. verify that the new component has no dependency back into `agent-session`
10. remove `agent-session -> system-bootstrap` by lifting global registration ownership to higher-level composition/bootstrap entrypoints if that can be done cleanly; otherwise document the exact remaining blocker and why the cycle persists
11. add or adjust focused tests proving the extracted kernel boundary and behavior
12. document the resulting ownership split, environment contract, optional deferrals, and cycle status in task notes
13. run focused verification, then widen only as needed

Implementation posture:
- start with the clearest whole-file moves: dispatch pipeline + schema
- treat the narrowed environment-map contract as fixed input to the implementation, not an optional cleanup after file moves
- extract the generic effect executor substrate only if it can be separated cleanly from app-specific effect methods in the same slice; otherwise leave effect execution above the kernel and document that deferral explicitly
- extract generic listener/publication helpers only if they are needed to keep generic dispatch code from depending on `agent-session/context.clj`; otherwise leave them above the kernel and document that deferral explicitly
- avoid extracting vague “utilities”; every moved thing should have a clear kernel rationale
- prefer preserving namespace APIs where practical so caller churn stays bounded
- treat the `agent-session.bootstrap` / `system-bootstrap` split as an architectural checkpoint rather than an unrelated side concern
- do not pull query/bootstrap/global registration into the kernel to solve the cycle; solve that ownership separately above the kernel boundary

Risks:
- over-extracting domain-specific effect handlers into a nominally generic kernel
- under-extracting and ending with a kernel that is too thin to be architecturally meaningful
- moving files without actually improving dependency direction
- letting this task balloon into `session-state` or `turn` extraction
- accidentally introducing a new component cycle while trying to resolve a lower-level coupling problem
- splitting mixed files in a way that obscures ownership rather than clarifying it

Decision criteria during implementation:
- if a function can be reused unchanged in a non-session domain, it is a kernel candidate
- if a function mentions session/prompt/workflow/tool/extension semantics, it stays out of the kernel
- if a namespace is mixed, split it rather than compromising the boundary
- if a candidate extraction still requires `agent-session` imports, it is not yet a valid kernel move
- if generic code still needs arbitrary access to the broad `ctx` map instead of only `:state*`, `:execute-effect-fn`, `:validate-result-fn`, `:publish-change-fn`, and optional `:dispatch-trace-fn`, the kernel contract is not yet sharp enough
- if a proposed new environment key is not demonstrably domain-independent, it must not be added to the kernel contract
- if query-context creation or resolver/mutation registration is drifting into the kernel, the boundary is being violated
- if the effect execution substrate still encodes application effect assumptions, defer that extraction instead of weakening the kernel boundary
- if listener/publication helpers can remain above the kernel without causing generic dispatch code to depend on `context.clj`, leave them there in this slice
- if the remaining `agent-session` ↔ `system-bootstrap` edge exists, its precise cause must be nameable in one sentence after the slice
