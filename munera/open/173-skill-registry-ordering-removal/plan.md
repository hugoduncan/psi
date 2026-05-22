# Plan

1. Audit `skill-registry` callers and tests for real dependence on registration order versus mere deterministic listing.
2. If no insertion-order dependency is found, narrow the contract to exact-name lookup + duplicate-ignore + `:added?` / `:changed?` + canonical deterministic listing.
3. Implement the smallest coherent change at the registry read surface, preferably by making `all-skills` / `skill-names` canonical by `:name` so prompt/discovery callers inherit the same deterministic order automatically.
4. Add focused tests proving duplicate-ignore remains intact while visible listing is canonical rather than insertion-ordered.
5. Update task `164` to reflect the refined conclusion.
