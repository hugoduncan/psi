Approach:
- treat this as a focused architectural extraction under the existing prompt-lifecycle umbrella, not as a prompt-behavior redesign
- first map the current prepare → execute → record → finish ownership across namespaces and identify the smallest coherent component boundary that can become authoritative
- then move orchestration behind that boundary, simplify callers so they delegate to it, and keep any proof changes tightly limited to what the extraction requires

Likely steps:
1. inspect current prompt lifecycle code paths, responsibilities, and tests
2. choose the dedicated component boundary and public API
3. record the chosen ownership split explicitly in implementation notes before or during extraction
4. move or extract lifecycle orchestration into the new component with minimal semantic drift
5. simplify surrounding callers to use the component rather than partial local orchestration
6. update focused documentation/comments for the new ownership model
7. make only the minimal focused test updates needed for extraction safety
8. run focused verification and widen if needed

Risks:
- extracting too much at once and accidentally coupling unrelated prompt helpers into the component
- preserving old compatibility seams under a new namespace rather than truly centralizing ownership
- accidental scope growth through unnecessary test rewrites
- accidental overlap/conflict with task `006` if cache-breakpoint shaping is touched beyond the interface needed for extraction

Notes:
- prefer one obvious component entrypoint over multiple thin wrapper shims
- do not expand this task into test-architecture cleanup; preserve existing test structure unless a narrow extraction-driven change is necessary
