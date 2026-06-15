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

- [x] AF-8 Reconcile the **live singleton server/registry handle's
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

- [x] AF-9 Locate AF-7's **system/runtime-scoped status projection on a concrete
  extension-API surface**. AF-7 requires the singleton server's status to project
  into system/runtime-scoped canonical state and excludes both the `/dev-http`
  command handler's session-rebound implicit `(:mutate api)` and `:mutate-session`
  as session-scoped — but the documented extension mutate surfaces are only those
  two (both session-scoped), and the cited `[:runtime :nrepl]` / OAuth precedents
  are core-owned projections not reached through the extension API. Specify the
  realizing mechanism: either (a) define/identify a system-scoped extension
  dispatch surface (a contract addition, as AF-3/AF-6 added first-class
  `psi.extension/*` mutations) whose pure handler writes a system/runtime-scoped
  `:state*` key independent of any invoking session, or (b) confirm and document
  that a dispatch-routed `psi.extension/*` status mutation's pure handler writes
  the system/runtime-scoped key directly regardless of the session-rebound
  triggering surface — so AF-7's "system-scoped, not session-rebound" decision is
  realizable on the extension-API contract. Distinct from AF-6 (event ownership)
  and AF-7 (the scope decision); AF-9 is the unlocated projection surface/mechanism.

- [ ] AF-10 Locate AF-8's **runtime-owned-on-`ctx` live-handle ownership on a
  concrete extension-API surface** (symmetric to AF-9, but for the handle, not the
  status projection). AF-8 decided the live integrant system/server/registry
  handle is a runtime-owned managed handle on `ctx` keyed by logical identity —
  explicitly **not** extension-local hidden state and **not** core `:state*` —
  while the Lifecycle Boundary forbids any **core** namespace gaining dev-http-
  specific integrant code (integrant `init`/`halt!` lives in the extension). But
  the only documented extension-API runtime-ctx-handle surface is the
  managed-service `:ensure-service`/`:stop-service` lifecycle, whose documented
  `:type :subprocess` hosts a **runtime-owned subprocess** lifecycle (runtime
  spawns/kills the process) and whose transport the design itself rejects as
  psi-as-client and unfit; dev-http instead needs a runtime ctx handle whose
  start/stop lifecycle is **extension-provided in-process integrant
  `init`/`halt!`** — a shape no documented surface realizes, and which
  doc/extensions.md's "do not expand the generic managed-service core … prefer
  integration-local adapters" guidance makes a consequential contract choice, not
  a free implementation detail. AF-8 defers the mechanism ("whether via
  `:ensure-service` with a non-subprocess type or a narrower runtime ctx-handle
  mechanism is a planning/implementation detail"), but by the AF-9 standard
  applied in this task the realizing surface must be pinned at design time, since
  AF-8's runtime-owned-on-`ctx` + extension-owned-in-process-lifecycle +
  no-core-dev-http-code + no-extension-local-hidden-state quadrilemma is otherwise
  unrealizable on the documented contract. Specify one of: (a) define/identify a
  generic **non-subprocess managed-handle lifecycle type** whose runtime-owned
  `:ensure-service`/`:stop-service` delegates start/stop to the extension-provided
  in-process `init`/`halt!` (a contract addition in the AF-3/AF-6/AF-9 lineage);
  (b) confirm and document that a narrower runtime ctx-handle extension surface
  already hosts an extension-provided-lifecycle handle keyed by logical identity;
  or (c) justify an alternative host satisfying all four AF-8/Boundary
  constraints. Distinct from AF-8 (the location/ownership decision) and AF-9 (the
  status-*projection* surface) — AF-10 is the unlocated live-*handle* ownership
  surface/mechanism.

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

- [x] AMB-14 Specify the **URL form returned by the registration calls**: which
  of INC-8's two forms (token-less base vs token-embedded copy-pasteable) the
  `dev-present` tool result (AC-3) and `register-route!` return value carry, and
  how the developer obtains a directly-openable link. A `dev-present` tool result
  is journaled into replayable session state, so a token-embedded return URL
  would leak the credential-class token (AF-4/INC-3/INC-8); a token-less return
  needs a defined path for the developer to obtain the token (e.g. via `/dev-http
  status`). Distinct from AMB-1 (transport) and INC-8 (projected-vs-status URL
  forms; neither names the tool-result/return surface).

- [x] AMB-15 Define **empty / no-selection choice-submit** behavior: a `:choices`
  POST with zero options selected (unchecked multi-select, or a radio with no
  default-checked option) — whether it is a "successful POST" that consumes the
  AMB-11 single shot and injects an empty (or `:prompt`-only) user message, or is
  rejected/no-op (browser told "no selection"; single shot not consumed) — so
  AC-6 and its tests are unambiguous. Distinct from AMB-3 (mid-turn timing),
  AMB-8 (target liveness), and AMB-11 (repeat of a selected submission).

- [x] AMB-17 Define **concurrent first-shot choice-submission atomicity** and
  **where the single-shot `submitted` flag is set**. AMB-11 makes a `:choices`
  route single-shot and INC-10 adds a pre-dispatch guard that reads the
  registry-entry submitted flag, but the design never specifies when/where the
  flag flips (in the HTTP handler pre-dispatch vs inside the dispatch-serialized
  `psi.extension/*` mutation) nor how the read-check-mark is atomic — so two
  simultaneous valid first-shot POSTs can both pass the guard before either marks
  submitted (TOCTOU), leaving the "at most one user message per choice route"
  guarantee (AMB-11/AC-6) undefined under concurrency. Specify the authoritative
  single-shot mechanism (e.g. mark inside the dispatch-serialized mutation with
  the pre-dispatch guard as a best-effort fast path that the mutation re-checks/
  no-ops, or a handler-level compare-and-set). Distinct from AMB-11 (sequential
  repeat), AMB-3 (timing), AMB-8 (liveness), AMB-15 (empty selection).
- [x] AMB-18 Define the **token-enforcement boundary** for `register-route!`
  raw-handler routes and persisted `dev/` routes. AMB-1/INC-6 gate "dynamic
  content routes" by content category, but a raw handler fn (and a persisted
  `dev/` handler) emits arbitrary ring responses the platform cannot classify a
  priori. State whether token validation is uniform platform middleware over the
  whole `/s/:route-id` session-route subtree and the persisted `dev/` route
  subtree (raw/persisted handlers auto-gated, never see an untokened request) or
  is the individual handler's responsibility — pinning which layer enforces AC-7
  for the router build and tests, with the static-asset path exempt. Distinct
  from AMB-1 (transport) and INC-6 (static-asset exemption wording).
- [ ] AMB-19 Define the **token-enforcement rejection response**. AMB-1 fixed
  token transport and AMB-18 fixed the enforcement layer (uniform platform
  middleware over the dynamic-route subtrees, so raw/persisted handlers never see
  an untokened request), but neither defines **what a request with a missing or
  invalid token receives** from that middleware (status code + body) — unspecified
  across materially different interpretations: `404` (hide route existence),
  `401`, `403`, a plain "missing/invalid token" page, or a redirect. The design
  pins every other browser-facing response precisely (AMB-8 "session no longer
  active", AMB-11 "choice already submitted", AMB-15 "no selection"), so the
  token-failure response should be pinned too — it is exactly what the AC-7 tests
  must assert (token present → served; missing/invalid → *what?*), and the 404-vs-
  401/403 choice has behavioural consequences (hiding vs revealing route
  existence). Specify the rejection response for the gated dynamic routes (HTML
  pages, choice POST, `:file` serving; static-asset subtree exempt per AMB-18).
  Distinct from AMB-1 (transport), AMB-18 (enforcement layer), and INC-6
  (static-asset exemption wording).

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
- [x] INC-9 Reconcile the **token-embedded surface enumeration** with the
  `/dev-http start` return. INC-8 says the token-embedded copy-pasteable URL is
  shown **only** in the `status` output and the dev start-up log line, but AMB-9
  says an already-running `start` is a no-op **returning the existing
  `url`+`token`** — a third token-embedded surface (the `start` command return)
  not in INC-8's enumeration. Either add the `start`-command return to the
  enumerated token-embedded surfaces, or specify `start` surfaces the token via
  the same `status`-output channel so INC-8's "only" holds. Distinct from INC-8
  (url-term conflation), AF-4 (token externality), and AMB-14 (registration-call
  return URL form).

- [x] AMB-16 Define **registration-call behavior when the server is not
  running**: what `dev-present` (AC-3) and `register-route!` (AC-4) do when
  invoked while `/dev-http` is stopped (no running server → no ephemeral-port
  base URL and, per AF-8, no live registry on `ctx`). Specify one of: error with
  a "start the server first" message; auto-start then register; register into a
  pre-server registry and bind lazily on start; or gate the capability/fn as
  unavailable while stopped — and state how the returned route URL is formed (or
  withheld) in that state. Distinct from AMB-9 (start-command idempotency) and
  AMB-12 (stop/status-command edges); AMB-16 is the registration-call not-running
  edge.

- [x] INC-10 Reconcile **no-op choice-submit log membership / dispatch** across
  AMB-8, AMB-15, and INC-3. AMB-8/AMB-15 describe a dropped (dead-target) or
  empty-selection submit as "the wrapping mutation no-ops" (implying a dispatched
  `psi.extension/*` mutation), but INC-3 admits only message-producing
  interaction-result mutations to the log and excludes "everything else" as
  out-of-band — while the dispatch journal records one entry per dispatch
  (including no-ops). State whether a dropped/empty submit (i) is short-circuited
  by a pre-dispatch guard in the HTTP handler (no wrapping mutation dispatched,
  nothing event-sourced) — then fix AMB-8/AMB-15 wording away from "the wrapping
  mutation no-ops", or (ii) dispatches the wrapping mutation that no-ops (and is
  therefore logged) — then amend INC-3's class-(2) definition to admit no-message
  no-op interaction mutations. Distinct from AMB-8 (liveness), AMB-15
  (empty-selection), and INC-3 (log classes).

- [x] INC-11 Reconcile the **Slicing section** with the "vertical,
  behaviour-first" principle and the Capability surface. Slice 1 ships the
  session-route registry + `/s/:route-id` dispatch mechanism, but its only
  slice-1 behaviour is a **persisted** demo route (which never touches the
  session-route registry), and no session-route registration surface exists until
  slice 2 (`dev-present`) — so slice 1 delivers an unexercisable mechanism
  (horizontal, not behaviour-first). Separately, `register-route!` (a top-level
  Capability-surface/Scope deliverable) and its hiccup/file raw-handler
  escape-hatch helpers are assigned to **no slice** (slice 2 only references
  `register-route!` obliquely; slice 3 adds only the choices helper). Either move
  the session-route registry/dispatch + a minimal registration surface into the
  slice that first delivers a session-route behaviour, or explicitly assign
  `register-route!` (+ hiccup/file helpers) to a slice and note that slice 1's
  registry/dispatch is exercised by that surface. Distinct from INC-1 (slice-1
  demo-output example vs renderer-set ordering).

- [ ] INC-12 Reconcile **INC-3's "both mutation classes enter the log" with the
  AF-7/AF-9 scope split**. INC-3 ("Replay fidelity / log membership") frames the
  two event-sourced classes — (1) status-projection and (2) message-producing
  choice submits — as members of one replayable journal ("the token-less base
  url that enters the log via class (1)"; "nREPL endpoint metadata is likewise in
  the log"), under a single "replay fidelity" heading. But AF-7/AF-9 make class
  (1) **system/runtime-scoped**, dispatched with **no invoking session-id**,
  landing in `[:runtime :dev-http]` (the `[:runtime :nrepl]` / OAuth
  `system_scope(¬agent_session_scope)` scope), while class (2) is **session-
  scoped** (`:mutate-session` against the feedback session). The dispatch
  event-log/trace is agent-session-owned and per-session-replayable, and the cited
  system-scoped projections are not members of any one session's replayable
  conversation log — so a no-session system-scoped status projection and a
  session-scoped, replay-critical choice submit cannot both "enter the log" in the
  same sense. State which journal/scope each class enters (class (2) → the
  feedback session's replayable event-log, replay-relevant; class (1) → the
  system/runtime dispatch record, not a per-session conversation-replay member),
  and adjust INC-3's "replay fidelity / the log" framing (and the "nREPL endpoint
  metadata is likewise in the log" claim) accordingly. Distinct from INC-3
  (which classes are event-sourced, written pre-scope-split), AF-7 (the scope
  decision), and AF-9 (the realizing surface).
