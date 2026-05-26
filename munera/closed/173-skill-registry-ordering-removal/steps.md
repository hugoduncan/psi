# Steps

- [x] Audit `skill-registry` callers/tests and record whether any true registration-order dependency exists.
- [x] Decide whether canonical name-sorted registry listing (branch B), presentation-owned canonical sorting with registry order-insensitivity (branch C), or a real insertion-order dependency (keep-order branch) is the selected contract.
- [x] If using branch B, update `skill-registry` implementation and any affected higher projection/prompt/display code so registry read/result surfaces are canonical by skill `:name`, including any prompt-component or workflow-selected skill subsets before model-visible rendering.
- [x] If using branch C, remove registry insertion-order semantics without adding registry sorted-order semantics; update higher projection/prompt/display code to own canonical presentation sorting, including prompt-component and workflow-selected skill subsets before model-visible rendering. Not selected; branch B was selected.
- [x] If using the keep-order branch, make no ordering-removal code change; document the confirmed dependency and ensure it is test-backed. Not selected; no real insertion-order dependency was found.
- [x] Add or update focused tests to prove the selected ordering contract while preserving duplicate-ignore and `:added?` / `:changed?`.
- [x] Update `munera/open/164-registry-semantics-unification-audit/` to reflect the selected branch.
- [x] Canonicalize `skills-by-source` / `:psi.skill/by-source` per-source vectors by skill `:name` and add focused proof that source-grouped discovery output does not preserve raw session vector order.
- [x] Add focused TUI coverage proving skill banner/autocomplete order is canonical by skill `:name` when session `:skills` starts unsorted (or otherwise prove the TUI state is sourced through `:psi.agent-session/skills` canonical resolver output).
- [x] Add focused registry proof that duplicate/no-change `register-skill` canonicalizes an unsorted pre-existing `:skills` vector while preserving first-write-wins, `:added? false`, and `:changed? false`.
- [x] Make `:session/register-skill` apply canonicalized `register-skill` result `:skills` when they differ from current session skills even if `:changed?` is false, without emitting prompt-refresh effects for duplicate/no-change skill identity.
- [x] Add focused command tests proving `/skills` output and the `/help` Skills section render unsorted session `:skills` in canonical skill-name order rather than raw vector order.
- [x] Add focused registry proof that adding a new skill to an unsorted pre-existing `:skills` vector returns canonical skill-name order while preserving `:added? true` / `:changed? true`.
- [x] Replace TUI-local skill ordering in banner/autocomplete with the shared canonical skill ordering helper so presentation code cannot drift from `skill-registry` ordering semantics.
