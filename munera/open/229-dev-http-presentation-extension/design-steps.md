# 229 — design review follow-up

## Architecture-fit follow-ups

- [x] AF-1 Resolve persisted-route source path in design toward extension-local
  `extensions/dev-http/dev/` (extension owns its own `dev` extra-path) to
  preserve the "integrant/extension scoped strictly inside dev-http" isolation
  posture; if root-`:dev`/`dev/` coupling is kept, record the explicit
  justification for the cross-component reach.
- [x] AF-2 Decide and state in design whether dev-http server status
  (running?/url/token) is projected into canonical `:state*` via dispatch for
  EQL/psi-tool introspection (matching the nREPL `[:runtime :nrepl]` /
  OAuth / workflow-progress precedent), or is deliberately kept
  extension-local-only with documented rationale.
- [x] AF-3 Specify in design that the synthetic-prompt wrapping mutation is a
  first-class `psi.extension/*` mutation (dispatch-routed, declared in
  `:allowed-events`) — an explicit extension-API contract update, not a bridge
  into the internal-only `:session/submit-synthetic-user-prompt` event.

- [ ] AF-4 Reconsider projecting the per-launch `token` into canonical
  `:state*`. Per the OAuth credential-externality precedent (State-boundary
  table: credential store stays external, only login status projected), keep the
  token in the extension-local handle and project only `running?`/`url`,
  surfacing the live token via the `status`/log path — avoiding a secret in the
  replayable event-log / dispatch-trace. Or document why the dev-grade token is
  deliberately placed in canonical state.
- [ ] AF-5 Specify that the `psi.extension/*` choice-submit mutation triggers
  `:session/submit-synthetic-user-prompt` via a `:runtime/dispatch-event`
  follow-on effect (pure handler → effects-as-data), not an imperative
  in-handler dispatch, to honor the Dispatch sequencing contract.

## Ambiguity follow-ups

- [x] AMB-1 Specify the access token transport (query param / header / cookie)
  and exactly which routes it gates (HTML routes, vendored JS assets, choice
  POST, `:file` serving).
- [x] AMB-2 Define the route-id assignment model: caller-supplied vs
  system-generated, for both `dev-present` and `register-route!`, consistent
  with O4 replace/last-write-wins.
- [x] AMB-3 Define choice-submit behavior when the originating session is
  mid-turn/busy (queue vs reject vs interrupt); clarify what "immediately" means.
- [x] AMB-4 Define the choice-feedback target session for routes registered via
  the REPL `register-route!` (which has no invoking agent session).
- [x] AMB-5 Resolve whether the port is user-configurable: reconcile AC-1
  "configurable/ephemeral" with the Lifecycle/O3 ephemeral-OS-assigned model.
- [x] AMB-6 Clarify whether SSE (slicing slice 4) is in scope for this task;
  align the slicing section and the "In scope" list.

- [ ] AMB-7 Define how a `:choices` selection maps to the injected synthetic
  user-message: the choice option schema (label vs value), single- vs
  multi-select, and the exact string that becomes the user prompt (AC-6).
- [ ] AMB-8 Define choice-submit behavior when the target session has
  ended/closed (target liveness, not just identity), and clarify whether the
  session-route registry is cleared on server-halt only or also when the
  invoking agent session ends ("die with the server/session").
- [ ] AMB-9 Define `/dev-http start` behavior when the server is already running
  (no-op / return existing url+token / restart / error), since the command
  surface has no `restart` and AC-1 only addresses no-orphan-on-reload/restart.

## Inconsistency follow-ups

- [x] INC-1 Reconcile Slice 1's demo-route example (e.g. "benchmark table")
  with renderer-set ordering: state that the slice-1 persisted route uses
  platform-only/hand-rolled handler output, or reorder so its example output
  does not depend on the Slice 2 renderer set.
- [x] INC-2 Reconcile `dev-present`'s "safe, replay-friendly, model-driven"
  framing with its renderer set: decide whether the model-callable tool may
  target the `:hiccup` raw-HTML and `:file` arbitrary-disk-file escape hatches,
  or restrict those to the REPL `register-route!` path.
