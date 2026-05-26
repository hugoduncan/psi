# Implementation notes

- 2026-05-25 ambiguity review: found actionable design ambiguity. The task only had `design.md`; `plan.md`, `steps.md`, and `design-steps.md` were absent. The design does not name the exact public `M-x` command symbol/autoload location, does not define the outside-Psi-buffer behavior, and leaves open whether the command should use existing `psi-emacs--focus-input-area` window-point synchronization or only move current-buffer point. Added follow-up items to `design-steps.md`; implementation artifacts still need to be created before build.
