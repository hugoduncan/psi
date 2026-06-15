# 229 — implementation notes

## Review log

### design-review · architecture (turn 1)

Architectural-fit pass (¬correctness, ¬ambiguity, ¬inconsistency). Sources:
AGENTS.md, META model, doc/architecture.md, doc/extension-api.md, doc/extensions.md.

Strong fit confirmed: reads-via-`:query`/writes-via-`:mutate`, runtime-handle
externality (integrant system in extension's own atom, not core `:state*`),
replay/determinism boundary reasoning, minimal capability + `:allowed-events`,
interaction re-entering session through a mutation (input-as-event-source
analog to TUI/RPC). Extension-local `deps.edn` matches the per-extension
local-root precedent (work-on, mcp-tasks-run).

Actionable architectural misfits:

- AF-1 Persisted routes load from the root `:dev {:extra-paths ["dev"]}`
  project-global path, coupling an otherwise strictly-isolated extension to a
  project-global source location. Isolation posture favors extension-local
  `extensions/dev-http/dev/` (extension owns its own dev extra-path) unless the
  root-`dev/` coupling is explicitly justified. (Task already flags this as a
  planning decision; the architecture-fit lens favors extension-local.)
- AF-2 Observable server status is surfaced only via `status` output + log. The
  State-boundary principle projects subsystem status worth querying into
  `:state*` via dispatch (precedent: nREPL `[:runtime :nrepl]` endpoint
  metadata, OAuth login status, workflow progress) while the handle stays
  external. Decide explicitly: project running?/url/token into canonical state
  for EQL/psi-tool introspection (recommended, matches precedent) or document
  the deliberate extension-local-only divergence.
- AF-3 The extension-facing mutation wrapping the currently-internal
  `:session/submit-synthetic-user-prompt` should be bound to the documented
  `psi.extension/*` mutation surface, dispatch-routed, and permission-gated via
  `:allowed-events` — an explicit contract update (one-way/no-shim,
  untrusted-extension posture), not an internal-event back-door. Design states
  it is in scope but does not locate it on the extension-API contract.

### design-review · architecture (round 2, turn 1)

Fresh architecture-fit pass over the post-resolution design (AF-1..3 already
resolved into design.md). Sources: AGENTS.md, META.md, doc/architecture.md
(State boundary table; Dispatch sequencing contract). AF-1..3 confirmed
genuinely resolved (extension-local `dev/`, status projection, first-class
`psi.extension/*` mutation). Two new actionable misfits:

- AF-4 AF-2's resolution projects `running?`/`url`/**`token`** into canonical
  `:state*` via dispatch, modeled on the nREPL endpoint-metadata precedent. But
  the State-boundary table keeps the OAuth credential store *external* and
  projects only login *status*; secrets do not enter canonical state. The
  per-launch token is a credential-class secret — projecting it also lands it in
  the replayable event-log + dispatch-trace summaries. Conforming choice: keep
  the token in the extension-local handle, project only `running?`/`url`, and
  surface the live token via the `status`/log path (reads the external handle).
  `url`/port non-determinism is already precedented (nREPL); the token's
  *secrecy* is the distinguishing misfit. Not covered by AF-1..3.
- AF-5 D3 says the wrapping mutation's "handler internally dispatches
  `:session/submit-synthetic-user-prompt`". The Dispatch sequencing contract
  sanctions follow-on dispatch only via the `:runtime/dispatch-event` effect
  (pure handler → effects-as-data). Imperative in-handler dispatch violates the
  pure-handler boundary. Conforming mechanism: the wrapping mutation emits a
  `:runtime/dispatch-event` follow-on effect targeting the synthetic-user-prompt
  event. Distinct from AF-3 (contract surface + `:allowed-events`).

### design-review · architecture (round 3, turn 1)

Fresh architecture-fit pass over the post-resolution design (AF-1..5 resolved).
Sources: AGENTS.md, META.md, doc/architecture.md (State boundary table line
156–174; Dispatch sequencing contract line 204–236, `:runtime/dispatch-event`
follow-on). AF-1..5 confirmed genuinely resolved and precedent-accurate
(extension-local `dev/`; running?/url projected + token external per OAuth
credential-externality; first-class `psi.extension/*` choice mutation in
`:allowed-events` emitting a `:runtime/dispatch-event` follow-on). One new
actionable misfit:

- AF-6 The **status-projection mutation** (lifecycle `start`/`stop` projecting
  `running?`/`url` into `:state*`) has **unspecified event-ownership**. It is
  dispatched **by the extension** (driven by its `/dev-http` command handler),
  but AF-2/AF-4 frame it only as "via dispatch ... matching the nREPL precedent"
  — and that precedent (`:session/set-nrepl-runtime`) is a **core-owned** event.
  An untrusted extension dispatching a core projection event is exactly the AF-3
  anti-pattern (internal-event reach). Conforming choice: state that the
  status-projection mutation is a **first-class `psi.extension/*` dispatch-routed
  mutation declared in `:allowed-events`**, consistent with AF-3's
  untrusted-extension posture — not a reach into a core/internal projection
  event. Distinct from AF-2/AF-4 (which fixed *what* projects: running?/url, not
  token) and from AF-3 (which fixed only the *choice-submit* mutation's
  ownership).

### design-review · architecture (round 4, turn 1)

Fresh architecture-fit pass over the fully-resolved design (AF-1..6 all
resolved). Sources: AGENTS.md (S2 OAuth `system_scope(¬agent_session_scope)`),
META model, doc/architecture.md (State-boundary table; runtime-handles table;
nREPL `[:runtime :nrepl]` precedent), doc/extension-api.md (`:query`/`:mutate`
ambient-vs-`:query-session`/`:mutate-session` explicit-session boundary).
AF-1..6 confirmed genuinely resolved and precedent-accurate. The choice-submit
mutation is correctly session-scoped (`:mutate-session` against the AMB-4
feedback target — the proper surface for the deferred/background HTTP-handler
context, which has no ambient session focus). One new actionable misfit:

- AF-7 The **status-projection mutation's canonical-state scope**
  (system/runtime vs agent-session) is unspecified. The dev-http server is a
  process-wide **singleton** (one server shared across all sessions), and the
  two cited projection precedents — nREPL `[:runtime :nrepl]` endpoint metadata
  and OAuth login status — are **both system-scoped** (AGENTS.md S2: OAuth
  `system_scope(¬agent_session_scope)`; nREPL is a system runtime handle in the
  State-boundary runtime-handles table). AF-2/AF-4 fixed *what* projects
  (`running?`/`url`, not the token) and AF-6 fixed event *ownership* (first-class
  `psi.extension/*` in `:allowed-events`), but neither pins the *scope*. Because
  a slash-command handler's implicit `(:mutate api)` is rebound to the invoking
  session (doc/extension-api.md), a naive `/dev-http` status projection would
  land in the **invoking session's** scope — wrong for a singleton (duplicated/
  divergent status across sessions, ambiguous ownership, no system-wide answer
  to "is the server up?"). Conforming choice: project server status into
  **system/runtime-scoped** canonical state, queryable system-wide like
  `[:runtime :nrepl]`, and dispatch it via the system-scoped `:mutate` surface
  (not the session-rebound implicit mutate / `:mutate-session`) — keeping the
  asymmetry explicit: status is system-scoped, the choice-submit is
  session-scoped. Distinct from AF-2/AF-4 (what projects) and AF-6 (event
  ownership).

### design-review · architecture (round 5, turn 1)

Fresh architecture-fit pass over the fully-resolved design (AF-1..7 all
resolved). Sources: AGENTS.md, META.md (managed-services bullets), doc/
architecture.md (State-boundary runtime-handles table; nREPL `[:runtime :nrepl]`
+ project-nrepl-registry precedents), doc/extension-api.md (`:mutate-session`
for background/HTTP-handler context). AF-1..7 confirmed genuinely resolved and
precedent-accurate; the choice-submit `:mutate-session` session-scoping is the
right surface for the ambient-session-less HTTP-handler thread. One new
actionable misfit:

- AF-8 The **live singleton server/registry handle's location** is unreconciled
  with the managed-services principle. AF-2/4/6/7 settle the *status projection*
  (what projects, event ownership, system scope) and correctly keep the handle
  out of the core `:state*` atom — but none addresses *where the live handle
  lives*. The design commits the integrant system/server handle to
  **extension-local hidden state** ("the extension's own atom/system, as
  `mcp-tasks-run`/`work-on`"), citing only those extension precedents. It never
  engages META.md's explicit principle — "psi runtime owns process-scoped
  managed services on ctx for long-lived subprocesses and similar runtime
  resources" and "managed services are keyed by logical identity and reused
  within ctx rather than extension-local hidden state" — nor the closest
  runtime-handle precedents (nREPL server, project-nrepl registry), which are
  runtime-owned managed handles on `ctx` (not the `:state*` atom, but not
  extension-local hidden state either). AF-7's own finding that the server is a
  process-wide **singleton** strengthens the misfit: a system-scoped singleton
  long-lived subprocess is exactly the managed-service-on-ctx shape (like nREPL/
  project-nrepl), whereas `mcp-tasks-run`/`work-on` are the precedent for
  session/extension-scoped handles. Conforming choice: either model the dev-http
  server as a **runtime-owned managed service on `ctx` keyed by logical
  identity** (matching the managed-services principle + nREPL/project-nrepl
  singleton precedent), or explicitly justify why the untrusted-extension
  isolation posture overrides the managed-services principle for this singleton
  (keeping it extension-local). Distinct from AF-2/AF-4 (what projects), AF-6
  (event ownership), and AF-7 (projection scope) — AF-8 is the live-handle
  *location/ownership* decision, currently resolved by precedent-citation
  without engaging the managed-services principle.

### design-review · ambiguity (round 5, turn 2)

Fresh ambiguity pass over the fully-resolved design (AMB-1..13 resolved). Used
in-context design.md + architecture sources + round-5 architecture reply (AF-8,
an architecture item, not duplicated here). AMB-1..13 confirmed resolved. Two
new actionable ambiguities:

- AMB-14 The **URL form returned by the registration calls** (`dev-present` tool
  result / `register-route!` return value) is unspecified. INC-8 distinguished
  the token-less base URL (projected/logged) from the token-embedded
  copy-pasteable URL but enumerated the token-embedded surfaces as **only** the
  `status` output + dev start-up log line; the `dev-present` "returns its URL"
  (AC-3) and `register-route!` return are a third, unspecified surface. This is
  load-bearing: a `dev-present` tool result is journaled into replayable session
  state, so returning the token-embedded URL would leak the credential-class
  token into the conversation journal (violating AF-4/INC-3/INC-8), while
  returning the token-less base URL leaves the model unable to hand the developer
  a directly-openable link (it must separately fetch the token via `/dev-http
  status`). The design must state which URL form each registration call returns
  and how the developer obtains an openable link without journaling the token.
  Distinct from AMB-1 (transport) and INC-8 (projected-vs-status URL forms;
  neither names the tool-result/return surface).

- AMB-15 The **empty / no-selection choice submit** is undefined. AMB-7 maps
  *selected* `:value`(s) to the injected message and AMB-11 covers *repeat*
  submission, but a POST with **zero** options selected (reachable for unchecked
  multi-select checkboxes, and for radios rendered with no default-checked
  option) is unspecified: whether it is a "successful POST" that consumes the
  AMB-11 single shot and injects an empty (or prompt-only) user message, or is
  rejected/no-op (browser told "no selection"; shot not consumed). AC-6 and its
  tests need the defined behavior. Distinct from AMB-3 (mid-turn timing), AMB-8
  (target liveness), and AMB-11 (repeat of a *selected* submission).

### design-review · inconsistency (round 5, turn 3)

Fresh inconsistency pass over the fully-resolved design (INC-1..8 resolved).
Used in-context design.md + architecture sources + round-5 architecture (AF-8)
and ambiguity (AMB-14/15) replies. INC-1..8 confirmed resolved; AF-8 / AMB-14 /
AMB-15 are architecture/ambiguity items, not duplicated here. One new actionable
inconsistency:

- INC-9 **Token-embedded surface enumeration vs the `/dev-http start` return.**
  INC-8 states the token-embedded copy-pasteable URL "is shown **only** in the
  human-facing `status` output and the dev start-up log line." But AMB-9's
  idempotent-start resolution says an already-running `start` "is a no-op
  returning the existing `url`+`token`" — making the `/dev-http start` command's
  own return value a third token-embedded surface, distinct from a `status` query
  and from the timbre start-up log line, which INC-8's "only" enumeration
  excludes. The two resolutions were never reconciled. Either add the
  `start`-command return to the enumerated token-embedded surfaces, or specify
  that `start` surfaces the token via the same `status`-output channel so INC-8's
  "only" holds. Internal contradiction between the INC-8 token-externality
  enumeration and the AMB-9 start-return text. Distinct from INC-8 (url-term
  conflation), AF-4 (token externality principle), and AMB-14 (the
  *registration-call* return URL form — INC-9 is the *lifecycle `start` command*
  return).

  (Considered but not filed: AC-3's "returned URL renders the content" vs AC-7's
  token-gated dynamic routes + AF-4 token-externality is the same root issue
  already captured by AMB-14; not duplicated as an inconsistency.)

### design-review · inconsistency (round 4, turn 3)

Fresh inconsistency pass over the fully-resolved design (INC-1..6 resolved).
Used in-context design.md + architecture sources + round-4 architecture (AF-7)
and ambiguity (AMB-12/13) replies. INC-1..6 confirmed resolved; AF-7 / AMB-12 /
AMB-13 are architecture/ambiguity items, not duplicated here. Two new actionable
inconsistencies (both resolution-introduced, across sections):

- INC-7 **register-route! choice feedback vs `:choices` being dev-present-only.**
  INC-5 (round 3) reframed the declarative renderer set as
  `:markdown`/`:table`/`:vega`/`:mermaid`/`:choices` and limited
  `register-route!` raw-handler helpers to **hiccup + file only** (no choices
  helper). But the AMB-4 (round 2) Choice-feedback paragraph still says the REPL
  `register-route!` route "takes an explicit `:session-id` … if omitted, the
  route is presentation-only (its **`:choices`/POST feedback** is disabled)",
  attributing `:choices`/POST interaction to a `register-route!` raw-handler
  route. Post-INC-5 there is no documented path for a raw handler fn to emit a
  platform-wired choice form (no choice helper listed beside hiccup/file), so
  either the platform's choice-POST interaction mechanism is available to
  `register-route!` routes (and a helper/contract is missing), or
  `register-route!`'s `:session-id` and its "`:choices`/POST feedback" are
  vestigial after INC-5. Internal contradiction across Renderers/INC-5, the
  `register-route!` description, and the AMB-4 Interaction paragraph. Distinct
  from INC-5 (which only reframed hiccup/file).

- INC-8 **The `url` term conflates a token-embedded surfaced value with a
  token-less projected/logged value.** AMB-1 says the per-launch token is a URL
  query param "so the URL surfaced in `status`/log is copy-pasteable and opens
  directly in a browser" (token embedded), and the Server bullet says "both the
  resolved URL and token are surfaced." But AF-4/INC-3 require the projected
  canonical `url` (and the logged class-1 value) to **exclude** the token (token
  never enters `:state*`/log). The single term `url`/"resolved URL" therefore
  denotes two different values — a token-embedded copy-pasteable surface string
  and a token-less projected/logged value — and the design never states the
  projected/logged `url` is the token-less base while the surfaced/log URL is
  token-embedded (reconstructed from base url + external token at render time).
  Taken literally, projecting the copy-pasteable `url` would leak the token into
  canonical state/log, violating AF-4/INC-3. Internal contradiction between AMB-1
  / the Server-bullet surfacing text and the AF-4/INC-3 token-externality
  constraints. Distinct from AF-4 (token externality *principle*) and INC-3 (log
  membership) — INC-8 is the unreconciled single-term `url` conflation.

### design-review · ambiguity (turn 2)

Ambiguity pass (¬correctness, ¬architecture, ¬inconsistency). Used in-context
design.md + architecture sources + AF notes. Six new actionable ambiguities:

- AMB-1 Token transport (query param/header/cookie) and which routes the token
  gates (HTML / vendored JS assets / choice POST / `:file`) are unspecified.
- AMB-2 Route-id assignment model (caller-supplied vs system-generated) is
  unclear and differs implicitly between `dev-present` and `register-route!`;
  O4 replace semantics presume caller-controlled ids.
- AMB-3 "drives the agent's next turn immediately" is undefined when the
  originating session is mid-turn/busy at choice-submit time (queue/reject/
  interrupt).
- AMB-4 Choice feedback targets "the invoking session," but `register-route!`
  is a REPL fn with no invoking agent session; the feedback target session for
  REPL-registered routes is undefined.
- AMB-5 AC-1 "configurable/ephemeral port" vs Lifecycle/O3 "ephemeral
  OS-assigned" leaves it ambiguous whether a port-config override exists.
- AMB-6 SSE appears as slicing slice 4 but is absent from the "In scope" list;
  whether SSE is a deliverable of this task is ambiguous.

### design-review · ambiguity (round 2, turn 2)

Fresh ambiguity pass over the post-resolution design (AMB-1..6 already resolved).
Used in-context design.md + architecture sources + round-2 architecture reply
(AF-4/AF-5). AMB-1..6 confirmed resolved. Three new actionable ambiguities:

- AMB-7 The interaction loop (AC-6) does not define how a `:choices` selection
  maps to the injected synthetic user-message text: the choice option schema
  (label vs value), single- vs multi-select, and exactly what string becomes the
  user prompt are unspecified. AMB-3/AMB-4 covered submit *timing* and target
  *identity*, not selection *content shape*.
- AMB-8 AMB-4 fixes the feedback target *identity* but not its *liveness*:
  behavior when a choice is submitted after the target session has
  ended/closed is undefined. Relatedly "throwaway; they die with the
  server/session" conflates server-lifecycle vs invoking-agent-session-lifecycle
  for when the session-route registry is cleared.
- AMB-9 Lifecycle `start` behavior when the server is already running (no-op /
  return existing url+token / restart / error) is unspecified; AC-1 covers
  no-orphan-on-reload/restart but not explicit double-`start` semantics, and the
  command surface has no `restart`.

### design-review · ambiguity (round 3, turn 2)

Fresh ambiguity pass over the post-resolution design (AMB-1..9 resolved). Used
in-context design.md + architecture sources + round-3 architecture reply (AF-6).
AMB-1..9 confirmed resolved. Two new actionable ambiguities:

- AMB-10 The `dev-present` per-renderer **content-data shape** is specified only
  for `:choices` (AMB-7); the other declarative renderers' input shapes are
  asymmetric/under-specified, notably `:table` ("tabular data" — vector-of-maps
  vs vector-of-vectors vs `{:headers :rows}`?). `:markdown` (string), `:vega`
  (Vega-Lite spec map), `:mermaid` (source string) are reasonably implied, but
  `:table` is genuinely ambiguous and a planner needs the concrete `dev-present`
  content schema. Distinct from AMB-7 (selection→message content, not input
  shape).
- AMB-11 **Repeat choice submission** is undefined: whether a `:choices` route
  accepts multiple POSTs (each injecting a fresh user message) or is single-shot
  after the first submission. AMB-3 covered mid-turn *timing* and AMB-8 covered
  target *liveness*, but neither covers re-submission of the same live route
  (e.g. dev opens the page twice / changes their pick / double-clicks submit).

### design-review · ambiguity (round 4, turn 2)

Fresh ambiguity pass over the fully-resolved design (AMB-1..11 resolved). Used
in-context design.md + architecture sources + round-4 architecture reply (AF-7).
AMB-1..11 confirmed resolved; AF-7 (status-projection scope) is an
architecture-fit item, not duplicated here. Two new actionable ambiguities:

- AMB-12 **Lifecycle behavior when the server is not running** is undefined.
  AMB-9 fixed double-`start` (idempotent no-op returning existing url+token),
  but the symmetric edges are unspecified: `/dev-http stop` when no server is
  running (no-op success vs error) and what `/dev-http status` reports when
  stopped (`running? false` shape, absent url/token). AC-1 only says "`stop`
  cleanly halts it … no orphaned server on reload/restart"; it does not address
  stop/status against an already-stopped server. A planner needs the defined
  no-server-running behavior for both commands. Distinct from AMB-9 (start-side
  idempotency).
- AMB-13 The **persisted-route discovery/aggregation contract** is ambiguous.
  The design says persisted routes are "reitit route-data + handler namespaces
  under … `extensions/dev-http/dev/`" that are "reloadable", and integrant was
  chosen for "clean `halt!`/`init` reload ergonomics against the churny `dev/`
  routes" — but how the platform *collects* those route definitions into the
  reitit router at integrant init/reload is open to several materially different
  interpretations (a single conventional entry namespace/var exposing a route
  vector; auto-scanning all namespaces under `dev/` for a marker; an explicit
  dev-time `register`/`require` call). AC-2 ("a persisted route defined under
  `extensions/dev-http/dev/` is served") does not pin the discovery convention.
  A planner must define this contract before implementing the router/integrant
  `init` and the reload story. Distinct from session-route registry behavior
  (AMB-8) and route-id assignment (AMB-2).

### design-review · inconsistency (turn 3)

Inconsistency pass (¬correctness, ¬architecture, ¬ambiguity). Used in-context
design.md + architecture + AF/AMB notes. Two new actionable inconsistencies.
Port (AC-1 vs Lifecycle/O3) and SSE (slicing vs In-scope) inconsistencies are
already captured under AMB-5/AMB-6; not duplicated here.

- INC-1 Slice 1 demo route renders "something real (e.g. a benchmark table)"
  but the renderer set (incl. `:table`) is introduced in Slice 2; the slice-1
  example presupposes a later-slice mechanism.
- INC-2 `dev-present` is described as "safe, replay-friendly, model-driven" yet
  its target renderer set includes the `:hiccup` raw-HTML escape hatch and
  `:file` arbitrary-disk-file serving, contradicting the "safe" framing.

### design-review · inconsistency (round 2, turn 3)

Fresh inconsistency pass over the post-resolution design (INC-1/INC-2 already
resolved). Used in-context design.md + architecture sources + round-2
architecture (AF-4/AF-5) and ambiguity (AMB-7/8/9) replies. Focus: internal
contradictions, esp. resolution-introduced ones. Two new actionable
inconsistencies:

- INC-3 "Replay fidelity" says *only interaction-result mutations enter the log*
  and "Determinism boundary" puts the live server *outside the deterministic
  core*, but the AF-2 resolution projects `running?`/`url`/`token` into canonical
  `:state*` via a dispatch mutation — which enters the event log and writes
  non-deterministic per-launch values into canonical state. The doc contradicts
  itself on log membership / determinism. (Inconsistency facet; distinct from
  AF-4's secret-externality recommendation.)
- INC-4 The `:mermaid` renderer is described as "Mermaid (and/or Graphviz)", but
  O5 / Client-assets vendor only Vega-Lite + Mermaid JS (no Graphviz/viz.js
  asset), so Graphviz rendering is unsupported by the declared vendored assets.

### design-review · inconsistency (round 3, turn 3)

Fresh inconsistency pass over the post-resolution design (INC-1..4 resolved).
Used in-context design.md + architecture sources + round-3 architecture (AF-6)
and ambiguity (AMB-10/11) replies. INC-1..4 confirmed resolved. Two new
actionable inconsistencies:

- INC-5 `register-route!` is defined as **fn-based** (registers "an arbitrary
  ring handler fn"), yet is also called "the only path to the `:hiccup` and
  `:file` escape hatches", which are declarative **renderers** (AC-5 lists
  `:hiccup`/`:file` as renderers producing "the expected response for
  representative input"). A fn-only registration has no renderer-spec channel:
  either `register-route!` also accepts a declarative renderer spec (unstated),
  or `:hiccup`/`:file` are raw-handler idioms rather than selectable renderers.
  Internal contradiction across the Renderers section, the `register-route!`
  description, and AC-5. Distinct from INC-2 (dev-present "safe" framing).
- INC-6 AC-7 states "Access requires the per-launch token" (blanket), but the
  AMB-1 resolution / Lifecycle "Token transport" bullet exempts "Vendored static
  JS/CSS assets". AC-7's wording was not reconciled with the AMB-1 resolution
  (AMB-5 reworded AC-1 but AC-7 was untouched), so the acceptance criterion
  contradicts the token-gating scope it is meant to verify.

### design-review · follow-up (this pass)

Evidence rule applied. No previous design-follow-up exists; the review-batch
segment is the three contiguous review commits (`98080a4f0` architecture,
`2ea8cfdc4` ambiguity, `16bacb444` inconsistency). Baseline = parent of oldest
segment commit = task-creation `5e108158e`. `git diff 5e108158e..HEAD --
…/design-steps.md` shows the entire `design-steps.md` was added by the batch
(absent at baseline); all 11 items (AF-1..3, AMB-1..6, INC-1..2) are diff-added,
unchecked, still present → candidate work set = all 11. No predating/stale/steps
items in scope.

Verified two referenced precedents before editing: `:session/submit-synthetic-
user-prompt` exists as an internal handler riding the statechart turn path
(prompt-lifecycle.clj); nREPL status projection precedent is `[:runtime :nrepl]`
(context.clj / `:session/set-nrepl-runtime`). Extension-local `dev` extra-path is
consistent with the per-extension local-root precedent (work-on, mcp-tasks-run).

Resolutions written into design.md (and crystallized under "Design-review
follow-up resolutions"):

- AF-1 → persisted routes load from extension-local `extensions/dev-http/dev/`
  via the extension's own `:dev` extra-path (isolation preserved); updated Model,
  Dependencies, Scope, AC-2, Slicing.
- AF-2 → project `running?`/`url`/`token` into `:state*` via dispatch (nREPL
  precedent); integrant handle stays extension-local. Added Lifecycle bullet +
  Architectural constraint.
- AF-3 → first-class `psi.extension/*` dispatch-routed mutation in
  `:allowed-events` wraps the internal synthetic-user-prompt event; no
  back-door. Updated Interaction + constraints.
- AMB-1 → token via URL query param; gates HTML/choice-POST/`:file`; static
  vendored assets exempt.
- AMB-2 → optional caller-supplied id (replace on collision) else
  system-generated; one model for both registration paths.
- AMB-3 → mid-turn submit queued as next user turn (not reject/interrupt);
  "immediately" = no manual trigger.
- AMB-4 → `register-route!` takes explicit `:session-id`; `dev-present` defaults
  to invoking session; no target → presentation-only.
- AMB-5 → port not user-configurable; ephemeral OS-assigned (honors O3); AC-1
  reworded.
- AMB-6 → SSE out of scope; removed from slices, added to Out of scope.
- INC-1 → slice-1 demo uses platform-only hand-rolled handler output,
  independent of Slice 2 renderer set.
- INC-2 → `dev-present` restricted to safe declarative renderers; `:hiccup` /
  `:file` escape hatches via `register-route!` only.

All 11 design-steps marked done. No blocked/skipped items.

### design-review · follow-up (round 2, this pass)

Evidence rule applied. Previous design-follow-up completion = `9c4e18c7a`
(executed AF-1..3, AMB-1..6, INC-1..2). The contiguous latest review-batch
segment since then is the three round-2 review commits (`88755092e`
architecture, `67b8a615d` ambiguity, `5464c40df` inconsistency). Baseline =
parent of oldest segment commit (`88755092e^`) = `9c4e18c7a` (confirmed via
`git rev-parse`). `git diff 9c4e18c7a..HEAD -- …/design-steps.md` shows exactly
seven diff-added unchecked checklist items: AF-4, AF-5, AMB-7, AMB-8, AMB-9,
INC-3, INC-4 — all still present and unchecked at follow-up start → candidate
work set = those seven. No predating/stale/steps items in scope; the AF-1..3 /
AMB-1..6 / INC-1..2 lines are present in the diff only as unchanged context
(already `[x]`), correctly excluded.

Resolutions written into design.md (and added under "Design-review follow-up
resolutions"):

- AF-4 → **token kept external**. The per-launch token is a credential-class
  secret; per the OAuth credential-externality precedent it is NOT projected into
  canonical `:state*`. Only `running?`/`url` project via dispatch; the token
  stays in the extension-local handle and is surfaced live via `status`/log.
  Updated the Lifecycle status-projection bullet, the Architectural-constraints
  "Status projection, handle + secret externality" bullet, and the AF-2
  resolution entry.
- AF-5 → the `psi.extension/*` choice mutation's **pure handler emits a
  `:runtime/dispatch-event` follow-on effect** (effects-as-data) targeting
  `:session/submit-synthetic-user-prompt`, not an imperative in-handler dispatch
  (Dispatch sequencing contract). Updated the AF-3 Interaction-contract
  paragraph (now AF-3, AF-5).
- AMB-7 → choice option = `{:label, :value?}` (value defaults to label);
  single-select default with optional `:multi? true`; injected user message =
  selected `:value`(s) joined by `", "` for multi-select, optionally prefixed by
  a `:prompt`. New "Choice selection → user-message content" paragraph.
- AMB-8 → submit to an ended/closed target session is **dropped** (mutation
  no-ops; browser told "session no longer active"); the session-route registry is
  cleared on **server halt only**, not on agent-session end — "die with the
  server/session" disambiguated to "die with the server". New paragraph; also
  reworded the Session-routes model bullet.
- AMB-9 → `/dev-http start` is **idempotent**: already-running start is a no-op
  returning the existing url+token (no second server, no restart, no error); no
  `restart` command. New Lifecycle bullet.
- INC-3 → stated precisely that **exactly two mutation classes enter the log**
  (status projection `running?`/`url`; interaction results), all else is
  out-of-band; reconciled with AF-4 (token excluded from the log) and clarified
  the Determinism boundary covers the live handle, not the event-sourced status
  metadata. Rewrote the Replay-fidelity and Determinism-boundary constraints.
- INC-4 → dropped the `:mermaid` "(and/or Graphviz)" claim to match the vendored
  Vega-Lite + Mermaid asset set (O5). Updated the renderer description.

Cross-item coherence checks: AF-4 and INC-3 jointly settle log/state membership
(token never projected, never logged; url/running? projected and logged);
verified no stale "running?/url/token" projection wording remained (the Scope
"server-status projection" line stays generic and correct). All seven
design-steps marked done. No blocked/skipped items.

### design-review · follow-up (round 3, this pass)

Evidence rule applied. Previous design-follow-up completion = `1a541ff90`
(executed AF-4, AF-5, AMB-7, AMB-8, AMB-9, INC-3, INC-4). The contiguous latest
review-batch segment since then is the three round-3 review commits (`4b7f85ec9`
architecture, `6d16cc0ad` ambiguity, `373ac17a3` inconsistency). Baseline =
parent of the oldest segment commit (`4b7f85ec9^`) = `1a541ff90` (confirmed via
`git rev-parse`). `git diff 1a541ff90..HEAD -- …/design-steps.md` shows exactly
five diff-added unchecked checklist items: AF-6, AMB-10, AMB-11, INC-5, INC-6 —
all still present and unchecked at follow-up start → candidate work set = those
five. No predating/stale/steps items in scope; the prior `[x]` lines appear only
as unchanged diff context, correctly excluded.

Resolutions written into design.md (and added under "Design-review follow-up
resolutions"):

- AF-6 → the **status-projection mutation** (lifecycle `start`/`stop` projecting
  `running?`/`url` into `:state*`) is a **first-class `psi.extension/*`
  dispatch-routed mutation declared in `:allowed-events`** (e.g.
  `psi.extension/dev-http-set-status`), driven by the `/dev-http` command
  handler. The nREPL precedent governs only the projected *shape*; the
  core-owned `:session/set-nrepl-runtime` event is never dispatched by the
  extension. Updated the Lifecycle status-projection bullet, the
  Architectural-constraints status/externality bullet, and the
  untrusted-extension-posture bullet (now lists both first-class
  `psi.extension/*` mutations).
- AMB-10 → fixed per-renderer `dev-present` `:content` shapes: `:markdown` /
  `:mermaid` = string, `:vega` = Vega-Lite spec map, `:table` = canonical
  `{:headers [..] :rows [[..] ..]}` (single explicit shape, no detection),
  `:choices` = the AMB-7 choices spec. New "`dev-present` content-data shapes"
  paragraph under Renderers.
- AMB-11 → a `:choices` route is **single-shot**: first successful POST injects
  one user message and marks the route submitted; later POSTs no-op with "choice
  already submitted". A liveness-dropped submit (AMB-8) does not consume the
  shot; a fresh decision needs a new route. New "Repeat submission" paragraph
  under Interaction.
- INC-5 → reframed `:hiccup`/`:file` as **raw-handler render helpers** (functions
  a `register-route!` handler fn calls to build its ring response), **not**
  declarative `dev-present` renderer keywords. The declarative renderer set is
  `:markdown`/`:table`/`:vega`/`:mermaid`/`:choices`. Rewrote the Renderers
  section split, the `register-route!` description, the Scope in-scope line, and
  split AC-5 into declarative-renderers + render-helpers clauses.
- INC-6 → reworded AC-7 to require the token for **dynamic content routes** (HTML
  pages, choice POST, file serving) and to **exempt vendored static JS/CSS
  assets**, aligning the acceptance criterion with the AMB-1 token-gating scope.

Cross-item coherence checks: confirmed no remaining text calls `:hiccup`/`:file`
declarative renderers in a way that contradicts INC-5 (dev-present bullet,
Slicing slice 2, and resolution list all frame them as register-route!-only
escape hatches); confirmed AMB-10's `:choices` shape references the AMB-7 spec
without divergence; confirmed AF-6 ownership wording is consistent with the AF-3
choice-submit mutation posture (both first-class `psi.extension/*`, both in
`:allowed-events`, neither reaching a core projection event). All five
design-steps marked done. No blocked/skipped items.

### design-review · follow-up (round 4, this pass)

Evidence rule applied. Previous design-follow-up completion = `3f5cfed76`
(executed AF-6, AMB-10/11, INC-5/6). The contiguous latest review-batch segment
since then is the three round-4 review commits (`90cf9d0cd` architecture,
`724304ac9` ambiguity, `ecb21c82e` inconsistency). Baseline = parent of the
oldest segment commit (`90cf9d0cd^`) = `3f5cfed76` (confirmed). `git diff
3f5cfed76..HEAD -- …/design-steps.md` shows exactly five diff-added unchecked
checklist items: AF-7, AMB-12, AMB-13, INC-7, INC-8 — all still present and
unchecked at follow-up start → candidate work set = those five. No
predating/stale/steps items in scope; prior `[x]` lines appear only as unchanged
diff context, correctly excluded.

Verified precedents before editing: doc/architecture.md State-boundary table —
nREPL `[:runtime :nrepl]` and OAuth login status are **system-scoped** runtime
handles; doc/extension-api.md — slash-command implicit `(:mutate api)` is rebound
to the invoking session, with `:mutate-session` for explicit/background work.

Resolutions written into design.md (and added under "Design-review follow-up
resolutions"):

- AF-7 → status projection is **system/runtime-scoped** (singleton server;
  matches `[:runtime :nrepl]` / OAuth `system_scope`), dispatched so it lands in
  system/runtime scope — not the session-rebound implicit `:mutate` /
  `:mutate-session`. Explicit scope asymmetry: status = system-scoped,
  choice-submit = session-scoped. Updated the Lifecycle status-projection bullet
  and the Architectural-constraints status/externality bullet.
- AMB-12 → `/dev-http stop` when stopped = no-op success (no error); `/dev-http
  status` when stopped = `running? false`, no url/token (canonical status mirrors
  this). New Lifecycle bullet, symmetric with AMB-9.
- AMB-13 → persisted routes collected via a **single conventional entry var**
  (e.g. `dev-http.routes/routes`) returning a reitit route-data vector,
  required/rebuilt at integrant init/reload; no auto-scan/marker. New
  "Persisted-route discovery contract" paragraph; AC-2 reworded.
- INC-7 → added a third raw-handler **choices interaction helper** (beside the
  hiccup/file escape-hatch helpers) emitting a platform-wired choice form bound to
  the route's `:session-id` (AMB-4); reuses the platform choice-POST + mutation +
  single-shot/liveness machinery. `:choices` now reachable from both registration
  paths; `register-route!`'s `:session-id` no longer vestigial. Updated the
  Renderers helper list + framing, the `register-route!` description, the AMB-4
  Interaction paragraph, AC-5, and the Scope in-scope line.
- INC-8 → distinguished the **token-less base `url`** (projected into `:state*` /
  event-log per AF-4/INC-3) from the **token-embedded copy-pasteable URL**
  (reconstructed at render time from base + external token; shown only in
  `status`/dev log line). Updated the AMB-1 token-transport bullet, the Server
  bullet, the status-projection bullet, and the INC-3 log-membership constraint.

Cross-item coherence checks: AF-7 + INC-8 jointly keep canonical/system-scoped
projection token-less (no secret, no session-scope drift); INC-7's choices helper
revises INC-5's "two helpers" to "two escape-hatch + one interaction helper"
without contradicting INC-5 (escape hatches stay hiccup/file, register-route!-only)
and is consistent with AMB-4's `:session-id`; AMB-12 is symmetric with the AMB-9
idempotent-start resolution; AMB-13's single-entry-var contract leaves AMB-8
session-registry behavior and AMB-2 route-id assignment untouched. All five
design-steps marked done. No blocked/skipped items.

### design-review · follow-up (round 5, this pass)

Evidence rule applied. Previous design-follow-up completion = `1ae973438`
(executed round-4 follow-ups AF-7, AMB-12/13, INC-7/8). The contiguous latest
review-batch segment since then is the three round-5 review commits (`1d5011aa9`
architecture-fit, `2a7c7c5dd` ambiguity, `c107d8c41` inconsistency). Baseline =
parent of the oldest segment commit (`1d5011aa9^`) = `1ae973438` (confirmed via
`git rev-parse`). `git diff 1ae973438..HEAD -- …/design-steps.md` shows exactly
four diff-added unchecked checklist items: AF-8, AMB-14, AMB-15, INC-9 — all
still present and unchecked at follow-up start → candidate work set = those four.
No predating/stale/steps items in scope; prior `[x]` lines appear only as
unchanged diff context, correctly excluded.

Verified precedents before editing: META.md lines 24–25 (managed-services
principle: "psi runtime owns process-scoped managed services on ctx … keyed by
logical identity … rather than extension-local hidden state"); doc/extensions.md
"Managed services" surface (`:ensure-service`/`:stop-service`/`:service-request`,
`:type :subprocess`, protocol-agnostic lifecycle, psi-as-client transport).

Resolutions written into design.md (and added under "Design-review follow-up
resolutions"):

- AF-8 → the live integrant system/server/registry handle is a **runtime-owned
  managed handle on `ctx` keyed by logical identity** (e.g. `:dev-http/server`),
  not extension-local hidden state and not the core `:state*` atom — reconciling
  the live-handle location with the managed-services principle and the
  process-wide-singleton runtime-handle precedents (nREPL `[:runtime :nrepl]`,
  project-nrepl registry) that AF-7's system-scoping points to. integrant keeps
  the in-extension `init`/`halt!` lifecycle under that handle; holding it on
  `ctx` keyed by logical identity makes it survive extension reload and forbids
  orphan/duplicate (reinforcing AC-1 — the exact failure the principle prevents).
  Token stays external (AF-4) alongside the handle. Noted that the managed-service
  *transport* surface (`:type :subprocess` request/response) is psi-as-client
  subprocess RPC and is **not** adopted for an inbound in-process HTTP host; AF-8
  adopts the ownership/location principle only (the concrete ctx-handle mechanism
  is a planning detail). Updated the Lifecycle Boundary bullet (split into
  scoped-integrant-code + a new "Live server-handle location (AF-8)" bullet), the
  status-projection bullet, and the Architectural-constraints handle-externality
  bullet (all "extension's own atom/system" framings now read runtime-owned-on-ctx).
- AMB-14 → registration-call return URL form follows the
  journaled-vs-non-journaled principle: `dev-present` (journaled tool result)
  returns the **token-less base URL** (developer gets an openable link via
  `/dev-http status`); `register-route!` (non-journaled REPL return) returns the
  **token-embedded copy-pasteable URL** directly. New "Registration-call return
  URL form (AMB-14)" paragraph under Registration; AC-3 reworded to the token-less
  base URL.
- AMB-15 → empty/no-selection `:choices` submit (zero options) is **rejected as a
  no-op**: nothing injected (no `:prompt`-only message), browser told "no
  selection", AMB-11 single shot **not consumed** (route stays live); single-select
  radios render with no default-checked option. Mirrors AMB-8's
  drop-doesn't-consume; the shot is reserved for a genuine decision. New
  "Empty / no-selection submit (AMB-15)" paragraph under Interaction; AC-6
  augmented.
- INC-9 → the idempotent `/dev-http start` return is a **non-journaled human-facing
  command-output surface** (same class as `status` output / log line), so it
  carries the token-embedded copy-pasteable URL legitimately. Replaced INC-8's
  closed "only status + log line" enumeration with the **journaled-vs-non-journaled
  principle**, under which the `start` return and the `register-route!` REPL return
  (AMB-14) are admitted non-journaled token-embedded surfaces. Rewrote the Token-
  transport bullet into the principle, updated the AMB-9 Double-`start` bullet and
  its resolution entry, and updated the INC-8 resolution entry.

Cross-item coherence checks: INC-8/INC-9/AMB-14 now share one
journaled-vs-non-journaled principle (token-less in `:state*`/log/`dev-present`
result; token-embedded in status/log-line/`start`-return/`register-route!`-return)
— no fixed-list/closed-enumeration contradiction remains; verified the AF-4/INC-3
token-never-in-replayable-state invariant still holds under every enumerated
surface. AF-8 keeps the handle out of the core `:state*` atom (AF-2/AF-4 "never in
core state atom" preserved) while changing its location from extension-private to
runtime-owned-on-ctx; the `mcp-tasks-run`/`work-on` precedent is superseded for
this singleton (those are session/extension-scoped; AF-7 made dev-http a
system-scoped singleton). AMB-15 is symmetric with AMB-8 (non-decision does not
burn the single shot) and consistent with AMB-7/AMB-11. All four design-steps
marked done. No blocked/skipped items.

### design-review · architecture (round 6, turn 1)

Fresh architecture-fit pass over the fully-resolved design (AF-1..8 all
resolved). Sources: AGENTS.md (VSM S1–S3, capability gating, `system_scope`),
META.md (managed-services on ctx; interactive-projection invalidation vs
polling), doc/architecture.md (State-boundary runtime-handles table; Dispatch
sequencing contract `:runtime/dispatch-event`), doc/extensions.md (managed-service
surface `:ensure-service`/`:type :subprocess`), doc/extension-api.md
(`:query`/`:mutate` ambient vs `:query-session`/`:mutate-session` explicit;
`psi.extension/*` ext-scoped mutate). AF-1..8 re-confirmed genuinely resolved and
precedent-accurate: extension-local `dev/` (AF-1); status projection of
`running?`/token-less base url only (AF-2/AF-4); first-class `psi.extension/*`
dispatch-routed mutations in `:allowed-events` for both choice-submit (AF-3) and
status-projection (AF-6); effects-as-data follow-on for the synthetic-user-prompt
(AF-5); system-scoped status vs session-scoped choice-submit (AF-7); live handle
as runtime-owned managed handle on `ctx` keyed by logical identity (AF-8).

Candidate angles checked and dismissed (not new actionable misfits):
- Off-thread HTTP-handler → `:mutate-session` is precedented by the scheduler's
  delayed-prompt path; AMB-3 statechart turn admission governs it.
- `dev-present` tool capability + `:allowed-events` gating is the standard
  extension capability surface, already covered by the minimal-surface constraint
  and AF-3/AF-6.
- Projection-invalidation effects (META.md: interactive projections use semantic
  invalidation, not polling): dev-http status is **pull-based introspection
  state** modeled explicitly on the `[:runtime :nrepl]` endpoint-metadata
  precedent (queried via `/dev-http status` + EQL), not an interactive UI
  projection with subscribers. The mirrored nREPL precedent is itself pull-only
  and emits no interactive-projection invalidation, so consistency requires none
  here.

Conclusion: **no new actionable architectural-fit misfit.** The design's
architectural fit is complete across the VSM layers and the State/Dispatch/
managed-services boundaries. No design-steps added this pass.

### design-review · ambiguity (round 6, turn 2)

Fresh ambiguity pass using in-context design.md + architecture sources +
round-6 architecture reply (no new architecture misfit). AMB-1..15 re-confirmed
resolved. One new actionable ambiguity:

- AMB-16 **Registration-call behavior when the server is not running** is
  undefined. AMB-9 fixed double-`start` and AMB-12 fixed `/dev-http stop`/`status`
  against a stopped server, but both are *lifecycle commands*. The *registration
  calls* — `dev-present` (AC-3) and `register-route!` (AC-4) — both return a route
  URL, which requires the running server's ephemeral-port base URL and a live
  registry (which per AF-8 lives in the integrant system on `ctx`, present only
  while the server runs). Yet both registration surfaces are registered at
  extension load, independent of the `/dev-http start` lifecycle, so each is
  invokable while the server is stopped. The behavior is unspecified across
  several materially different interpretations: error with a clear "start the
  server first" message; auto-start the server then register; register into a
  pre-server registry and lazily bind on start; or make the `dev-present`
  capability/`register-route!` unavailable while stopped. A planner needs this
  defined for the `dev-present` tool handler and `register-route!` fn, and for
  AC-3/AC-4 tests. Distinct from AMB-9 (start-command idempotency) and AMB-12
  (stop/status-command edges) — AMB-16 is the *registration-call* not-running edge.

### design-review · inconsistency (round 6, turn 3)

Fresh inconsistency pass using in-context design.md + architecture sources +
round-6 architecture (no new misfit) and ambiguity (AMB-16) replies. INC-1..9
re-confirmed resolved; AMB-16 is an ambiguity item, not duplicated here. One new
actionable inconsistency (resolution-introduced, across sections):

- INC-10 **No-op choice submit: dispatched-and-logged vs out-of-band.** The
  round-5 AMB-8 (dead target) and AMB-15 (empty selection) resolutions describe a
  no-message submit as "**the wrapping mutation no-ops** — nothing is injected" /
  "rejected as a no-op that injects nothing" — wording that has the
  `psi.extension/*` wrapping mutation *dispatched* (running and no-oping). But
  INC-3 states **exactly two** mutation classes enter the event log — class (2)
  being "interaction-result mutations (choice submits → **user message**)" — and
  "everything else ... is presentation/out-of-band and **excluded from the log**."
  A dropped/empty submit produces no user message, so it is not class (2); yet
  per doc/architecture.md the dispatch journal keeps **one summarized entry per
  dispatch** (including `blocked?`/no-op dispatches), so a *dispatched* wrapping
  mutation cannot be "excluded from the log." The two readings conflict: either
  (a) the no-op submit is short-circuited by a pre-dispatch guard in the HTTP
  handler before any wrapping mutation is dispatched (nothing event-sourced —
  consistent with INC-3, but then AMB-8/AMB-15's "the wrapping mutation no-ops"
  mis-describes it as a mutation run), or (b) the wrapping mutation is dispatched
  and no-ops (necessarily logged — contradicting INC-3's "only message-producing
  interaction results enter the log"). The design must reconcile whether
  dropped/empty choice submits are event-sourced. Distinct from AMB-8 (liveness
  behavior), AMB-15 (empty-selection behavior), and INC-3 (which classes enter
  the log) — INC-10 is the unreconciled no-op-submit log-membership/dispatch
  point.

### design-review · round-6 follow-up execution (AMB-16, INC-10)

Batch baseline established via the evidence rule: oldest round-6 review commit is
`1d7da3cc8` (architecture-fit round 6); its parent `078b90296` (execute round-5
follow-ups) is the batch baseline. `git diff 078b90296..HEAD -- design-steps.md`
added exactly two unchecked items — AMB-16 (ambiguity round 6) and INC-10
(inconsistency round 6); architecture-fit round 6 added no actionable item. Both
were still unchecked at follow-up start and are the entire candidate work set.

- **AMB-16 — registration when the server is not running.** Resolved to
  **error, no implicit auto-start, no pre-server staging registry**. Rationale:
  auto-start conflicts with the explicit `/dev-http start` command surface and
  AMB-9 idempotency; a pre-server registry violates AF-8 ("no live registry off
  `ctx` when stopped") and AMB-8 ("registry = server lifetime"). Both
  registration calls require a running server (URL is formed from the
  ephemeral-port base; registry lives on the `ctx`-held integrant system). While
  stopped, `dev-present` returns an error tool-result and `register-route!`
  raises/returns an error value, both naming the remedy ("start the server
  first"); nothing registered, no URL returned. Added a Registration-section body
  paragraph, a Resolved-decisions entry, and not-running notes on AC-3/AC-4.

- **INC-10 — no-op choice-submit log membership / dispatch.** Resolved to
  **option (i): a pre-dispatch guard in the HTTP choice-POST handler**. The
  handler reads target-session liveness (`:query-session`) and registry-entry
  selection/single-shot flags, and dispatches the wrapping `psi.extension/*`
  choice-submit mutation **only** for a genuine, live-target, non-empty,
  first-shot selection. Dropped (AMB-8), empty (AMB-15), and already-submitted
  (AMB-11) POSTs are short-circuited before any dispatch — no wrapping mutation
  is dispatched, so no no-op choice mutation is event-sourced or recorded in the
  dispatch journal. This keeps INC-3 class (2) cleanly "message-producing
  interaction-result mutations only" (no class-(2) amendment). Corrected
  AMB-8/AMB-11/AMB-15 wording away from "the wrapping mutation no-ops" to
  "short-circuited by a pre-dispatch handler guard"; tightened the INC-3
  constraint paragraph; added a Resolved-decisions entry.

Both design-steps items marked done. No blocked/skipped items.

### design-review · architecture (round 7, turn 1)

Fresh architecture-fit pass over the fully-resolved design (AF-1..8 resolved;
round-6 architecture concluded no new misfit). Sources: AGENTS.md (S2
`system_scope(¬agent_session_scope)`, capability gating), META.md
(managed-services on ctx; core owns canonical projection models), doc/
architecture.md (State-boundary runtime-handles table — all listed system/runtime
projections are core-owned; Dispatch sequencing contract `:runtime/dispatch-event`),
doc/extension-api.md + doc/extensions.md (extension mutate surfaces). AF-1..8
re-confirmed genuinely resolved and precedent-accurate. One new actionable misfit:

- AF-9 **AF-7's required system-scoped status projection has no located
  extension-API surface.** AF-7 decided the singleton server's status projects
  into **system/runtime-scoped** canonical state and explicitly excludes both the
  `/dev-http` slash-command's session-rebound implicit `(:mutate api)` *and*
  `:mutate-session` as session-scoped — but it never names the concrete surface
  that *realizes* a system-scoped projection. The documented extension mutate
  surfaces are **only** session-scoped: `(:mutate api)` is rebound to the
  invoking session inside a slash-command handler (doc/extension-api.md), and
  `(:mutate-session api)` is explicit-session. The cited `[:runtime :nrepl]` /
  OAuth-login-status precedents are **core-owned** projections (dispatched by core
  events like `:session/set-nrepl-runtime`, not reached through the extension
  API); doc/extensions.md states runtime-scoped canonical data is core-owned. So
  the design requires a system-scoped projection from an untrusted extension but
  the only documented extension dispatch paths land in session scope — the
  AF-3-class gap ("in scope but not located on the extension-API contract"),
  unresolved for the status-projection surface. AF-6 fixed event *ownership*
  (first-class `psi.extension/*` in `:allowed-events`) and AF-7 fixed the scope
  *decision* (system vs session), but neither locates the *mechanism*: how a
  `psi.extension/dev-http-set-status` mutation, triggered from the
  session-rebound `/dev-http` command handler, escapes session scope to write a
  system/runtime-scoped `:state*` key. Conforming choice: either (a) identify/
  define a system-scoped extension dispatch surface (a contract addition, as
  AF-3/AF-6 added first-class mutations) under which the `psi.extension/*`
  status mutation's pure handler writes a system/runtime-scoped key independent
  of any invoking session, or (b) confirm and document that a dispatch-routed
  `psi.extension/*` mutation's pure handler can write the system/runtime-scoped
  key directly regardless of the session-rebound triggering surface — making
  AF-7's "system-scoped, not session-rebound" decision realizable on the
  extension-API contract. Distinct from AF-6 (event ownership) and AF-7 (the
  scope decision); AF-9 is the unlocated system-scoped projection *surface/
  mechanism*.

### design-review · ambiguity (round 7, turn 2)

Fresh ambiguity pass using in-context design.md + architecture sources +
round-7 architecture reply (AF-9, an architecture item — not duplicated here).
AMB-1..16 re-confirmed resolved. Two new actionable ambiguities:

- AMB-17 **Concurrent first-shot choice-submission atomicity / where the
  single-shot `submitted` flag is set** is undefined. AMB-11 makes a `:choices`
  route single-shot and INC-10 adds a *pre-dispatch* guard in the HTTP
  choice-POST handler that "reads the registry-entry submitted flag" and
  dispatches the wrapping mutation only for a first-shot selection — but the
  design never says *when/where the flag flips to submitted* (in the HTTP handler
  before dispatch, or inside the dispatched `psi.extension/*` mutation, which
  serializes through dispatch) nor how the read-check-mark is made atomic. Under
  two simultaneous valid first-shot POSTs, both can pass the pre-dispatch
  flag-read before either marks the route submitted (a TOCTOU window), so the
  "at most one user message per choice route" guarantee (AMB-11/AC-6) is
  unspecified under concurrency — exactly the class of race that task 224's
  at-most-once funnel had to make atomic. A planner needs the defined mechanism:
  e.g. the authoritative single-shot mark happens inside the dispatch-serialized
  mutation (the pre-dispatch guard is a best-effort fast path that may admit a
  duplicate dispatch, with the mutation re-checking and no-oping the second), or
  the registry-entry mark is a handler-level compare-and-set, etc. Distinct from
  AMB-11 (sequential repeat), AMB-3 (mid-turn timing), AMB-8 (target liveness),
  and AMB-15 (empty selection) — AMB-17 is the *concurrent* first-shot
  atomicity/flag-set-point gap.

- AMB-18 **Token-enforcement boundary for `register-route!` raw-handler routes
  and persisted `dev/` routes** is undefined. AMB-1/INC-6 say the token gates
  "all dynamic content routes — HTML page routes, the choice POST endpoint, and
  `:file` serving" and exempts vendored static assets, but the enforcement is
  enumerated by *content category*, not by *route boundary*. A `register-route!`
  raw handler fn emits arbitrary ring responses the platform cannot classify a
  priori, and persisted `dev/` routes are full-power handlers too; the design
  does not state whether token validation is applied as uniform platform
  middleware over the whole `/s/:route-id` session-route subtree and the
  persisted `dev/` route subtree (so raw/persisted handlers are auto-gated and
  never see an untokened request) or is the individual handler's responsibility
  (so a raw handler could accidentally serve untokened dynamic content). AC-7 and
  AC-8 do not pin which layer enforces the token. A planner needs the defined
  enforcement point (recommended: platform middleware gating the dynamic-route
  subtrees uniformly, with the static-asset path exempt) for the router build and
  the AC-7 tests. Distinct from AMB-1 (token *transport*) and INC-6 (static-asset
  *exemption wording*) — AMB-18 is the *enforcement-layer/responsibility* gap.

### design-review · inconsistency (round 7, turn 3)

Fresh inconsistency pass using in-context design.md + architecture sources +
round-7 architecture (AF-9) and ambiguity (AMB-17/18) replies; AF-9 / AMB-17 /
AMB-18 are architecture/ambiguity items, not duplicated here. INC-1..10
re-confirmed resolved; verified the round-6 INC-10 rewording is consistent
across AMB-8/AMB-11/AMB-15 (no leftover "the wrapping mutation no-ops" except the
correction note quoting old wording). One new actionable inconsistency:

- INC-11 **Slicing vs the "vertical, behaviour-first" principle and the
  Capability surface: slice-1 ships an unexercisable mechanism and
  `register-route!` has no slice.** The Slicing section header asserts "vertical,
  behaviour-first," but **slice 1** bundles the **session-route registry +
  `/s/:route-id` dispatch** mechanism while its only slice-1 behaviour is a
  **persisted** demo route — and a persisted `dev/` route never touches the
  session-route registry (registry routes are reached through `/s/:route-id`, and
  the slice-1 demo uses platform-only hand-rolled handler output independent of
  registration). No session-route **registration surface** exists until slice 2
  (`dev-present`), so slice 1's session-route registry/dispatch is delivered with
  nothing to populate or exercise it — a horizontal (mechanism-without-behaviour)
  slice, contradicting the section's behaviour-first claim. Separately,
  **`register-route!`** — a top-level Capability-surface and Scope deliverable
  ("the `dev-present` tool + `register-route!` REPL fn") together with its
  hiccup/file raw-handler escape-hatch helpers — is **assigned to no slice**:
  slice 2 only references it obliquely ("escape hatches reachable via
  `register-route!` only") without listing it as a deliverable, and slice 3 adds
  only the choices helper. The Slicing section is therefore internally
  inconsistent with both the behaviour-first principle (slice-1 unexercisable
  registry mechanism) and the Capability surface (`register-route!` unsliced).
  Reconcile by either moving the session-route registry/dispatch (and a minimal
  registration surface) into the slice that first delivers a session-route
  behaviour, or explicitly assigning `register-route!` (and its hiccup/file
  helpers) to a slice and noting that slice 1's registry/dispatch is exercised by
  that registration surface. Distinct from INC-1 (slice-1 demo-output example vs
  renderer-set ordering).

### design-review · round-7 follow-up execution (AF-9, AMB-17, AMB-18, INC-11)

Batch baseline established via the evidence rule: the contiguous latest
review-batch segment since the previous design-follow-up (`4b901e128`, execute
round-6 follow-ups) is the round-7 batch — `9c4f2bf99` (architecture-fit r7),
`e62679080` (ambiguity r7), `d5e5c116f` (inconsistency r7). The parent of the
oldest segment commit (`9c4f2bf99^`) is `4b901e128` (the round-6 follow-up
commit itself), so that is the batch baseline. `git diff 4b901e128..HEAD --
design-steps.md` added exactly four unchecked items — AF-9, AMB-17, AMB-18,
INC-11 — all still unchecked at follow-up start. They are the entire candidate
work set. No predating/edited/checked/steps.md items included.

- **AF-9 — system-scoped status-projection surface.** AF-7 requires the
  singleton's status in system/runtime scope, but the documented extension
  mutate surfaces (`(:mutate api)` session-rebound in slash-command handlers;
  `(:mutate-session api)` explicit-session) are both session-scoped, so AF-7's
  "dispatched, not session-rebound" decision lacked a realizing surface.
  Resolved: dispatch the first-class `psi.extension/dev-http-set-status` event
  through a **non-session-rebound, system-scoped dispatch path** (explicit
  extension-API contract addition, AF-3/AF-6 lineage, carrying no invoking
  session-id) whose pure handler writes the system/runtime-scoped
  `[:runtime :dev-http]` `:state*` key directly — not the rebound `(:mutate api)`
  nor `(:mutate-session api)`. Keeps the AF-6 posture (no reach into the
  core-owned `:session/set-nrepl-runtime`). Added a Status-projection bullet, an
  architectural-constraints note, and a resolved-decision.

- **AMB-17 — concurrent first-shot atomicity / single-shot mark location.**
  Resolved via task 224's at-most-once funnel: the **authoritative single-shot
  mark is set inside the dispatch-serialized choice-submit mutation's pure
  handler**, not the HTTP handler; the submitted state is a **canonical `:state*`
  flag** (feedback-session-scoped submitted-route-id set), so atomicity is from
  dispatch serialization (no test-and-set). Handler: absent → add id + emit the
  `:runtime/dispatch-event` follow-on (both-or-neither, AF-5); present → no-op.
  The INC-10 pre-dispatch HTTP guard becomes a **best-effort fast path** for the
  deterministically-known no-ops; a concurrent first-shot race may admit two
  dispatches but only the first produces a message (at most one user message per
  route, AMB-11/AC-6). Reconciled INC-3's "no no-op is ever event-sourced" by
  admitting the rare race-loser as the one journaled no-op (task-224 precedent),
  while keeping all deterministically-known no-ops short-circuited pre-dispatch.
  Added an Interaction-section paragraph, an INC-3 constraint clause, an INC-10
  refinement note, and a resolved-decision.

- **AMB-18 — token-enforcement boundary.** Resolved to **uniform platform
  middleware** over the dynamic-route subtrees (the `/s/:route-id` session-route
  subtree and the persisted `dev/` route subtree), so `register-route!` raw
  handlers and persisted `dev/` handlers are auto-gated and never see an
  untokened request; the vendored static-asset subtree is the sole exempt path.
  Enforcement is the platform's, not the individual handler's — so a priori
  classification of an opaque handler's output is never required. Added a
  Token-transport bullet, strengthened AC-7, and added a resolved-decision.

- **INC-11 — slicing behaviour-first + `register-route!` sliced.** Moved the
  **session-route registry + `/s/:route-id` dispatch** out of slice 1 (where it
  was an unexercisable mechanism) into **slice 2**, introduced together with its
  first registration surfaces; assigned **`register-route!`** (+ hiccup/file
  escape-hatch helpers) explicitly to slice 2 alongside `dev-present`, with the
  choices helper in slice 3. Every slice now delivers an exercisable end-to-end
  behaviour. Rewrote the Slicing section, added a behaviour-first preamble, and
  added a resolved-decision.

All four design-steps items marked done. No blocked/skipped items.

### design-review · architecture (round 8, turn 1)

Fresh architecture-fit pass over the fully-resolved design (AF-1..9 resolved;
round-6 found no new misfit, round-7 found AF-9). Sources re-consulted: AGENTS.md
(VSM S1–S3, `system_scope`, shims/adapters one-way), META.md (managed-services on
ctx keyed by logical identity, *reused within ctx rather than extension-local
hidden state*; core owns canonical projections), doc/architecture.md
(State-boundary runtime-handles table — every listed ctx handle is core/runtime-
placed; Dispatch sequencing `:runtime/dispatch-event`), doc/extensions.md
(managed-service surface: `:ensure-service`/`:stop-service`/`:service-request`,
documented `:type :subprocess`; guidance "do not expand the generic
managed-service core with protocol-specific behavior … prefer integration-local
adapters"), doc/extension-api.md (only session-scoped `(:mutate api)` /
`:mutate-session`).

Angles checked and dismissed (not new actionable misfits):
- AF-9 system-scoped status-projection surface — resolved (non-session-rebound
  dispatch path writing `[:runtime :dev-http]`); precedent-accurate.
- Choice-submit follow-on `:runtime/dispatch-event` → core `:session/submit-
  synthetic-user-prompt` — covered by AF-3/AF-5 (declared first-class wrapping
  mutation, effects-as-data, runtime-executed follow-on); not extension-gated
  reach. No new misfit.
- Pull-based status (no projection-invalidation effects) — round-6 dismissal
  holds; mirrors pull-only `[:runtime :nrepl]` precedent.
- HTTP-handler reads via `:query-session`, writes via dispatched mutation —
  conforms to reads-via-resolvers / writes-via-mutations.

One new actionable architectural-fit misfit:

- AF-10 **AF-8's runtime-owned-on-`ctx` live handle has no located extension-API
  ownership surface** — symmetric to the AF-9 gap, but for the *handle* not the
  *status projection*. AF-8 decided the live integrant system/server/registry
  handle is a runtime-owned managed handle on `ctx` keyed by logical identity —
  explicitly **not** extension-local hidden state and **not** core `:state*` —
  while the Lifecycle Boundary constraint forbids any **core** namespace gaining
  dev-http-specific integrant code (integrant `init`/`halt!` lives in the
  extension). But the only documented extension-API runtime-ctx-handle surface is
  the managed-service `:ensure-service`/`:stop-service` lifecycle, whose
  documented `:type :subprocess` hosts a **runtime-owned subprocess** lifecycle
  (runtime spawns/kills the process) and whose transport surface the design
  itself rejects as psi-as-client and unfit. dev-http instead needs a runtime
  ctx handle whose **start/stop lifecycle is extension-provided in-process
  integrant `init`/`halt!`** — a shape no documented extension surface realizes,
  and which doc/extensions.md's guidance ("do not expand the generic
  managed-service core … prefer integration-local adapters") makes a consequential
  contract choice, not a free implementation detail. AF-8 consciously defers the
  realizing mechanism ("whether via `:ensure-service` with a non-subprocess type
  or a narrower runtime ctx-handle mechanism is a planning/implementation
  detail"), but by the AF-9 standard already applied in this task — locate the
  concrete extension-API surface when the documented surfaces don't realize the
  decision — the surface must be pinned at design time: AF-8's
  runtime-owned-on-`ctx` + extension-owned-in-process-lifecycle + no-core-dev-http-
  code + no-extension-local-hidden-state quadrilemma is otherwise unrealizable on
  the documented contract (any realization collapses into extension-local hidden
  state, which AF-8 forbids, or core dev-http code, which the Boundary forbids).
  Conforming choice: (a) define/identify a generic **non-subprocess managed-handle
  lifecycle type** whose runtime-owned `:ensure-service`/`:stop-service` delegates
  start/stop to the extension-provided in-process `init`/`halt!` (a contract
  addition in the AF-3/AF-6/AF-9 lineage), or (b) confirm and document that a
  narrower runtime ctx-handle extension surface already hosts an
  extension-provided-lifecycle handle keyed by logical identity, or (c) justify an
  alternative host that still satisfies all four AF-8/Boundary constraints.
  Distinct from AF-8 (the location/ownership decision) and AF-9 (the
  status-*projection* surface) — AF-10 is the unlocated live-*handle* ownership
  surface/mechanism.

### design-review · ambiguity (round 8, turn 2)

Fresh ambiguity pass using the loaded design.md + architecture sources + the
round-8 architecture reply (AF-10, an architecture item — not duplicated here).
Targeted re-reads only (grep for token-rejection / 404 / malformed-content /
error-tool-result wording). AMB-1..18 re-confirmed resolved. One new actionable
ambiguity:

- AMB-19 **Token-enforcement rejection response is undefined.** AMB-1 fixed token
  *transport* (query param) and AMB-18 fixed the enforcement *layer* (uniform
  platform middleware over the dynamic-route subtrees, so raw/persisted handlers
  "never see an untokened request"), but neither defines **what a request with a
  missing or invalid token actually receives** from that middleware. The design
  pins every other browser-facing response precisely — AMB-8 "session no longer
  active", AMB-11 "choice already submitted", AMB-15 "no selection" — yet the
  token-failure response is unspecified across materially different
  interpretations: `404 Not Found` (hide route existence), `401 Unauthorized`,
  `403 Forbidden`, a `400`/`200` plain "missing or invalid token" page, or a
  redirect. The choice matters behaviourally (404 hides that a route exists;
  401/403 reveals it) and is exactly what the AC-7 token-enforcement tests must
  assert (token present → served; token missing/invalid → *what?*). A planner
  needs the rejection response (status + body) pinned for the token middleware and
  the AC-7 tests. Applies uniformly to the gated dynamic routes — HTML page
  routes, the choice POST endpoint, and `:file` serving (the static-asset subtree
  is exempt, AMB-18). Distinct from AMB-1 (transport), AMB-18 (enforcement layer),
  and INC-6 (static-asset exemption wording) — AMB-19 is the token-rejection
  *response* gap.

### design-review · inconsistency (round 8, turn 3)

Fresh inconsistency pass using the loaded design.md + architecture sources + the
round-8 architecture (AF-10) and ambiguity (AMB-19) replies (architecture/
ambiguity items, not duplicated here). Targeted re-reads: the INC-3
log-membership paragraph, the AF-7/AF-9 status-scope bullets, and
doc/architecture.md's State-boundary + dispatch-log description. INC-1..11
re-confirmed resolved. One new actionable inconsistency:

- INC-12 **INC-3's "both mutation classes enter the log" conflates two different
  scopes/journals after the AF-7/AF-9 scope split.** INC-3 ("Replay fidelity /
  log membership") states **exactly two** dev-http mutation classes are
  event-sourced and "enter **the log**" — (1) status-projection mutations and
  (2) message-producing choice submits — framing both as members of one
  replayable journal (it even says the class-(1) token-less base `url` "enters the
  log" and that "nREPL endpoint metadata is likewise ... in the log"), and ties
  the whole paragraph to **replay fidelity**. But AF-7/AF-9 make class (1)
  **system/runtime-scoped**, dispatched on a **non-session-rebound path carrying
  no invoking session-id**, landing in `[:runtime :dev-http]` — explicitly the
  same scope as the system-scoped `[:runtime :nrepl]` / OAuth precedents — while
  class (2) is **session-scoped** (`:mutate-session` against the AMB-4 feedback
  session, line 476). The architecture's dispatch event-log / trace is
  agent-session-owned and per-session-replayable ("replay suppresses effects"),
  and the cited system-scoped nREPL/OAuth projections are `system_scope
  (¬agent_session_scope)` — i.e. **not** members of any one session's replayable
  conversation log the way the session-scoped injected user message (class 2)
  must be. So INC-3's single undifferentiated "the log" is internally
  inconsistent with AF-7/AF-9: a no-invoking-session-id system-scoped status
  projection and a session-scoped, replay-critical choice submit do **not** enter
  the same per-session replayable journal, yet INC-3 lists them together as the
  two classes that "enter the log" under one "replay fidelity" frame. Reconcile by
  distinguishing the journals/scopes: class (2) session-scoped → the feedback
  session's replayable event-log (replay-relevant for that conversation); class
  (1) system-scoped → the system/runtime dispatch record (nREPL/OAuth precedent),
  which is not a per-session conversation-replay member — and adjust INC-3's
  "replay fidelity / the log" framing (and the "nREPL endpoint metadata is
  likewise in the log" claim) to name **which** log/scope each class enters.
  Distinct from INC-3 (which classes are event-sourced, pre-scope-split), AF-7
  (the scope decision), and AF-9 (the realizing surface) — INC-12 is the
  unreconciled log-membership-vs-scope conflation INC-3 now carries.

### design-review · round-8 follow-up execution (AF-10, AMB-19, INC-12)

Evidence rule applied. Previous design-follow-up completion = `c5618bbf9`
(execute round-7 follow-ups AF-9, AMB-17, AMB-18, INC-11). The contiguous latest
review-batch segment since then is the round-8 batch — `c564c4eef`
(architecture-fit r8, AF-10), `1d2ba4f13` (ambiguity r8, AMB-19), `b2023601b`
(inconsistency r8, INC-12). Parent of the oldest segment commit (`c564c4eef^`) =
`c5618bbf9` (the round-7 follow-up commit), confirmed via `git rev-parse`, so
that is the batch baseline. `git diff c5618bbf9..HEAD -- design-steps.md` added
exactly three unchecked checklist items — AF-10, AMB-19, INC-12 — all still
unchecked at follow-up start and the entire candidate work set. Prior `[x]`
lines appear only as unchanged diff context, correctly excluded; no
predating/edited/stale/steps.md items in scope.

Verified precedents before editing: doc/architecture.md State-boundary table
(`[:runtime :nrepl]` is a system-scoped runtime-handle projection) + the
dispatch event-log description ("the coarse-grained dispatch journal: one
summarized entry per dispatch"); doc/extensions.md managed-service surface
(`:ensure-service`/`:stop-service`/`:service-request`, documented only
`:type :subprocess`; guidance: keep the managed-service core protocol-agnostic,
do not add protocol-specific behaviour without multi-integration justification).

- **AF-10 — live-handle realizing surface.** AF-8 fixed the handle's
  location/ownership (runtime-owned-on-`ctx`, keyed by logical identity) but left
  the realizing extension-API surface unpinned; the only documented runtime-ctx-
  handle surface (managed-service `:ensure-service`/`:stop-service`) documents
  only `:type :subprocess`, a runtime-spawned subprocess unfit for an
  extension-provided in-process integrant lifecycle. Resolved to **option (a)**: a
  **generic, non-subprocess managed-handle lifecycle `:type`** (e.g.
  `:type :managed-handle`) whose runtime-owned `:ensure-service`/`:stop-service`
  owns the `ctx` slot keyed by logical identity and delegates start/stop to the
  extension-provided in-process `init`/`halt!`. Generic lifecycle ownership (not
  dev-http protocol behaviour) → respects the managed-service-core
  protocol-agnostic guidance with multi-integration justification; integrant
  definition/config/lifecycle stays in the extension (Lifecycle Boundary upheld);
  explicit extension-API contract addition in the AF-3/AF-6/AF-9 lineage (one-way,
  no shim). The `:type :subprocess` transport surface is not adopted. Updated the
  AF-8 Lifecycle bullet's deferred-mechanism sentence to point to AF-10, added a
  new "Live server-handle realizing surface (AF-10)" Lifecycle bullet, and added a
  resolved-decision.

- **AMB-19 — token-enforcement rejection response.** AMB-1 fixed transport and
  AMB-18 fixed the enforcement layer, but the missing/invalid-token response was
  undefined. Resolved to **`403 Forbidden`** + plain-text body `"dev-http:
  missing or invalid token"` (naming `/dev-http status` as the remedy). `403`
  (server refuses) fits a query-param token with no HTTP auth-challenge protocol,
  so `401` is rejected; `404` route-existence-hiding is considered and rejected
  because the token is dev-grade/localhost-bound and developer debuggability of a
  stale link outweighs hiding existence on a loopback dev server. Applies
  uniformly to the gated dynamic routes; static-asset subtree exempt (AMB-18).
  Added a "Token-enforcement rejection response (AMB-19)" Lifecycle bullet,
  strengthened AC-7 to assert the `403` + body, and added a resolved-decision.

- **INC-12 — log-membership scope split.** INC-3 framed both event-sourced
  classes as members of one "the log" under a single replay-fidelity heading, but
  AF-7/AF-9 made class (1) system/runtime-scoped (no invoking session-id →
  `[:runtime :dev-http]`) and class (2) session-scoped (feedback session). Both
  dispatch, so both appear as one entry in the coarse-grained dispatch event-log
  (one entry per dispatch), but they differ in scope/replay-relevance: class (2)
  is the feedback session's per-session conversation-replay state (the injected
  user message), while class (1) is a system/runtime projection (same scope as
  `[:runtime :nrepl]` / OAuth `system_scope`) recorded in the dispatch journal but
  not a member of any one session's conversational replay. Rewrote the INC-3
  constraint heading (now INC-3, INC-12), added the two-scope/journal distinction,
  refined the "nREPL endpoint metadata is likewise in the log" claim to a
  system/runtime projection in the dispatch journal (not a per-session
  conversation-replay member), and added a resolved-decision.

Cross-item coherence checks: AF-10 keeps the handle out of the core `:state*`
atom and out of extension-local hidden state (AF-2/AF-4/AF-8 preserved) and is
consistent with the AF-9 "locate the concrete surface" standard; AMB-19 sits
beside AMB-18 (enforcement layer) and AMB-1 (transport) without altering the
exempt static-asset subtree; INC-12 leaves the AF-4/INC-8 token-never-in-
canonical/replayable invariant intact (token excluded from both scopes/journals)
and is consistent with the AMB-17 race-loser journaled-no-op clause (class (2)
session-scoped journal). All three design-steps marked done. No blocked/skipped
items.

### design-review · architecture (round 9, turn 1)

Fresh architecture-fit pass (first turn of a new shared design-review session)
over the fully-resolved design (AF-1..10 resolved; round-6 no-new-misfit;
round-7 AF-9; round-8 AF-10). Sources re-consulted: AGENTS.md (VSM S1–S5,
`system_scope(¬agent_session_scope)`, capability gating, shims/adapters one-way),
META.md (managed-services on `ctx` keyed by logical identity, reused-not-hidden;
core owns canonical projections; interactive-projection invalidation vs polling),
doc/architecture.md (State-boundary runtime-handles table — every listed handle
is core/runtime-placed with a core-owned projection; Dispatch sequencing contract
`:runtime/dispatch-event`; dispatch event-log one-entry-per-dispatch),
doc/extensions.md (managed-service surface `:ensure-service`/`:stop-service`,
documented only `:type :subprocess`; protocol-agnostic-core + multi-integration
guidance), doc/extension-api.md (only session-scoped `(:mutate api)`
rebound-to-invoking-session / `(:mutate-session api)` explicit-session;
`psi.extension/*` ext-scoped mutate).

AF-1..10 re-confirmed genuinely resolved and precedent-accurate:
extension-local `dev/` (AF-1); status projection of `running?`/token-less base
url only, token external per OAuth credential-externality (AF-2/AF-4);
first-class `psi.extension/*` dispatch-routed mutations in `:allowed-events` for
choice-submit (AF-3) and status (AF-6); effects-as-data follow-on (AF-5);
system-scoped status vs session-scoped choice-submit (AF-7); live handle
runtime-owned on `ctx` keyed by logical identity (AF-8); system-scoped
projection realized via a non-session-rebound dispatch path whose pure handler
writes `[:runtime :dev-http]` directly (AF-9 — verified the documented mutate
surfaces are both session-scoped, so the contract addition is genuinely needed,
not redundant); generic non-subprocess `:type :managed-handle` realizing AF-8
(AF-10).

Angles checked and dismissed (not new actionable misfits):
- AF-9 surface redundancy — verified via doc/extension-api.md that neither
  `(:mutate api)` nor `(:mutate-session api)` dispatches with no invoking
  session-id, so AF-7's system-scoped projection genuinely needs the AF-9
  contract addition; not over-engineering.
- AF-10 core `:type :managed-handle` addition driven by a dev-only extension —
  the managed-services guidance explicitly admits core additions with
  multi-integration justification, which AF-10 supplies (generic in-process
  managed-handle lifecycle, not dev-http protocol behaviour; integrant
  definition/lifecycle stays in the extension; Lifecycle Boundary upheld).
  `addition > modification` / `open_slot > closed_dispatch` favour this shape.
  Re-weighing the strength of that justification would relitigate AF-10's
  resolved judgment, not surface a new misfit.
- HTTP-handler off-thread `:mutate-session` against the AMB-4 target — precedented
  by the scheduler delayed-prompt path (round-6/8 dismissal holds).
- Pull-based status (no projection-invalidation effects) — mirrors the pull-only
  `[:runtime :nrepl]` precedent (round-6 dismissal holds).
- Reads-via-`:query-session` / writes-via-dispatched-mutation, minimal capability
  + `:allowed-events`, replay/determinism boundary — all conform.

Conclusion: **no new actionable architectural-fit misfit.** The design's
architectural fit is complete and coherent across the VSM layers and the
State/Dispatch/managed-services boundaries; AF-1..10 resolve every fit concern
with cited precedents. No design-steps added this pass.

### design-review · ambiguity (round 9, turn 2)

Fresh ambiguity pass (second turn of the shared design-review session) using the
already-loaded design.md + architecture sources + the round-9 architecture reply
(no new architecture misfit — not duplicated here). One targeted re-read
(grep for 404 / not-found / unknown-route / precedence wording) confirmed the gap
below is unfiled. AMB-1..19 re-confirmed resolved. One new actionable ambiguity:

- AMB-20 **Unknown / unregistered route-id browser-facing response + token-
  middleware-vs-route-resolution precedence is undefined.** The design pins every
  other browser-facing response precisely (AMB-8, AMB-11, AMB-15, AMB-19) but
  never defines what a GET to an unknown `/s/:route-id` (or unknown persisted
  `dev/` path) returns — a real, expected case after AMB-8 (registry cleared on
  halt) + AMB-16 (no pre-server staging registry): a stale tab/link reopened after
  `/dev-http stop`+`start` hits a no-longer-registered route-id. Two gaps: (1) the
  response (reitit/ring default `404` vs a pinned "no such route" body matching
  the design's pinned-response posture); (2) the precedence between the AMB-18
  token middleware (`403`) and route resolution (`404`) for an untokened request
  to an unknown route — under reitit, middleware on matched routes may let an
  unmatched path bypass the token check and return `404`, diverging from the `403`
  an untokened *known* route returns. Distinct from AMB-19 (token rejection on a
  gated existing route), AMB-8 (a known live route whose target session ended),
  and AMB-16 (the registration call while stopped).

### design-review · inconsistency (round 9, turn 3)

Fresh inconsistency pass (third turn of the shared design-review session) using
the already-loaded design.md + architecture sources + the round-9 architecture
(no new misfit) and ambiguity (AMB-20) replies — architecture/ambiguity items not
duplicated here. One targeted re-read (the Scope "In scope" + Slicing sections,
grepped for managed-handle / system-scoped / contract-addition / core wording)
confirmed the gap below is unfiled. INC-1..12 re-confirmed resolved. One new
actionable inconsistency:

- INC-13 **Scope/Slicing vs the AF-9/AF-10 core extension-API contract
  additions.** AF-9 (non-session-rebound system-scoped extension dispatch
  surface) and AF-10 (generic `:type :managed-handle` managed-service lifecycle
  type) both resolve to **explicit core extension-API contract additions** outside
  the dev-http extension's own namespaces, but the "In scope" list enumerates only
  extension-local deliverables and never lists them, and Slicing never assigns
  them (despite slice 1 implicitly needing both). The "the dev-http extension
  platform" framing plus the Lifecycle Boundary ("no core namespace gains
  dev-http-specific integrant code; integrant does not touch core … dispatch") can
  even read as excluding core changes, contradicting AF-9/AF-10's required generic
  core additions. Reconcile by stating in Scope (and assigning in Slicing) that the
  AF-9 dispatch surface and AF-10 `:type :managed-handle` are in-scope generic
  (non-dev-http-specific) core extension-API additions. Distinct from INC-11
  (dev-http internal slice ordering + register-route! sliced) and INC-12
  (log-membership scope split).

### design-review · round-9 follow-up execution (AMB-20, INC-13)

Evidence rule applied. Previous design-follow-up completion = `8e0be10cd`
(execute round-8 follow-ups AF-10, AMB-19, INC-12). The contiguous latest
review-batch segment since then is the round-9 batch — `e50028ae4`
(architecture-fit r9, no new misfit), `5b4523ac8` (ambiguity r9, AMB-20),
`90a746229` (inconsistency r9, INC-13). Oldest segment commit = `e50028ae4`; its
parent = `8e0be10cd` (the round-8 follow-up commit), confirmed via `git
rev-parse`, so that is the batch baseline. `git diff 8e0be10cd..HEAD --
design-steps.md` added exactly two unchecked checklist items — AMB-20 and INC-13
— both still unchecked at follow-up start and the entire candidate work set.
Prior `[x]` lines appear only as unchanged diff context, correctly excluded; no
predating/edited/stale/steps.md items in scope.

Verified precedents before editing: AMB-18/AMB-19 Lifecycle bullets (token
middleware over matched dynamic-route subtrees; `403` + body for missing/invalid
token on a gated existing route); AF-9/AF-10 resolutions (system-scoped dispatch
surface; generic `:type :managed-handle`); the In-scope list + Slicing section.

Resolutions written into design.md:

- **AMB-20 — unknown/unregistered route response + token-vs-route precedence.**
  Resolved to **`404 Not Found`** + plain-text body `"dev-http: no such route"`
  for an unmatched `/s/:route-id` (or unknown persisted `dev/` path), with
  **route-resolution-first precedence**: the AMB-18 token middleware wraps only
  *matched* dynamic-route subtrees (reitit middleware runs only for matched
  routes), so an unknown route-id returns `404` **regardless of token presence** —
  untokened *unknown* → `404` while untokened *known* gated → `403` (AMB-19), and
  valid-token unknown → the same `404`. The `404`-vs-`403` existent-vs-nonexistent
  signal is accepted under AMB-19's already-chosen debuggability-over-hiding
  posture on a loopback dev server (no attempt to mask unknown routes as `403`).
  Added a Lifecycle bullet, strengthened AC-7 with the unknown-route `404`
  clause, and added a resolved-decision entry. Distinct from AMB-19 (gated
  existing route), AMB-8 (known live route, dead target session), AMB-16
  (registration call while stopped).

- **INC-13 — Scope/Slicing cover the AF-9/AF-10 core extension-API additions.**
  AF-9 (non-session-rebound system-scoped dispatch surface) and AF-10 (generic
  `:type :managed-handle` managed-service lifecycle type) both resolve to
  **core/platform** extension-API contract additions outside dev-http's own
  namespaces, but "In scope" listed only extension-local deliverables and Slicing
  assigned neither. Resolved by adding an explicit "In scope" bullet naming both
  as **generic (non-dev-http-specific) core extension-API contract additions** —
  so the Lifecycle Boundary ("no core namespace gains dev-http-specific code")
  still holds — and assigning both to **slice 1** (which depends on both: the
  managed-handle hosts the integrant system handle on `ctx`; the system-scoped
  surface lands the status projection in `[:runtime :dev-http]`). Added the
  In-scope bullet, the slice-1 assignment, and a resolved-decision entry. This
  resolves the prior reading of "the dev-http extension platform" framing + the
  Boundary as excluding the required generic core additions.

Cross-item coherence checks: AMB-20 sits beside AMB-19 (gated-route token
rejection) and AMB-18 (enforcement layer) without altering the exempt
static-asset subtree, and is consistent with AMB-19's accepted
existence-revealing posture; the route-resolution-first precedence is the natural
reitit semantics already implied by AMB-18's "middleware over matched subtrees".
INC-13 leaves the AF-9/AF-10 resolutions themselves untouched (only Scope/Slicing
now name them as in-scope generic core additions) and keeps the Lifecycle
Boundary intact (the additions are generic, not dev-http-specific). Both
design-steps marked done. No blocked/skipped items.

### design-review · architecture (round 10, turn 1)

Fresh architecture-fit pass (first turn of a new shared design-review session)
over the fully-resolved design (AF-1..10 resolved; rounds 6 and 9 architecture
passes each concluded no new misfit; round 7 = AF-9, round 8 = AF-10). Sources
re-consulted: AGENTS.md (VSM S1–S5, `system_scope(¬agent_session_scope)`,
capability gating, shims/adapters one-way, state read-via-resolvers /
write-via-mutations), META.md (managed-services on `ctx` keyed by logical
identity, reused-not-hidden; core owns canonical projections; interactive
projections use invalidation not polling), doc/architecture.md (State-boundary
runtime-handles table — every listed handle is core/runtime-placed with a
core-owned projection; Dispatch sequencing contract `:runtime/dispatch-event`;
dispatch event-log one-entry-per-dispatch), doc/extensions.md (managed-service
surface — documented only `:type :subprocess`; protocol-agnostic-core +
multi-integration-justification guidance, lines 330–345), doc/extension-api.md
(only session-scoped `(:mutate api)` rebound-to-invoking-session and
`(:mutate-session api)` explicit-session, lines 155–168; `psi.extension/*`
ext-scoped mutate).

Verified the two load-bearing precedents directly this pass:
- doc/extension-api.md confirms both documented extension mutate surfaces are
  session-scoped (no no-session-id dispatch path), so AF-9's non-session-rebound
  system-scoped dispatch surface is a genuinely required contract addition, not
  redundant.
- doc/extensions.md confirms the managed-service core documents only
  `:type :subprocess` and admits generic core additions with multi-integration
  justification, so AF-10's generic `:type :managed-handle` is a precedent-fitting
  contract addition (not protocol-specific behaviour in the core).

AF-1..10 re-confirmed genuinely resolved and precedent-accurate: extension-local
`dev/` (AF-1); project `running?`/token-less base url only, token external per
OAuth credential-externality (AF-2/AF-4); first-class `psi.extension/*`
dispatch-routed mutations in `:allowed-events` for choice-submit (AF-3) and
status (AF-6); effects-as-data follow-on (AF-5); system-scoped status vs
session-scoped choice-submit (AF-7); live handle runtime-owned on `ctx` keyed by
logical identity (AF-8) realized via the generic `:type :managed-handle` (AF-10);
system-scoped projection realized via a non-session-rebound dispatch surface
writing `[:runtime :dev-http]` directly (AF-9).

Angles checked and dismissed (not new actionable misfits):
- Journaled `dev-present` tool result carrying the non-deterministic
  ephemeral-port token-less base URL — a recorded tool output replayed verbatim
  from the journal (effects suppressed on replay), the standard recorded-output
  pattern; the non-deterministic url is precedented (`[:runtime :nrepl]`) and the
  token is excluded (AF-4/INC-8). No replay-fidelity misfit.
- `register-route!` REPL fn reaching the runtime-owned `ctx` handle (AF-8/AF-10)
  — an in-process dev/REPL escape hatch by design (non-journaled, throwaway);
  this is a registration-surface mechanics/ambiguity concern, not an
  architecture-fit boundary violation (it does not reach core `:state*` or a
  core-owned event).
- Off-thread HTTP-handler `:mutate-session` against the AMB-4 target — precedented
  by the scheduler delayed-prompt path (rounds 6/8/9 dismissal holds).
- Pull-based status (no projection-invalidation effects) — mirrors the pull-only
  `[:runtime :nrepl]` precedent (rounds 6/9 dismissal holds).
- AF-9/AF-10 core additions driven by a dev-only extension — admitted by the
  managed-services multi-integration guidance and `addition > modification` /
  `open_slot > closed_dispatch`; re-weighing would relitigate AF-10's resolved
  judgment, not surface a new misfit (round 9 reasoning holds).

Conclusion: **no new actionable architectural-fit misfit.** The design's
architectural fit is complete and coherent across the VSM layers and the
State/Dispatch/managed-services boundaries; AF-1..10 resolve every fit concern
with cited, this-pass-verified precedents. No design-steps added this pass.

### design-review · ambiguity (round 10, turn 2)

Fresh ambiguity pass (second turn of the shared design-review session) using the
already-loaded design.md + architecture sources + round-10 architecture reply
(no new architecture misfit — not duplicated here). One targeted re-read (grep
for malformed / invalid-content / not-accepted / validation / reject wording)
confirmed every "reject" in design.md is about the token (AMB-19), empty
selection (AMB-15), not-running registration (AMB-16), or choice edges — none
covers `dev-present` content-shape violation. AMB-1..20 re-confirmed resolved.
One new actionable ambiguity:

- AMB-21 **`dev-present` malformed / shape-violating content handling + the
  per-renderer validation boundary is undefined.** AMB-10 fixes the canonical
  per-renderer `:content` shapes and says non-canonical `:table` variants are
  "not accepted (no shape detection)", but never defines what a `dev-present`
  call carrying shape-violating content *receives* (error tool-result vs
  throw vs broken render), nor *where* validation happens — server-checkable
  shapes (`:table`/`:markdown`/`:mermaid`/`:choices`) can be rejected at
  call time, but `:vega` content is opaque (handed to the vendored client lib)
  and can only fail at browser render time, so the validation boundary differs
  by renderer and is unstated. The design pins every other error/edge response
  precisely (AMB-8/11/15/16/19/20), so the malformed-content rejection response
  and the call-time-vs-render-time boundary need pinning for the tool handler and
  AC-3/AC-5 negative-path tests. Distinct from AMB-10 (valid shapes), AMB-16
  (not-running edge), and AMB-19 (token rejection).

design-steps.md: added AMB-21 (one unchecked item). No blocked/skipped items.

### design-review · inconsistency (round 10, turn 3)

Fresh inconsistency pass (third turn of the shared design-review session) using
the already-loaded design.md + architecture sources + round-10 architecture (no
new misfit) and ambiguity (AMB-21) replies — architecture/ambiguity items not
duplicated here. AMB-21 is a new *gap* (design unchanged), so not yet a
contradiction source. Targeted re-reads only, aimed at resolution-introduced
staleness/contradiction:
- Stale handle-location wording (grep `own atom` / `extension-local hidden` /
  `mcp-tasks-run` / `work-on`): every hit is inside the AF-8 resolution that
  **explicitly supersedes/contextualizes** the old "extension's own atom" framing
  (lines 375–380, 970–971); no uncorrected stale wording. AF-1's "extension's own
  `:dev` extra-path" (851) is correct.
- Token-projection wording (grep `token.*project` / `running?/url/token` /
  `url+token`): uniformly "token-less base url projected, token external"; the
  stopped-status line reports "no url and no token"; no leftover
  "running?/url/token projected" staleness. AF-4 invariant intact.
- Determinism-boundary bullet vs INC-3's non-deterministic-url admission:
  reconcilable — the bullet's "not part of this non-deterministic boundary" is a
  *canonical-state membership* statement (only the live handle + excluded token
  sit outside canonical state), while INC-3 separately admits the projected
  token-less base `url`'s *value* is non-deterministic-but-precedented (nREPL
  parallel). Membership-vs-value, already reconciled by INC-3; no contradiction.

INC-1..13 re-confirmed resolved and coherent (slicing INC-11/INC-13, log-scope
split INC-12, AMB-17 race-loser journaled-no-op reconciliation, URL-form
journaled-vs-non-journaled principle all internally consistent).

Conclusion: **no new actionable inconsistency.** No design-steps added this pass.

### design-review · execute round-10 follow-ups (AMB-21)

Batch baseline established per the evidence rule: previous design-follow-up
completion was `4ae12b11f` (round-9 follow-ups); the immediately preceding
review batch is round 10 — architecture (`845113c18`, no new misfit), ambiguity
(`a2ace999a`, filed AMB-21), inconsistency (`d9141a7a2`, no new inconsistency).
Baseline = parent of the oldest batch commit = `845113c18^` = `4ae12b11f`.
`git diff 845113c18^..HEAD -- design-steps.md` added exactly one unchecked
checklist item: **AMB-21** (architecture/inconsistency passes added none). It
remained unchecked at follow-up start. Candidate work set = {AMB-21}.

Resolved AMB-21 by pinning the `dev-present` malformed-content response and the
per-renderer validation boundary along a **structural-vs-semantic** axis:
- **Call-time structural validation** — the `dev-present` handler validates
  `:content` against the AMB-10 per-renderer structural shape (per-`:renderer`
  malli dispatch schema) *before registering*; a violation → **error tool-result**
  naming renderer + expected shape, **nothing registered, no URL** (mirrors
  AMB-16's not-running posture and AMB-19's pinned-rejection posture).
- **Render-time opaque validation** — the opaque interior of `:vega`/`:mermaid`/
  `:markdown` content (structurally well-typed but semantically invalid) is *not*
  call-time-checked; it is handed to the vendored client lib / commonmark and
  fails only at **browser render time**; the route is still served. The platform
  never parses Vega-Lite/Mermaid grammars server-side.
- `register-route!` (arbitrary handler fn, INC-5) has **no analogous call-time
  content validation** — no declarative content shape exists.

design.md edits: new AMB-21 decision bullet in the edge-case decision section
(after AMB-20), AC-3 negative path (shape-violating call → error tool-result,
registers nothing; opaque failures are render-time, not tool errors), AC-5
per-renderer structural-violation rejection clause, and an AMB-21 entry in the
resolved-decisions log. design-steps.md: AMB-21 marked `[x]`.

No blocked/skipped items.

### design-review · architecture (round 11, turn 1)

Fresh architecture-fit pass (first turn of a new shared design-review session)
over the fully-resolved design (AF-1..10 resolved; architecture passes rounds 6,
9, 10 each concluded no new misfit). Sources re-consulted this pass: AGENTS.md
(VSM S1–S5, `system_scope(¬agent_session_scope)`, untrusted-extension posture,
shims/adapters one-way, reads-via-resolvers/writes-via-mutations), META.md
(managed-services on `ctx` keyed by logical identity, reused-not-hidden), and —
verified directly this pass — doc/architecture.md's **live default interceptor
chain** (`:permission :log :statechart :handler :effects
:trim-effects-on-replay :validate :apply`), the Dispatch sequencing contract
(`:runtime/dispatch-event` follow-on runs synchronously from `:effects` after
apply), the dispatch event-log being one-summarized-entry-per-dispatch carrying
**`ext id` + `origin`**, and doc/extension-api.md confirming both documented
extension mutate surfaces (`(:mutate api)` rebound-to-invoking-session,
`(:mutate-session api)` explicit-session) are session-scoped.

New angles examined this pass (none a new actionable misfit):
- **AF-9 no-session-id system-scoped dispatch through the live interceptor
  chain.** Probed how `:permission` (extension `:allowed-events`), `:statechart`,
  and capability-gating treat a dispatch carrying no invoking session-id. Resolved
  cleanly: permission enforcement is keyed on the **ext-id** (carried in every
  dispatch per the event-log `ext id`/`origin` fields), not on a session, so a
  declared `psi.extension/*` event is permission-checkable without a session;
  system-scoped events are simply **not statechart-claimed** (the `[:runtime
  :nrepl]`/OAuth `system_scope` precedent), and capability-gating is not in the
  live default chain. No fit conflict; AF-9's "explicit extension-API contract
  addition carrying no session-id" sits within the existing chain semantics.
- **Extension pure handler writing the core `[:runtime :dev-http]` key.** The
  declared `psi.extension/*` status mutation's pure handler emits a
  `:root-state-update` into `[:runtime :dev-http]` — the sanctioned dispatch write
  path, mirroring the `[:runtime :nrepl]` scope, already located by AF-7/AF-9; not
  an untrusted-extension boundary breach (no reach into a core-owned event, no
  direct atom access).
- **Two generic core additions (AF-9 surface, AF-10 `:type :managed-handle`)
  driven by a dev-only extension.** Admitted by the managed-services
  multi-integration guidance and `addition > modification` / `open_slot >
  closed_dispatch`; re-weighing would relitigate AF-10's resolved judgment
  (rounds 9/10 reasoning holds).
- Off-thread HTTP-handler `:mutate-session` (scheduler delayed-prompt precedent),
  pull-based status with no projection-invalidation effects (pull-only `[:runtime
  :nrepl]` precedent), AF-5 effects-as-data follow-on (Dispatch sequencing
  contract) — all conform; dismissals from prior rounds hold.

Conclusion: **no new actionable architectural-fit misfit.** The design's
architectural fit is complete and coherent across the VSM layers and the
State/Dispatch/managed-services/interceptor boundaries; AF-1..10 resolve every
fit concern with cited, this-pass-verified precedents (including the live
interceptor-chain semantics for the AF-9 no-session-id surface). No design-steps
added this pass.

### design-review · ambiguity (round 11, turn 2)

Fresh ambiguity pass (second turn of the shared design-review session) using the
already-loaded design.md + architecture sources + the round-11 architecture reply
(no new architecture misfit — not duplicated here). Targeted re-reads only (grep:
persisted/choices-helper/session-id, and route-id/verbatim/generate) to confirm
the two gaps below are unfiled. AMB-1..21 re-confirmed resolved. Two new
actionable ambiguities:

- AMB-22 **Persisted `dev/` routes and the choice-feedback loop is undefined.**
  The design routes choice feedback to exactly two registration classes — the
  `dev-present` tool (defaults to its invoking session, AMB-4) and the REPL
  `register-route!` fn (explicit `:session-id`, AMB-4/INC-7) — but persisted
  `dev/` routes are a **third raw-handler class** (full-power Clojure handlers
  loaded at integrant `init`, slice 1) with **no registration-time invoking
  session and no `:session-id` argument**. A persisted handler is a raw ring
  handler with access to the same platform helpers, so it could call the
  **choices interaction helper** (INC-7) — yet the design never states whether
  persisted routes may participate in the choice loop, and if so **where their
  feedback session-id comes from** (none exists at load time; a per-request
  source such as a query param is undefined). Two materially different readings:
  (a) persisted routes are **presentation-only** for choices (the helper is
  unavailable / requires a session-id they cannot supply), or (b) persisted
  routes **can** use the choices helper via some per-request session-id mechanism
  the design must define. The design pins every other interaction edge precisely,
  so the persisted-route choice-loop availability + session-id source should be
  pinned for the choices-helper contract and the slice-1/slice-3 AC coverage.
  Distinct from AMB-4 (the `register-route!` explicit session-id), INC-7 (the
  helper's existence/wiring), and AMB-8 (target liveness).

- AMB-23 **Caller-supplied `:route-id` validity / format constraints are
  undefined.** AMB-2 fixes the route-id *assignment model* (optional
  caller-supplied id used **verbatim**, replace-on-collision; else a
  system-generated unique id) but never constrains the **format/charset/validity**
  of a caller-supplied id against the **single-segment `/s/:route-id`** dispatch
  subtree. A caller-supplied id containing a `/` (two segments → never matches
  `/s/:route-id`), an empty string, URL-unsafe characters, or a value colliding
  with the static-asset subtree / a reserved path therefore has **undefined
  behaviour** — most likely a silently-registered but **unreachable always-`404`
  route**, contradicting the design's pin-every-edge / pinned-response posture
  (AMB-16/19/20/21). Specify the caller-supplied route-id constraints (allowed
  charset / single-segment requirement / non-empty / reserved-prefix exclusion)
  and the **rejection response** for an invalid id at registration time — most
  consistently an **error tool-result / error return** in the AMB-21/AMB-16
  posture (registers nothing, no URL). Distinct from AMB-2 (assignment model),
  AMB-20 (the *unknown*-route browser response), and AMB-21 (`:content` shape
  validation).

### design-review · inconsistency (round 11, turn 3)

Fresh inconsistency pass (third turn of the shared design-review session) using
the already-loaded design.md + architecture sources + round-11 architecture (no
new misfit) and ambiguity (AMB-22, AMB-23) replies — architecture/ambiguity items
not duplicated here. AMB-22/AMB-23 are new *gaps* (design unchanged), so not yet
contradiction sources. Targeted re-reads only (the AMB-11 / AMB-17 single-shot
region and the INC-10 resolved-decision guard wording). INC-1..13 re-confirmed
resolved. One new actionable inconsistency:

- INC-14 **Single-shot "submitted" flag location: AMB-11/INC-10 (registry-entry
  flag) vs AMB-17 (canonical `:state*` flag).** AMB-17 relocated the authoritative
  single-shot state to a **canonical `:state*` flag** (a feedback-session-scoped
  submitted-route-id set), read by the best-effort pre-dispatch guard via
  `:query-session`, and explicitly states the **registry entry holds only the
  route *definition*** (the submitted *decision* state is canonical
  event-sourced state). But the earlier AMB-11 body still says the first POST
  "marks the route **submitted** (a flag on the **registry entry**)" and the guard
  short-circuits by "reading the **registry-entry submitted flag**", and the
  INC-10 resolved-decision still says the pre-dispatch guard reads "the
  **registry-entry** selection/single-shot flags." So the design describes the
  single-shot flag's storage two contradictory ways — **on the registry entry**
  (AMB-11/INC-10) vs **canonical `:state*`, not on the registry** (AMB-17) — and
  the guard's read source two ways (registry-entry flag vs canonical flag via
  `:query-session`). AMB-17 is the later authoritative refinement (INC-10's own
  tail note acknowledges "the authoritative single-shot mark moves into the
  dispatch-serialized mutation"), so reconcile the stale AMB-11 and INC-10 wording
  to AMB-17: the submitted flag is a canonical `:state*` feedback-session-scoped
  submitted-route-id set (registry entry = route definition only), and the
  pre-dispatch guard reads that canonical flag via `:query-session`. Distinct from
  AMB-17 (the relocation resolution itself) and INC-10 (no-op log membership /
  pre-dispatch short-circuit) — INC-14 is the residual unreconciled
  flag-location/guard-source contradiction in the AMB-11 and INC-10 text.

---

## Round-11 design-review follow-up execution (2026-06-15)

Batch baseline = parent of oldest round-11 review commit (`9847dca3b^` =
`2667c744d`, the round-10 follow-up execution). `git diff 2667c744d..HEAD --
design-steps.md` added exactly three unchecked items: **AMB-22**, **AMB-23**
(ambiguity round 11), **INC-14** (inconsistency round 11). Architecture round 11
added no new misfit. All three still unchecked at follow-up start → executed.

- **AMB-22 (persisted `dev/` routes + choice feedback).** Resolved to **option
  (a) presentation-only**: persisted routes are raw handlers loaded at integrant
  `init` with no registration-time session; they may call the choices helper
  (INC-7), but the helper requires an explicit feedback `:session-id` (AMB-4) and
  the platform defines **no implicit/per-request session-id source** (a
  per-request binding would be an unauthenticated cross-session injection
  vector), so absent an explicit id the helper renders a presentation-only
  (feedback-disabled) form, mirroring `register-route!` without `:session-id`.
  Choice-feedback loop stays bound to the two session-aware classes. Added body
  paragraph after AMB-4 + resolved-decision entry. Pins choices-helper contract +
  slice-1/slice-3 AC coverage.

- **AMB-23 (caller-supplied route-id constraints + invalid-id rejection).**
  Constraints: non-empty, single-segment (no `/`), URL-safe charset
  `[A-Za-z0-9_-]+`, not a reserved id (no static-asset-subtree / reserved-path
  collision). Violating id rejected at registration time (registers nothing, no
  URL) in the AMB-16/AMB-21 posture (`dev-present` error tool-result;
  `register-route!` error value). System-generated ids satisfy constraints by
  construction. Forbids the silently-registered unreachable always-`404` route.
  Added body paragraph after AMB-2 + resolved-decision entry.

- **INC-14 (single-shot flag location/guard-source reconciliation).** Reconciled
  the stale AMB-11 body and INC-10 resolved-decision wording (submitted flag "on
  the registry entry"; guard reads "registry-entry submitted/selection flags") to
  AMB-17: the submitted flag is a canonical `:state*` feedback-session-scoped
  submitted-route-id set (registry entry = route definition only), the
  authoritative mark is set inside the dispatch-serialized mutation, and the
  pre-dispatch guard reads that canonical flag via `:query-session`. Edited both
  stale passages + added an INC-14 resolved-decision entry recording the
  reconciliation.

All three marked `[x]` in design-steps.md.

---

## Round-12 architecture review (2026-06-15)

First turn of the shared `design-review` session (architecture-fit only).
Re-read design.md against AGENTS.md (VSM S2 untrusted-extension /
capability-gating posture, `protect: ¬direct_atom_access(extensions)`),
META.md (managed-services principle), doc/extensions.md (managed-service-core
guidance), and doc/extension-api.md (`:mutate`/`:mutate-session` surfaces).

AF-1..AF-10 (persisted-route isolation, status-projection ownership/scope,
choice-mutation contract/sequencing, token/handle externality, runtime-owned-
on-`ctx` live handle, AF-9 system-scoped dispatch surface, AF-10
`:type :managed-handle`) remain sound and well-fitted; no regression found.

**One new actionable architecture-fit misfit (AF-11):** the design resolves AF-9
into a *generic, non-dev-http-specific* (INC-13) system-scoped extension dispatch
surface whose pure handler writes `[:runtime :dev-http]` directly, and AF-10 into
a generic runtime-owned `ctx`-slot keyed by logical identity — both authorized
only by `:allowed-events` declaration (the AF-3/AF-6 session-scoped lineage). But
system/runtime-scoped writes are strictly broader authority than the existing
session-scoped `:mutate`/`:mutate-session` surfaces, and the design never bounds
*which* `[:runtime <key>]` state key (AF-9) or *which* logical `ctx`-slot identity
(AF-10) an untrusted extension may target. As specified, the generic surfaces let
an untrusted extension write/claim the core-owned `[:runtime :nrepl]` / OAuth slot
or a peer extension's slot — circumventing **at the state-write/ctx-slot level**
the very core-owned-projection protection AF-6 establishes **at the event level**
(AF-6 forbids dispatching `:session/set-nrepl-runtime`, yet an unconfined
system-scoped write could clobber `[:runtime :nrepl]` directly). Filed AF-11:
specify the authority-bounding (ext-path-derived/namespaced key confinement
and/or distinct S2 capability gating) so the two new system-scoped surfaces
preserve the untrusted-extension isolation posture. Distinct from AF-6 (event
ownership), AF-7 (scope decision), AF-9/AF-10 (the realizing surfaces) — AF-11 is
the system-scoped-write/ctx-slot **authority-bounding / isolation** concern.
