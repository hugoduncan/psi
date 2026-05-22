# Plan

1. Audit `skill-registry` callers and tests for real dependence on registration order versus mere deterministic listing.
2. Decide the branch from audit evidence:
   - Branch B / canonical-removal branch: if no insertion-order dependency is found and registry callers need ordered read helpers, narrow the contract to exact-name lookup + duplicate-ignore + `:added?` / `:changed?` + canonical deterministic listing.
   - Branch C / presentation-owned ordering branch: if no insertion-order dependency is found and registry callers can treat registry reads as unordered, make ordering non-semantic in `skill-registry`; keep deterministic sorting only in higher prompt/display/introspection surfaces that render or project ordered output.
   - Keep-order branch: if a real insertion-order dependency is found, do not make an ordering-removal code change; document the dependency in `implementation.md`, preserve or add a focused test proving the required order, and update task `164` only to record the confirmed requirement.
3. For branch B, implement the smallest coherent change at the registry boundary/read surface: `all-skills`, `skill-names`, and `register-skill` result `:skills` should be canonical by exact `:name` string ordering, and prompt/display/introspection projections that consume raw session `:skills` should canonicalize before exposing ordered output. Prompt-component / workflow `:skill-names` filtering and workflow step `:session :skills` resolution are allowlist/selection mechanisms; if either renders a selected skill subset to the model, the subset should appear in canonical skill-name order rather than caller-declared or inherited parent/session order.
4. For branch C, remove insertion-order assertions from registry-level tests without replacing them with registry-level sorted-order assertions; prove unordered membership/count/exact lookup at the registry layer and add or update higher-surface tests proving canonical presentation sorting wherever ordered output is user- or model-visible, including prompt-component / workflow `:skill-names` filtered skill subsets and workflow step `:session :skills` selected skill subsets.
5. Add focused tests proving duplicate-ignore remains intact and proving the selected ordering contract:
   - branch B: visible registry and higher listing surfaces are canonical rather than insertion-ordered
   - branch C: registry listing is order-insensitive while higher visible surfaces are canonical
   - keep-order: insertion order is a confirmed, documented, test-backed requirement
6. Update task `164` to reflect the selected branch and refined conclusion.
