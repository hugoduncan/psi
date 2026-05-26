# Steps

- [x] Inventory current registry/session ownership and lifecycle shaping for `skill-registry`, `prompt-registry`, `tool-registry`, `workflow-registry`, and `deterministic-operation-registry` using the review order in `plan.md`.
- [x] Update `design.md` with explicit per-domain classification into membership-id, derived-payload, runtime-adapter, or out-of-scope.
- [x] Clarify the tool-session model decision in `design.md`, including whether `:tool-defs` is the long-term authoritative session surface or a derived compatibility/execution payload.
- [x] Clarify bootstrap/new/resume/fork/child-session shaping rules in `design.md` in terms of canonical definition ownership, session membership/selection authority, and derived compatibility projections, including that prompt lifecycle code may still persist required non-authoritative projection vectors.
- [x] Clarify the prompt-domain lifecycle contract in `design.md`: `:prompt-contribution-ids` is authoritative membership, `:prompt-contributions` is persisted derived compatibility/execution projection state, and child-session inheritance/filtering must be described from membership authority rather than projection authority.
- [x] Clarify that this task produces architectural guidance plus recommended follow-on slices, not same-pass creation/refinement of separate follow-on task directories.