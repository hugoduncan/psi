# Implementation steps

- [ ] Add request/result model and validation helpers.
- [ ] Add constrained submission helper for descriptor-based UI action requests.
- [ ] Route accepted requests to the active UI adapter boundary.
- [ ] Handle Emacs make-visible requests by invoking `psi-emacs-show-active` on the Emacs side.
- [ ] Return structured results for accepted/completed/rejected/unsupported/failed/timeout states.
- [ ] Add tests for unavailable, malformed, stale, unsupported, and accepted Emacs requests.
- [ ] Update docs to distinguish queryability from implemented side-effecting invocation.
