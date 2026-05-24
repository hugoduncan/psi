# Steps

Design alignment:

- [x] Finalize the single-id prompt contribution contract in `design.md`, including canonical `id` normalization and nil/blank handling.
- [x] Specify same-owner duplicate registration behavior and cross-owner duplicate conflict behavior in `design.md`.
- [x] Specify post-change lookup/update/unregister targeting, ownership/provenance retention, ordering, and any narrow compatibility handling in `design.md`.
- [x] Ensure `plan.md`, `steps.md`, and `design.md` describe the same actual task scope and acceptance path.

Implementation later:

- [x] Inventory only the remaining lower-level seams, projections, tests/helpers, and callers that still model or depend on composite `ext-path + id` identity; keep already-single-id extension-facing helpers/docs out of that inventory.
- [x] Implement the chosen single-id identity semantics in `prompt-registry` and affected lower-level seams plus higher projections.
- [x] Add or update tests covering normalization, duplicate/conflict behavior, lower-level targeting, ordering, and affected caller-visible surfaces.
- [x] Run focused verification and any broader verification needed to close the task.
