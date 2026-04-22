Approach:
- start by mapping current symbol ownership and actual cross-file dependencies in the TUI app split files
- choose the smallest explicit namespace decomposition that removes `in-ns` and avoids cycles
- migrate one surface at a time: shared state/helpers, autocomplete, support/init, update, render, then thin facade composition
- keep the public entry surface stable while moving implementation details behind explicit requires
- run targeted lint and TUI tests after each structural slice

Initial execution outline:
1. inventory current symbols by file and dependency direction
2. introduce explicit `ns` forms for extracted files in their chosen target namespaces
3. move shared helpers/constants to the owning namespace(s) that minimize back-edges
4. rewrite `app.clj` to compose explicit namespaces instead of `load`
5. update tests/call sites
6. verify with targeted lint/tests, then `bb lint`

Risks:
- introducing namespace cycles while splitting update/render/support logic
- accidentally widening public API surface by moving too many helpers to public vars
- subtle TUI behavior drift if helper ownership changes without good regression coverage

Verification:
- targeted `clj-kondo` over TUI namespaces
- relevant TUI tests
- full `bb lint`
