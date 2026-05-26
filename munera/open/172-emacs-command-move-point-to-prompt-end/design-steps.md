# Design follow-up steps

- [ ] Choose the exact public interactive command name and loading/autoload location so `M-x` discovery is deterministic rather than inferred by implementers.
- [ ] Define behavior when the command is invoked outside an initialized Psi Emacs session buffer, including whether it signals `user-error` or no-ops.
- [ ] Clarify whether the command should delegate to `psi-emacs--focus-input-area` and therefore update the selected/all visible window points, or only move point in the current buffer.
- [ ] Create `plan.md` and `steps.md` before implementation so the command location, tests, and verification order are explicit.
