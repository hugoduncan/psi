Approach:
- treat this as a focused architectural extraction under the existing prompt-lifecycle umbrella, not as a prompt-behavior redesign
- extract a distinct turn component with a distinctive name (`psi.turn`) so ownership is not blurred by the overloaded word `prompt`
- first map the current prepare → execute → record → finish ownership across namespaces and identify the smallest coherent turn boundary that can become authoritative
- then move orchestration behind that boundary, simplify callers so they delegate to it, and keep any proof changes tightly limited to what the extraction requires
- keep `prompt-control` as the temporary public compatibility facade while moving implementation ownership behind it into `psi.turn`

Likely steps:
1. inspect current turn lifecycle code paths, responsibilities, tests, and dependency edges
2. establish the dependency slope explicitly: shared leaves below `psi.turn`; `core` / `context` / workflows / mutations / adapters above it
3. introduce the authoritative `psi.turn` public owner while keeping `prompt-control` as a thin delegating facade
4. record the chosen ownership split, naming rationale, and dependency boundary explicitly in implementation notes before or during extraction
5. move or extract request preparation, execution, and record orchestration into the `psi.turn` family with minimal semantic drift
6. rebind `context` callback wiring to turn-owned functions
7. thin turn handler ownership so `dispatch-handlers.prompt-lifecycle` is registration/adaptation only
8. simplify the key caller seams (`prompt-control` and `context`) to use the component rather than partial local orchestration
9. update focused documentation/comments for the new ownership model
10. make only the minimal focused test updates needed for extraction safety, covering at least the canonical submit/start → prepare → execute → record → continue/finish flow after extraction
11. run focused verification and widen if needed

Risks:
- extracting too much at once and accidentally coupling unrelated prompt helpers into the component
- preserving old compatibility seams under a new namespace rather than truly centralizing ownership
- choosing a component name but leaving prompt-oriented ownership/documentation patterns in place so the rename is cosmetic rather than architectural
- creating upward dependencies from `psi.turn` into `core`, `context`, workflow runtimes, or mutation namespaces
- leaving `context` callback wiring or handler ownership half-migrated so split ownership persists
- accidental scope growth through unnecessary test rewrites
- accidental overlap/conflict with task `006` if cache-breakpoint shaping is touched beyond the interface needed for extraction

Notes:
- prefer one obvious component entrypoint over multiple thin wrapper shims
- maintain a one-way dependency slope: shared helpers below `psi.turn`, façades/orchestrators/adapters above it
- keep `prompt-control` as the migration seam until callers and callback wiring are safely redirected
- use the component name `turn` consistently in new ownership docs so the boundary is described in the same vocabulary as the namespace
- treat `context` callback rewiring as a required migration milestone, not an optional cleanup step
- do not expand this task into test-architecture cleanup; preserve existing test structure unless a narrow extraction-driven change is necessary
