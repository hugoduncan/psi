# Steps

Design alignment:

- [ ] Finalize the single-id prompt contribution contract in `design.md`, including canonical `id` normalization and nil/blank handling.
- [ ] Specify same-owner duplicate registration behavior and cross-owner duplicate conflict behavior in `design.md`.
- [ ] Specify post-change lookup/update/unregister targeting, ownership/provenance retention, ordering, and any narrow compatibility handling in `design.md`.
- [ ] Ensure `plan.md`, `steps.md`, and `design.md` describe the same actual task scope and acceptance path.

Implementation later:

- [ ] Inventory prompt-registry APIs, projections, and callers that currently rely on composite `ext-path + id` identity.
- [ ] Implement the chosen single-id identity semantics in `prompt-registry` and affected higher surfaces.
- [ ] Add or update tests covering normalization, duplicate/conflict behavior, targeting, ordering, and affected caller-visible surfaces.
- [ ] Run focused verification and any broader verification needed to close the task.
