2026-05-05
- Task created from implementation review follow-up spanning `077` and `089`.
- Rationale: session output normalization is mostly converged in docs/IR/compiler, but runtime step preparation and statechart execution still show split responsibility and duplication risk.
- This task is intended as a narrow shaping/convergence slice, not a redesign of the broader workflow runtime.
- Design review follow-up:
  - clarified that the task should prefer an existing authoritative interpretation layer over introducing a new peer helper/seam
  - clarified that accidental compat-era duplication may be removed when the resulting canonical behavior is made explicit in focused tests
  - clarified that `workflow_source_resolution.clj` is in play only if it is already the clearest home for the authoritative interpretation, not as a reason to broaden the slice
