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

## Ambiguity follow-ups

- [ ] AMB-1 Specify the access token transport (query param / header / cookie)
  and exactly which routes it gates (HTML routes, vendored JS assets, choice
  POST, `:file` serving).
- [ ] AMB-2 Define the route-id assignment model: caller-supplied vs
  system-generated, for both `dev-present` and `register-route!`, consistent
  with O4 replace/last-write-wins.
- [ ] AMB-3 Define choice-submit behavior when the originating session is
  mid-turn/busy (queue vs reject vs interrupt); clarify what "immediately" means.
- [ ] AMB-4 Define the choice-feedback target session for routes registered via
  the REPL `register-route!` (which has no invoking agent session).
- [ ] AMB-5 Resolve whether the port is user-configurable: reconcile AC-1
  "configurable/ephemeral" with the Lifecycle/O3 ephemeral-OS-assigned model.
- [ ] AMB-6 Clarify whether SSE (slicing slice 4) is in scope for this task;
  align the slicing section and the "In scope" list.

## Inconsistency follow-ups

- [ ] INC-1 Reconcile Slice 1's demo-route example (e.g. "benchmark table")
  with renderer-set ordering: state that the slice-1 persisted route uses
  platform-only/hand-rolled handler output, or reorder so its example output
  does not depend on the Slice 2 renderer set.
- [ ] INC-2 Reconcile `dev-present`'s "safe, replay-friendly, model-driven"
  framing with its renderer set: decide whether the model-callable tool may
  target the `:hiccup` raw-HTML and `:file` arbitrary-disk-file escape hatches,
  or restrict those to the REPL `register-route!` path.
