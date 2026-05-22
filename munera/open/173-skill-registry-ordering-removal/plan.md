# Plan

1. Audit `skill-registry` callers and tests for real dependence on registration order versus mere deterministic listing.
2. Decide the branch from audit evidence:
   - Removal branch: if no insertion-order dependency is found, narrow the contract to exact-name lookup + duplicate-ignore + `:added?` / `:changed?` + canonical deterministic listing.
   - Keep-order branch: if a real insertion-order dependency is found, do not make an ordering-removal code change; document the dependency in `implementation.md`, preserve or add a focused test proving the required order, and update task `164` only to record the confirmed requirement.
3. For the removal branch, implement the smallest coherent change at the registry boundary/read surface: `all-skills`, `skill-names`, and `register-skill` result `:skills` should be canonical by exact `:name` string ordering, and prompt/display/introspection projections that consume raw session `:skills` should canonicalize before exposing ordered output.
4. Add focused tests proving duplicate-ignore remains intact while visible listing is canonical rather than insertion-ordered.
5. Update task `164` to reflect the selected branch and refined conclusion.
