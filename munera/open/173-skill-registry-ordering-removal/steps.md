# Steps

- [x] Audit `skill-registry` callers/tests and record whether any true registration-order dependency exists.
- [x] Decide whether canonical name-sorted registry listing (branch B), presentation-owned canonical sorting with registry order-insensitivity (branch C), or a real insertion-order dependency (keep-order branch) is the selected contract.
- [x] If using branch B, update `skill-registry` implementation and any affected higher projection/prompt/display code so registry read/result surfaces are canonical by skill `:name`, including any prompt-component or workflow-selected skill subsets before model-visible rendering.
- [x] If using branch C, remove registry insertion-order semantics without adding registry sorted-order semantics; update higher projection/prompt/display code to own canonical presentation sorting, including prompt-component and workflow-selected skill subsets before model-visible rendering. Not selected; branch B was selected.
- [x] If using the keep-order branch, make no ordering-removal code change; document the confirmed dependency and ensure it is test-backed. Not selected; no real insertion-order dependency was found.
- [x] Add or update focused tests to prove the selected ordering contract while preserving duplicate-ignore and `:added?` / `:changed?`.
- [x] Update `munera/open/164-registry-semantics-unification-audit/` to reflect the selected branch.
- [x] Canonicalize `skills-by-source` / `:psi.skill/by-source` per-source vectors by skill `:name` and add focused proof that source-grouped discovery output does not preserve raw session vector order.
