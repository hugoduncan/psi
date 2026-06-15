# 229 — design review follow-up

## Architecture-fit follow-ups

- [ ] AF-1 Resolve persisted-route source path in design toward extension-local
  `extensions/dev-http/dev/` (extension owns its own `dev` extra-path) to
  preserve the "integrant/extension scoped strictly inside dev-http" isolation
  posture; if root-`:dev`/`dev/` coupling is kept, record the explicit
  justification for the cross-component reach.
- [ ] AF-2 Decide and state in design whether dev-http server status
  (running?/url/token) is projected into canonical `:state*` via dispatch for
  EQL/psi-tool introspection (matching the nREPL `[:runtime :nrepl]` /
  OAuth / workflow-progress precedent), or is deliberately kept
  extension-local-only with documented rationale.
- [ ] AF-3 Specify in design that the synthetic-prompt wrapping mutation is a
  first-class `psi.extension/*` mutation (dispatch-routed, declared in
  `:allowed-events`) — an explicit extension-API contract update, not a bridge
  into the internal-only `:session/submit-synthetic-user-prompt` event.
