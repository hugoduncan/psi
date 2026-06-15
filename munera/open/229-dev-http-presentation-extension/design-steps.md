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

- [x] AF-4 Reconsider projecting the per-launch `token` into canonical
  `:state*`. Per the OAuth credential-externality precedent (State-boundary
  table: credential store stays external, only login status projected), keep the
  token in the extension-local handle and project only `running?`/`url`,
  surfacing the live token via the `status`/log path — avoiding a secret in the
  replayable event-log / dispatch-trace. Or document why the dev-grade token is
  deliberately placed in canonical state.
- [x] AF-5 Specify that the `psi.extension/*` choice-submit mutation triggers
  `:session/submit-synthetic-user-prompt` via a `:runtime/dispatch-event`
  follow-on effect (pure handler → effects-as-data), not an imperative
  in-handler dispatch, to honor the Dispatch sequencing contract.

- [x] AF-6 Specify the **event-ownership** of the status-projection mutation
  (lifecycle `start`/`stop` projecting `running?`/`url` into `:state*`): state
  that it is a first-class `psi.extension/*` dispatch-routed mutation declared in
  `:allowed-events`, consistent with AF-3's untrusted-extension posture — not a
  reach into a core/internal projection event (the cited nREPL precedent
  `:session/set-nrepl-runtime` is core-owned, so the precedent governs *shape*,
  not the dispatching event's ownership).

- [x] AF-7 Specify the **canonical-state scope** of the status-projection
  mutation (`running?`/`url`): the dev-http server is a process-wide singleton,
  so project its status into **system/runtime-scoped** state queryable
  system-wide (matching the system-scoped nREPL `[:runtime :nrepl]` and OAuth
  precedents), dispatched via the system-scoped `:mutate` surface — not the
  invoking-session-rebound implicit mutate / `:mutate-session`. Keep the
  asymmetry explicit (status = system-scoped; choice-submit = session-scoped).
  Distinct from AF-2/AF-4 (what projects) and AF-6 (event ownership).

- [ ] AF-8 Reconcile the **live singleton server/registry handle's
  location/ownership** with META.md's managed-services principle ("psi runtime
  owns process-scoped managed services on ctx for long-lived subprocesses …";
  "managed services are keyed by logical identity … rather than extension-local
  hidden state"). The design keeps the integrant system handle in
  extension-local hidden state (citing `mcp-tasks-run`/`work-on`) without
  engaging that principle or the nREPL/project-nrepl runtime-handle-on-ctx
  precedents — yet AF-7 establishes the server is a process-wide singleton
  (system-scoped), which fits the managed-service-on-ctx model. Either model the
  server as a runtime-owned managed service on `ctx` keyed by logical identity,
  or explicitly justify why the untrusted-extension isolation posture overrides
  the managed-services principle for this singleton. Distinct from AF-2/AF-4
  (what projects), AF-6 (event ownership), and AF-7 (projection scope) — AF-8 is
  the live-handle location/ownership decision.

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

- [x] AMB-7 Define how a `:choices` selection maps to the injected synthetic
  user-message: the choice option schema (label vs value), single- vs
  multi-select, and the exact string that becomes the user prompt (AC-6).
- [x] AMB-8 Define choice-submit behavior when the target session has
  ended/closed (target liveness, not just identity), and clarify whether the
  session-route registry is cleared on server-halt only or also when the
  invoking agent session ends ("die with the server/session").
- [x] AMB-9 Define `/dev-http start` behavior when the server is already running
  (no-op / return existing url+token / restart / error), since the command
  surface has no `restart` and AC-1 only addresses no-orphan-on-reload/restart.

- [x] AMB-10 Specify the `dev-present` per-renderer content-data shape for the
  declarative renderers — in particular the `:table` input shape
  (vector-of-maps vs vector-of-vectors vs `{:headers :rows}`), and confirm the
  implied shapes for `:markdown` (string), `:vega` (Vega-Lite spec map), and
  `:mermaid` (source string) — so the `dev-present` content contract is
  unambiguous for AC-3/AC-5.
- [x] AMB-11 Define repeat-submission behavior for a `:choices` route: whether
  multiple POSTs to the same live route each inject a fresh user message, or the
  route is single-shot after the first submission (and what the browser sees on a
  subsequent submit). Distinct from AMB-3 (mid-turn timing) and AMB-8 (target
  liveness).

- [x] AMB-12 Define lifecycle command behavior when the server is **not
  running**: `/dev-http stop` against a stopped server (no-op success vs error)
  and what `/dev-http status` reports when stopped (`running? false`, no
  url/token). Distinct from AMB-9 (start-side idempotency).
- [x] AMB-13 Define the **persisted-route discovery/aggregation contract**: how
  the platform collects `extensions/dev-http/dev/` reitit route-data + handler
  namespaces into the router at integrant `init`/reload (conventional entry
  namespace/var vs auto-scan vs explicit register call), so AC-2 and the
  reload story are unambiguous for the planner. Distinct from AMB-8 (session
  registry lifetime) and AMB-2 (route-id assignment).

- [ ] AMB-14 Specify the **URL form returned by the registration calls**: which
  of INC-8's two forms (token-less base vs token-embedded copy-pasteable) the
  `dev-present` tool result (AC-3) and `register-route!` return value carry, and
  how the developer obtains a directly-openable link. A `dev-present` tool result
  is journaled into replayable session state, so a token-embedded return URL
  would leak the credential-class token (AF-4/INC-3/INC-8); a token-less return
  needs a defined path for the developer to obtain the token (e.g. via `/dev-http
  status`). Distinct from AMB-1 (transport) and INC-8 (projected-vs-status URL
  forms; neither names the tool-result/return surface).

- [ ] AMB-15 Define **empty / no-selection choice-submit** behavior: a `:choices`
  POST with zero options selected (unchecked multi-select, or a radio with no
  default-checked option) — whether it is a "successful POST" that consumes the
  AMB-11 single shot and injects an empty (or `:prompt`-only) user message, or is
  rejected/no-op (browser told "no selection"; single shot not consumed) — so
  AC-6 and its tests are unambiguous. Distinct from AMB-3 (mid-turn timing),
  AMB-8 (target liveness), and AMB-11 (repeat of a selected submission).

## Inconsistency follow-ups

- [x] INC-1 Reconcile Slice 1's demo-route example (e.g. "benchmark table")
  with renderer-set ordering: state that the slice-1 persisted route uses
  platform-only/hand-rolled handler output, or reorder so its example output
  does not depend on the Slice 2 renderer set.
- [x] INC-2 Reconcile `dev-present`'s "safe, replay-friendly, model-driven"
  framing with its renderer set: decide whether the model-callable tool may
  target the `:hiccup` raw-HTML and `:file` arbitrary-disk-file escape hatches,
  or restrict those to the REPL `register-route!` path.
- [x] INC-3 Reconcile the "Replay fidelity" (*only interaction-result mutations
  enter the log*) and "Determinism boundary" (*live server outside the
  deterministic core*) constraints with the AF-2 status-projection dispatch
  mutation (which enters the log and writes non-deterministic `url`/`token` into
  canonical `:state*`); state precisely which mutation classes enter the log.
- [x] INC-4 Reconcile the `:mermaid` renderer's "(and/or Graphviz)" claim with
  O5 / Client-assets vendoring only Vega-Lite + Mermaid JS: either drop the
  Graphviz claim or add a vendored Graphviz/viz.js asset.

- [x] INC-5 Reconcile `register-route!`'s fn-based definition ("an arbitrary
  ring handler fn") with its billing as "the only path to the `:hiccup`/`:file`
  escape hatches" (declarative renderers per AC-5): either state that
  `register-route!` also accepts a declarative renderer spec (full renderer set
  incl. `:hiccup`/`:file`), or reframe `:hiccup`/`:file` as raw-handler idioms
  (not selectable renderers) and adjust AC-5 accordingly.
- [x] INC-6 Reconcile AC-7's blanket "Access requires the per-launch token" with
  the AMB-1/Lifecycle static-asset exemption: reword AC-7 to scope the token
  requirement to dynamic content routes (HTML pages, choice POST, `:file`),
  noting vendored static JS/CSS assets are exempt.

- [x] INC-7 Reconcile `register-route!` choice feedback with INC-5's
  dev-present-only `:choices`: either expose a platform choice-POST mechanism /
  helper for `register-route!` raw handlers (defining how a raw handler emits a
  platform-wired choice form bound to its `:session-id`), or drop the AMB-4
  "`:choices`/POST feedback" + `:session-id` framing for `register-route!` as
  vestigial after INC-5. Distinct from INC-5 (hiccup/file reframing).
- [x] INC-8 Reconcile the `url` term: state explicitly that the projected/logged
  canonical `url` (AF-4/INC-3) is the **token-less base** URL, while the
  surfaced/`status`/log copy-pasteable URL (AMB-1) is **token-embedded**
  (reconstructed from the base url + the external token at render time), so
  projecting/logging `url` cannot leak the token. Distinct from AF-4 (token
  externality) and INC-3 (log membership).
- [ ] INC-9 Reconcile the **token-embedded surface enumeration** with the
  `/dev-http start` return. INC-8 says the token-embedded copy-pasteable URL is
  shown **only** in the `status` output and the dev start-up log line, but AMB-9
  says an already-running `start` is a no-op **returning the existing
  `url`+`token`** — a third token-embedded surface (the `start` command return)
  not in INC-8's enumeration. Either add the `start`-command return to the
  enumerated token-embedded surfaces, or specify `start` surfaces the token via
  the same `status`-output channel so INC-8's "only" holds. Distinct from INC-8
  (url-term conflation), AF-4 (token externality), and AMB-14 (registration-call
  return URL form).
