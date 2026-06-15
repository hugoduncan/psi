# 229 — dev-http presentation extension

## Intent

Add a `dev-http` psi extension: a local HTTP server that acts as a **rich,
bidirectional side channel between the agent and the developer, addressed by
URL**. It lets the agent (and dev-tree code) present things the terminal cannot
— benchmark results, alternative-design comparisons, choice prompts, diagrams,
arbitrary file artifacts — in a browser, and lets the developer's interactions
(notably *choices*) flow back into the originating session.

This is a **dev-time** capability. It is not shipped behaviour for end users; it
exists to make developing psi (and with psi) richer and more interactive.

## Why

The terminal/emacs surfaces are line-oriented. Many dev-time artifacts are
inherently visual or interactive: benchmark tables/charts, side-by-side design
alternatives, dependency/run/EQL diagrams, and "which of these do you prefer?"
decisions. A browser side channel presents these well and supports a real
interaction loop (agent presents options → human picks → agent reacts).

## Model: platform vs content

Keep a hard split:

- **Platform** (the extension, stable, thin): HTTP server, reitit router, a
  route registry, lifecycle, the renderer set, and the interaction boundary.
- **Content** (rich, churny): individual routes.

Two route sources feed one router:

1. **Persisted routes** — reitit route-data + handler namespaces under the
   extension-local `extensions/dev-http/dev/` source path. The extension owns
   its own `dev` extra-path in its extension-local `deps.edn`
   (`{:dev {:extra-paths ["dev"]}}` scoped to the extension), rather than the
   project-global root `:dev`/`dev/` alias. This preserves the
   "integrant/extension scoped strictly inside dev-http" isolation posture
   (AF-1): the extension does not reach a project-global source location.
   Committed, full-power Clojure handlers, reloadable. Dev-only; never in the
   published jar.
2. **Session routes** — registered at runtime into a registry atom, reached
   through a single stable dispatch subtree (e.g. `/s/:route-id`). Throwaway;
   they live with the **server** — the registry is cleared on server halt, not
   on agent-session end (AMB-8). This avoids rebuilding the immutable reitit
   router on every registration.

**Persisted-route discovery contract (AMB-13).** Persisted routes are collected
through a **single conventional entry var** — not namespace auto-scanning and not
ad-hoc `register` calls. The extension's integrant `init` `require`s one
conventional entry namespace under `extensions/dev-http/dev/` (e.g.
`dev-http.routes`) and reads a conventional var (e.g. `dev-http.routes/routes`)
that returns a **reitit route-data vector**; that vector may pull in sibling
handler namespaces under `dev/` (ordinary `require` + handler refs). integrant
`init`/reload re-`require`s the entry namespace and rebuilds the router from the
returned vector, so editing a `dev/` route and reloading picks it up via this one
deterministic entry point. There is **no marker-scan / auto-discovery
convention** (one obvious path; AMB-13). AC-2 is satisfied when a route
contributed through this entry var is served by the running server.

## Capability surface

### Registration (D1 = both)

- **`dev-present` tool** (model-callable, declarative/data): registers a session
  route from content data and returns its URL. Safe, replay-friendly, the model
  can drive it directly. Targets only the **safe declarative renderers**
  (`:markdown`, `:table`, `:vega`, `:mermaid`, `:choices`) — see INC-2. It may
  **not** target the `:hiccup` raw-HTML or `:file` arbitrary-disk escape
  hatches; those are reachable only through the dev-driven `register-route!`
  path, preserving the "safe, model-driven" framing.
- **`register-route!` (REPL/dev, fn-based)**: dev registers an arbitrary ring
  handler fn into the registry for full-power session routes. In-process,
  throwaway, not persisted/replayed. Because the handler is a raw fn (not a
  renderer spec), it is the path to the platform **render helpers** the handler
  fn calls to build its ring response: the **hiccup** and **file** escape-hatch
  helpers (raw-handler idioms, not `dev-present` renderer keywords — INC-5) and
  the **choices** interaction helper (INC-7). With an explicit `:session-id`
  (AMB-4) the handler can use the choices helper to participate in the choice
  loop; without one it is presentation-only.

**Route-id assignment (AMB-2).** Both `dev-present` and `register-route!` accept
an **optional caller-supplied `:route-id`**. When supplied, it is used verbatim
and re-registering an existing id **replaces** the prior entry (O4,
last-write-wins). When omitted, the platform **generates a unique id**
(collision-free). Both registration paths share this single id model.

**Registration-call return URL form (AMB-14).** The two registration calls return
**different URL forms**, governed by the journaled-vs-non-journaled principle
under Lifecycle/INC-8:

- **`dev-present`** is a model-callable tool whose result is **journaled into the
  replayable session conversation**, so it returns the **token-less base URL**
  only — returning a token-embedded URL would leak the credential-class token
  into replayable state (AF-4/INC-3/INC-8). The model hands the developer an
  openable link by directing them to `/dev-http status` (which renders the
  token-embedded copy-pasteable URL from the base URL + the external token); the
  tool result may include that hint. The token is never journaled.
- **`register-route!`** is a REPL/dev fn whose **return value is not journaled**
  into session state (it is an ordinary REPL evaluation result), so it returns the
  **token-embedded copy-pasteable URL** directly — a convenient directly-openable
  link with no journal leak, consistent with the non-journaled REPL-local surface
  class (AMB-14/INC-9).

This keeps the asymmetry principled: the journaled surface (`dev-present` result)
carries the token-less base URL; the non-journaled REPL surface
(`register-route!` return) carries the token-embedded copy-pasteable URL.

**Registration when the server is not running (AMB-16).** Both registration
calls require a **running** server: a registered route's URL is formed from the
ephemeral-port base URL, and the session-route registry lives inside the running
integrant system held as a runtime-owned handle on `ctx` (AF-8) — while
`/dev-http` is **stopped** neither exists. Invoking `dev-present` (AC-3) or
`register-route!` (AC-4) while stopped therefore **fails with a clear "dev-http
server is not running; start it with `/dev-http start`" error**: nothing is
registered and **no route URL is returned** (there is no base URL to form one).
There is **no implicit auto-start** (it would conflict with the explicit
`/dev-http start` command surface and AMB-9's start idempotency) and **no
pre-server staging registry** (it would violate AF-8's "no live registry off
`ctx` when stopped" and AMB-8's "registry = server lifetime"). The `dev-present`
tool surfaces this as an **error tool-result** naming the remedy (a journaled
result carrying no URL/token); the REPL `register-route!` raises / returns an
error value naming the remedy. This is distinct from AMB-9 (already-**running**
`start` idempotency) and AMB-12 (stop/status command edges) — AMB-16 is the
**registration-call** not-running edge.

### Renderers (D2)

The **declarative renderer keywords** are the safe, data-driven set selectable
by the `dev-present` tool (one keyword per route):

- `:markdown` — rendered via the existing commonmark dep.
- `:table` — tabular data.
- `:vega` — Vega-Lite spec → chart (client-side lib).
- `:mermaid` — Mermaid diagram source → diagram. **Mermaid only; no Graphviz**
  — the vendored client-asset set is Vega-Lite + Mermaid only (O5/INC-4).
- `:choices` — a choice form (the interaction primitive; see D3).

The **escape hatches are raw-handler idioms, not declarative renderer keywords**
(INC-5). Because `register-route!` registers an **arbitrary ring handler fn**
(not a renderer spec), there is no renderer-keyword channel for them; instead the
platform exposes **render helper fns** that such a handler fn may call to
build its ring response — two **escape-hatch** helpers and one **interaction**
helper:

- **hiccup helper** — renders arbitrary HTML from a hiccup form (the raw-HTML
  escape hatch).
- **file helper** — serves an arbitrary file artifact from disk
  (HTML/SVG/PNG/PDF/…) produced out-of-band (e.g. a benchmark report).
- **choices helper (INC-7)** — emits a **platform-wired choice form** bound to
  the route's feedback `:session-id` (AMB-4), so a full-power raw handler can
  present the same interaction primitive the `dev-present :choices` renderer
  offers. The platform owns the choice-POST endpoint, the first-class
  `psi.extension/*` choice-submit mutation, and the single-shot (AMB-11) /
  target-liveness (AMB-8) machinery; the helper only renders the wired form
  (option spec per AMB-7). This is the **documented path by which a
  `register-route!` raw handler participates in the choice loop**, and it is why
  `register-route!` carries an explicit `:session-id`: without a feedback target
  the choices helper has nowhere to deliver the user message, so the route is
  presentation-only.

These helpers are invoked from a `register-route!` handler fn (not selected as
`dev-present` `:renderer` keywords). The **hiccup/file escape hatches have no
`dev-present` channel at all** (INC-2); the **choices helper is the raw-handler
counterpart of the declarative `:choices` renderer** — same platform choice-POST
machinery, different entry surface (raw handler fn vs declarative tool call). So
`:choices` interaction is reachable from both registration paths (declaratively
via `dev-present`, imperatively via the `register-route!` choices helper), while
hiccup/file remain `register-route!`-only.

**`dev-present` content-data shapes (AMB-10).** A `dev-present` call carries a
`:renderer` keyword and a `:content` value; the `:content` shape is fixed
per-renderer so the tool contract is unambiguous for AC-3/AC-5:

- `:markdown` — `:content` is a **string** (CommonMark source).
- `:table` — `:content` is a **map `{:headers [string …] :rows [[cell …] …]}`**:
  `:headers` is a vector of column-header strings and `:rows` is a vector of
  equal-length row vectors (cells stringified for display). This single explicit
  shape is canonical — vector-of-maps / vector-of-vectors variants are **not**
  accepted (one-way; no shape detection).
- `:vega` — `:content` is a **Vega-Lite spec map** (passed to the vendored
  Vega-Lite client lib as data).
- `:mermaid` — `:content` is a **string** (Mermaid diagram source).
- `:choices` — `:content` is the choices spec defined under Interaction/AMB-7
  (`{:options [{:label, :value?} …], :multi? false, :prompt?}`).

`:hiccup` and `:file` are not `dev-present` renderers (INC-2/INC-5); their input
shapes belong to the `register-route!` raw-handler path.

### Interaction (D3)

Presentation is **out-of-band** (like logging/UI projection): non-deterministic,
not replayed, fine. **Interaction results MUST re-enter the session through a
mutation**, or they are invisible to the agent and break replay.

The interaction loop:

```
agent → register route (tool/REPL) → receives URL → tells developer
      → developer opens / submits a choice in the browser
      → POST → :mutate-session → mid-conversation USER message into the
        originating session → drives the agent's next turn
```

The choice submission appears as a **user** message (the human's decision is
genuine user input) and **drives the agent's next turn immediately**. This
targets the `:session/submit-synthetic-user-prompt` path (the same mechanism the
scheduler uses for delayed prompts), not the append-only `append-message` path.

**Extension-API contract for the wrapping mutation (AF-3, AF-5).** The submission
path (`:session/submit-synthetic-user-prompt`) is currently dispatched only
internally. Rather than reach into that internal event as a back-door, this task
adds a **first-class `psi.extension/*` mutation** (e.g.
`psi.extension/dev-http-submit-choice`) reached through the extension
`:mutate-session` API. It is dispatch-routed and **declared in the extension's
`:allowed-events`**. Its **pure handler does not imperatively dispatch** the
follow-on event; instead it emits a **`:runtime/dispatch-event` follow-on
effect** (effects-as-data) targeting `:session/submit-synthetic-user-prompt`,
honoring the Dispatch sequencing contract (pure handler → effects → boundary
executes) (AF-5). This is an explicit, documented extension-API contract update
(one-way / no shim, untrusted-extension posture), not an internal-event bridge.

**Mid-turn / busy submit behavior (AMB-3).** Because the choice mutation rides
the synthetic-user-prompt path, the session statechart governs turn admission
(same as the scheduler's delayed-prompt path). A submission while the session is
**mid-turn/busy is queued** and delivered as the next user turn when the current
turn completes — it is **not** rejected and does **not** interrupt the in-flight
turn. "Immediately" therefore means *no manual trigger is required*: the
submission drives the next available turn automatically.

**Choice-feedback target session (AMB-4).** Choice feedback requires a target
session-id. The `dev-present` tool defaults the target to its **invoking
session**. The REPL `register-route!` fn has no invoking agent session, so it
takes an explicit `:session-id` argument naming the feedback target — consumed by
the platform **choices helper** (INC-7) when the raw handler emits a choice form;
if omitted, the route is **presentation-only** (its `:choices`/POST feedback is
disabled — there is nowhere to deliver the user message).

Routes target the **invoking session only**; multi-session targeting is out of
scope.

**Choice selection → user-message content (AMB-7).** A `:choices` renderer is
given an ordered list of options; each option is a map with a required
human-readable **`:label`** and an optional **`:value`** (the string delivered to
the agent; defaults to `:label` when omitted). Selection is **single-select** by
default (radio); an optional **`:multi? true`** on the choices spec switches to
multi-select (checkboxes). On submit, the injected synthetic **user message** is
exactly the selected option's `:value` (single-select), or, for multi-select,
the selected `:value`s joined by `", "` in option order. An optional **`:prompt`**
string on the choices spec, when present, is prefixed as `"{prompt}: {value(s)}"`
so the agent receives self-describing context. No other framing is added — the
delivered string is deterministic from the option spec + selection (AC-6).

**Target liveness + registry lifetime (AMB-8).** AMB-4 fixes the feedback target
*identity*; this fixes its *liveness*. At choice-submit time the target session
may have ended/closed. If the target session is **no longer live**, the
submission is **dropped by a pre-dispatch guard in the HTTP choice-POST handler**
(which checks target-session liveness via `:query-session` before dispatching):
**no wrapping `psi.extension/*` choice-submit mutation is dispatched, so nothing
is event-sourced or logged** (INC-10), and the browser receives a clear "session
no longer active" response. Registry
lifetime is tied to the **server**, not the invoking agent session: the
session-route registry lives in the extension's integrant system and is cleared
only on server **halt** (`/dev-http stop` / reload), **not** when an invoking
agent session ends. A route whose target session has ended therefore remains
served but becomes effectively presentation-only (feedback dropped per above).
"Die with the server/session" thus means *die with the server* (registry =
server lifetime); feedback delivery is independently gated on per-target-session
liveness.

**Repeat submission (AMB-11).** A `:choices` route is **single-shot**: the
**first** successful POST injects exactly one synthetic user message and marks the
route **submitted** (a flag on the registry entry). Subsequent POSTs to the same
live route are **short-circuited by the same pre-dispatch handler guard**
(reading the registry-entry submitted flag): **no wrapping mutation is
dispatched** (INC-10), nothing is injected, and they return a clear "choice
already submitted" response; the rendered page likewise reflects the submitted
state on reload. This makes accidental re-submission (double-click, reopening the page,
changing a pick) safe and deterministic — one choice route yields at most one
user message. (Distinct from AMB-3's mid-turn *timing* and AMB-8's target
*liveness*: even a single, well-timed, live-target submission is accepted only
once.) To present a fresh decision, register a new route. A submission that is
*dropped* for target-liveness (AMB-8) does **not** consume the single shot.

**Empty / no-selection submit (AMB-15).** A `:choices` POST carrying **zero
selected options** — reachable for an unchecked multi-select form, and for
single-select radios (which are rendered with **no default-checked option** so
the developer must make an explicit choice) — is **rejected by the same
pre-dispatch handler guard** (which checks for a non-empty selection before
dispatching): **no wrapping `psi.extension/*` mutation is dispatched, nothing is
event-sourced or logged** (INC-10), nothing is injected, and the AMB-11 single
shot is **not consumed**. No synthetic user
message is injected (not even a `:prompt`-only message: the `:prompt` is
contextual framing for a real selection per AMB-7, not a standalone message), the
browser is told **"no selection — please choose an option,"** and the route stays
**live/un-submitted** so a subsequent valid selection is still accepted. This
mirrors the AMB-8 liveness-drop posture (a non-decision does not burn the single
shot) and keeps the single-shot guarantee tied to a **genuine decision**: a
choice route yields at most one user message, and that message always carries a
real selection (AC-6). Distinct from AMB-3 (mid-turn timing), AMB-8 (target
liveness), and AMB-11 (repeat of a *selected* submission).

**Concurrent first-shot atomicity + single-shot mark location (AMB-17).** AMB-11
makes a `:choices` route single-shot and INC-10's pre-dispatch HTTP guard reads
the submitted flag, but the design must say **where the flag flips** and how the
read-check-mark is atomic — otherwise two simultaneous valid first-shot POSTs
could both pass a pre-dispatch flag-read before either marks the route submitted
(a TOCTOU window), leaving the "at most one user message per choice route"
guarantee undefined under concurrency. Resolution, following **task 224's
at-most-once funnel**: the **authoritative single-shot mark is set inside the
dispatch-serialized `psi.extension/*` choice-submit mutation's pure handler — not
in the HTTP handler**. The submitted state is a **canonical `:state*` flag** (a
submitted-route-id set under the feedback-session scope), so atomicity comes from
**dispatch serialization on the single-source-of-truth atom** — no test-and-set
on external state. The mutation's pure handler reads the flag: if the route-id is
**absent**, it adds it (`:root-state-update`) **and** emits the
`:runtime/dispatch-event` follow-on effect targeting
`:session/submit-synthetic-user-prompt` (both-or-neither, per AF-5); if
**present**, it **no-ops** (no state change, no follow-on, no message). The
**INC-10 pre-dispatch HTTP guard is a best-effort fast path** (reads the same
canonical flag via `:query-session`) that short-circuits the
*deterministically-known* no-ops (dead target — AMB-8, empty selection — AMB-15,
already-submitted-and-observed — AMB-11) before any dispatch; under a concurrent
first-shot race it may admit two dispatches, but the dispatch-serialized handler
accepts only the **first** — the loser's handler no-ops. This guarantees **at
most one synthetic user message per choice route** under concurrency
(AMB-11/AC-6). The registry entry (off-`ctx`, AF-8) holds the route
*definition*; the *submitted decision state* is **canonical event-sourced
state**, consistent with INC-3 class (2) (a first-shot submit is
message-producing and event-sourced). The one residual log subtlety — the rare
concurrent race-loser dispatches and is therefore journaled as a no-op — is
reconciled under INC-3 below. Distinct from AMB-11 (sequential repeat), AMB-3
(timing), AMB-8 (liveness), and AMB-15 (empty selection) — AMB-17 is the
*concurrent* first-shot atomicity / flag-set-point decision.

## Lifecycle (D4 + integrant)

- Explicit command surface modeled on `project-nrepl`:
  `/dev-http start | status | stop`.
- **Double-`start` (AMB-9)**: `/dev-http start` is **idempotent**. When the
  server is already running, `start` is a **no-op that returns the existing
  server URL** (and reports "already running"); it does **not** start a
  second server, restart, or error. There is **no `restart` command** — to
  restart, `stop` then `start`. This upholds AC-1's no-orphaned-server guarantee.
  The `start` command return is a **non-journaled human-facing command-output
  surface** (same class as `/dev-http status` output and the dev start-up log
  line), so it carries the **token-embedded copy-pasteable URL** per the
  journaled-vs-non-journaled principle (INC-9) — directly resolving INC-9's
  enumeration gap: the `start` return is one of the enumerated non-journaled
  token-embedded surfaces, not a token leak into replayable state. (The fresh
  start-up case likewise surfaces the same token-embedded URL via this channel.)
- **Stop / status when not running (AMB-12)**: the no-server-running edges are
  defined symmetrically with the idempotent `start`. `/dev-http stop` against a
  server that is **not running** is a **no-op success** (reports "not running";
  nothing to halt, **no error**). `/dev-http status` when stopped reports
  **`running? false`** with **no `url` and no `token`** — and the projected
  canonical status is correspondingly `running? false` carrying no `url` (the
  external handle/token are absent). Neither command errors against an
  already-stopped server. (Distinct from AMB-9's start-side idempotency.)
- **integrant** manages the extension-local system
  (`config → registry → router → server`), chosen for clean `halt!`/`init`
  reload ergonomics against the churny `dev/` routes.
- **Boundary**: integrant is scoped strictly inside the `dev-http` extension —
  the integrant system *definition* and its `init`/`halt!` lifecycle code live in
  the extension; no core namespace (`system-bootstrap`, dispatch, core state)
  gains any dev-http-specific integrant code. integrant does not touch core
  state, dispatch, `system-bootstrap`, or any other component.
- **Live server-handle location (AF-8)**: the running integrant
  system/server/registry handle is held as a **runtime-owned managed handle on
  `ctx`, keyed by a logical identity** (e.g. `:dev-http/server`) — **not** in
  extension-private hidden mutable state, and **not** in the core `:state*` atom.
  This reconciles the live-handle location with META.md's managed-services
  principle ("psi runtime owns process-scoped managed services on ctx for
  long-lived subprocesses and similar runtime resources … keyed by logical
  identity … rather than extension-local hidden state") and matches the
  process-wide-**singleton** runtime-handle precedents the AF-7 system-scoping
  points to (nREPL `[:runtime :nrepl]`, the project-nrepl registry). The earlier
  `mcp-tasks-run`/`work-on` citation is the precedent for **session/extension-
  scoped** handles; AF-7 established dev-http is a process-wide singleton, which
  is the **managed-service-on-ctx** shape, not extension-local hidden state.
  Holding the live handle on `ctx` under a logical key means it **survives
  extension reload, cannot be orphaned or duplicated** (one keyed reuse within
  ctx), directly reinforcing AC-1's no-orphaned-server-on-reload/restart
  guarantee — the exact failure the managed-services principle prevents. The
  per-launch **`token` stays external** (AF-4): it is held alongside this
  runtime-owned handle, never projected into canonical `:state*`/log. Note: the
  documented managed-service *transport* surface (`:service-request` /
  `:service-notify`, `:type :subprocess`) is built for **psi-as-client**
  subprocess RPC and does **not** fit an inbound in-process HTTP host (the
  developer's browser is the client, not psi); AF-8 adopts the
  **ownership/location principle** (runtime-owned, on `ctx`, keyed by logical
  identity, reused-not-hidden), with integrant owning the in-extension
  `init`/`halt!` lifecycle under that handle. Whether the extension reaches this
  via `:ensure-service`/`:stop-service` with a non-subprocess type or a narrower
  runtime ctx-handle mechanism is a planning/implementation detail; the *design
  decision* is the live handle's **location/ownership = runtime-owned-on-`ctx`**,
  not extension-local-hidden-state.
- **Server**: http-kit, bound to `127.0.0.1` only. **Ephemeral port** (OS-assigned
  at start; not user-configurable — see O3). A **per-launch token** is required
  for access (dev-grade, not auth); the **token-embedded copy-pasteable URL**
  (base + `?token=…`) and the token are surfaced in the `status` output and the
  dev start-up log line, while the **token-less base URL** is what the canonical
  status / event-log carries (INC-8).
- **Token transport + gated routes (AMB-1)**: the per-launch token is carried as
  a **URL query param** (`?token=…`), so the URL surfaced in the `status` output
  and the dev start-up log line is copy-pasteable and opens directly in a
  browser. **Two URL forms are distinguished (INC-8)** to keep AMB-1's
  copy-pasteable surface from colliding with AF-4/INC-3 token-externality. The
  distinction is governed by a single **journaled-vs-non-journaled principle**
  (INC-8/INC-9/AMB-14), not a fixed surface list:
  - The **token-less base URL** (`http://127.0.0.1:<port>/…`) is the form used in
    **every surface that is journaled into replayable session state or canonical
    state** — canonical `:state*`, the event-log (AF-4/INC-3), and the
    **`dev-present` tool result** (AMB-14, since a tool result is journaled into
    the replayable conversation). It **never** carries the token.
  - The **token-embedded copy-pasteable URL** (base + `?token=…`) is
    **reconstructed at render time** from the base URL plus the external token and
    appears **only in non-journaled human-facing / REPL-local surfaces**: the
    `/dev-http status` output, the dev start-up log line, the idempotent
    `/dev-http start` command return (INC-9), and the `register-route!` REPL
    return value (AMB-14). None of these enter the replayable journal or canonical
    state, so the token never lands in replayable/canonical state.

  Projecting/logging the canonical `url` therefore cannot leak the token, and a
  developer obtains a directly-openable link from any non-journaled surface (e.g.
  `/dev-http status`). The token gates **all dynamic
  content routes** — HTML page routes, the choice POST endpoint, and `:file`
  serving. **Vendored static JS/CSS assets are exempt** (inert, localhost-bound,
  no state access), keeping asset URLs simple. Server-rendered pages propagate
  the token into their own same-origin links and the choice POST.
- **Token-enforcement boundary (AMB-18)**: token validation is enforced as
  **uniform platform middleware** wrapping the **dynamic-route subtrees** — the
  whole `/s/:route-id` session-route subtree and the persisted `dev/` route
  subtree — **not** the individual handler's responsibility. So `register-route!`
  raw-handler routes and persisted `dev/` handlers (both full-power handlers
  emitting arbitrary ring responses the platform cannot classify a priori) are
  **auto-gated by the platform and never see an untokened request** — a raw
  handler cannot accidentally serve untokened dynamic content. The **vendored
  static-asset subtree is the sole exempt path**, served by separate un-gated
  middleware. This pins the enforcement layer for the router build and the AC-7
  tests: the platform router composes the token middleware over the dynamic
  subtrees and excludes the static-asset subtree. AMB-1/INC-6 enumerate token
  enforcement by *content category*; AMB-18 fixes the *enforcement layer*
  (platform middleware over whole route subtrees), so category classification of
  an opaque handler's output is never required. Distinct from AMB-1 (token
  *transport*) and INC-6 (static-asset *exemption wording*).
- **Status projection (AF-2, AF-4, AF-6, AF-7)**: the observable server status —
  `running?` and the resolved **token-less base** `url` (INC-8) — is projected into canonical `:state*` via a
  **first-class `psi.extension/*` dispatch-routed mutation declared in the
  extension's `:allowed-events`** (e.g. `psi.extension/dev-http-set-status`),
  driven by the `/dev-http` lifecycle command handler on `start`/`stop`. The
  nREPL `[:runtime :nrepl]` / OAuth / workflow-progress precedent governs the
  *shape* of the projected status, **not** the dispatching event's ownership: the
  cited nREPL projection event (`:session/set-nrepl-runtime`) is **core-owned**,
  so the untrusted extension must **not** dispatch it — projecting its own status
  through its own declared `psi.extension/*` mutation keeps the AF-3
  untrusted-extension posture (no reach into a core/internal projection event)
  (AF-6). The **per-launch `token` is deliberately
  NOT projected** into canonical state: per the OAuth credential-externality
  precedent (the State-boundary table keeps the credential store external and
  projects only login *status*), the token is a credential-class secret and must
  not land in the replayable event-log / dispatch-trace. The token stays in the
  extension-local handle and is surfaced live via the `status` output / log path
  (which reads the external handle). **Canonical-state scope (AF-7)**: the
  dev-http server is a process-wide **singleton** (one server shared across all
  agent sessions), so its status is projected into **system/runtime-scoped**
  canonical state — queryable system-wide, exactly like the system runtime
  handles `[:runtime :nrepl]` and OAuth login status (AGENTS.md S2:
  `system_scope(¬agent_session_scope)`) — **not** into invoking-session scope.
  It is therefore dispatched so the projection lands in system/runtime scope,
  **not** via the slash-command's session-rebound implicit `(:mutate api)` (which
  doc/extension-api.md rebinds to the invoking session) nor `:mutate-session`;
  a session-rebound projection would mislocate a singleton's status (divergent
  per-session copies, no system-wide "is the server up?" answer). This keeps the
  scope asymmetry explicit: **server status = system-scoped**, while the
  **choice-submit mutation = session-scoped** (`:mutate-session` against the
  AMB-4 feedback target). Only `running?`/`url` status metadata is
  projected into canonical `:state*`; the integrant **system instance/handle (and
  the token)** live as a **runtime-owned managed handle on `ctx` keyed by logical
  identity** (AF-8) — never in the core `:state*` atom — preserving the isolation
  boundary below while reconciling the live-handle location with the
  managed-services principle.
- **System-scoped projection surface (AF-9)**: AF-7 requires the singleton's
  status to land in **system/runtime** scope, but the documented extension mutate
  surfaces are both **session-scoped** — `(:mutate api)` is rebound to the
  invoking session inside a slash-command handler (doc/extension-api.md) and
  `(:mutate-session api)` is explicit-session — so AF-7's "dispatched, not
  session-rebound" decision needs a concrete realizing surface on the
  extension-API contract. Resolution: the extension projects status by dispatching
  its first-class `psi.extension/dev-http-set-status` event through a
  **non-session-rebound, system-scoped dispatch path** — an explicit
  extension-API contract addition in the AF-3/AF-6 first-class-mutation lineage —
  i.e. **not** the slash-command-rebound implicit `(:mutate api)` and **not**
  `(:mutate-session api)`, but a dispatch that carries **no invoking session-id**.
  Its **pure handler writes the system/runtime-scoped `[:runtime :dev-http]`
  `:state*` key directly**, independent of any invoking session, exactly the scope
  the core-owned `[:runtime :nrepl]` projection lands in (AF-7 borrows the
  projected *shape*; AF-9 supplies the extension-side dispatch surface that lands
  it in system scope). The session-rebound `/dev-http` command handler that
  triggers the projection therefore uses **this explicit system-scoped surface**,
  not the ambient session-rebound mutate. This locates AF-7's "system-scoped, not
  session-rebound" decision on a concrete extension-API contract while keeping the
  AF-6 posture (no reach into a core-owned projection event like
  `:session/set-nrepl-runtime`). Distinct from AF-6 (event ownership) and AF-7
  (the scope decision) — AF-9 is the realizing projection surface/mechanism.
- **Route-id collisions**: re-registering an existing session route-id **replaces**
  the prior entry (last-write-wins; least surprising for a dev tool).
- **Client assets**: Vega-Lite / Mermaid client JS is **vendored** and served by
  the extension (offline-safe; no CDN/network dependency).

## Dependencies

New deps (extension-local `deps.edn` for `extensions/dev-http`):

- `metosin/reitit-ring`
- `http-kit`
- `hiccup`
- `integrant`

(`metosin/malli` and `org.commonmark/commonmark` are already on the classpath.)

The extension-local `deps.edn` also declares its own `:dev` alias
(`{:dev {:extra-paths ["dev"]}}`) so persisted routes load from
`extensions/dev-http/dev/`, not the project-global root `:dev` (AF-1).

## Architectural constraints (must hold)

- **Reads via resolvers, writes via mutations.** HTTP GET handlers read only via
  the extension `:query` / `:query-session` API; any state change (notably the
  choice feedback) goes through `:mutate` / `:mutate-session`. No direct core
  state access from HTTP handlers.
- **One-way / no shims.** The extension integrates only through the documented
  extension API map; it does not reach into core namespaces to mutate state.
- **Untrusted-extension posture.** Minimal declared capability surface; declare
  `:allowed-events` for events it dispatches — including the first-class
  `psi.extension/*` choice-submit mutation that wraps
  `:session/submit-synthetic-user-prompt` (AF-3) **and the first-class
  `psi.extension/*` status-projection mutation** that projects `running?`/`url`
  into `:state*` (AF-6). Neither reaches into a core-owned projection event.
- **Status projection, handle + secret externality.** Server status
  (`running?`/token-less base `url`) is projected into **system/runtime-scoped**
  canonical `:state*` via dispatch for introspection — the dev-http server is a
  process-wide **singleton**, so its status is system-scoped like `[:runtime
  :nrepl]` / OAuth login status (`system_scope(¬agent_session_scope)`), not
  invoking-session-scoped, and is dispatched so it lands in system/runtime scope
  rather than via the slash-command's session-rebound implicit `:mutate` /
  `:mutate-session` (AF-7). The realizing surface is a **non-session-rebound,
  system-scoped extension dispatch path** (explicit extension-API contract
  addition, AF-3/AF-6 lineage) carrying no invoking session-id, whose pure handler
  writes the system/runtime-scoped `[:runtime :dev-http]` key directly (AF-9).
  Meanwhile the integrant system instance/handle **and
  the per-launch `token`** live as a **runtime-owned managed handle on `ctx`
  keyed by logical identity** (AF-8) — never the core `:state*` atom (AF-2, AF-4)
  — reconciling the live-handle location with the managed-services principle
  while keeping it out of canonical replayable state. The status-projection
  mutation is itself a **first-class
  `psi.extension/*` dispatch-routed mutation declared in `:allowed-events`**
  (AF-6), exactly like the choice-submit mutation: the extension never dispatches
  the core-owned nREPL projection event (`:session/set-nrepl-runtime`); the nREPL
  `[:runtime :nrepl]` precedent governs only the projected status *shape*. Token
  externality mirrors the OAuth credential-externality precedent (secrets do not
  enter canonical/replayable state). The live token is surfaced via `status`/log
  only.
- **Replay fidelity / log membership (INC-3).** Exactly **two** dev-http mutation
  classes are event-sourced and enter the log: (1) **status-projection
  mutations** (lifecycle `start`/`stop` projecting `running?`/token-less base
  `url` into `:state*` — INC-8), and (2) **interaction-result mutations** —
  **only message-producing** choice submits (a genuine, live-target, non-empty,
  first-shot selection → user message). A **no-op submit** (dead target — AMB-8,
  empty / no selection — AMB-15, or an already-submitted single-shot route —
  AMB-11) is **short-circuited by a pre-dispatch guard in the HTTP choice-POST
  handler**: **no wrapping `psi.extension/*` mutation is dispatched**, so no
  no-op choice mutation is ever event-sourced or recorded in the dispatch
  journal (INC-10). Class (2) therefore admits no *deterministically-known*
  no-op mutation; the log records the message-producing submits. The **single
  exception** is the rare **concurrent first-shot race-loser** (AMB-17): when two
  simultaneous first-shot POSTs both pass the best-effort pre-dispatch guard, the
  loser's dispatch-serialized choice mutation **is** journaled but **no-ops in
  its handler** (adds nothing to the submitted set, produces no message, emits no
  follow-on effect) — the at-most-once funnel rejecting the duplicate,
  precedented by task 224's serialized guarded handler. Every
  *deterministically-known* no-op (dead target, empty, already-submitted-and-
  observed) is still short-circuited pre-dispatch and never event-sourced
  (INC-10); only the unavoidable concurrency-race loser is a journaled no-op.
  Everything else — page GET rendering, route registration, vendored
  asset serving — is **presentation/out-of-band** and excluded from the log (same
  posture as TUI/RPC input being event sources). The non-deterministic
  **token-less base** `url` that enters the log via class (1) is precedented
  (nREPL endpoint metadata is likewise non-deterministic in the log); the secret
  `token` is **excluded** from both classes and never enters the log — the
  token-embedded copy-pasteable URL exists only as a render-time reconstruction
  for the non-journaled human-facing/REPL-local surfaces (status output, dev log
  line, `/dev-http start` return, `register-route!` REPL return — INC-9/AMB-14),
  never in canonical/replayable state (AF-4, INC-8).
- **Determinism boundary.** The live server **process/handle** and runtime
  fn-route registration are side-effecting dev resources outside the
  deterministic core; this is accepted precisely because the extension is
  isolated and dev-only. The *status metadata* projected by class (1) above is
  ordinary event-sourced state (like any other dispatch mutation), not part of
  this non-deterministic boundary — only the live handle and the (excluded)
  token sit outside canonical state.

## Scope

### In scope

- The `dev-http` extension platform: lifecycle command, integrant system,
  http-kit server (localhost + token), reitit router, persisted-route loading
  from the extension-local `extensions/dev-http/dev/`, session-route registry +
  dispatch subtree, and server-status projection into `:state*`.
- The `dev-present` tool + `register-route!` REPL fn.
- The built-in declarative renderer set plus the raw-handler render helpers: the
  hiccup/file escape hatches and the choices interaction helper, reached via
  `register-route!` only (INC-5/INC-7).
- The choice interaction loop back into the originating session via mutation,
  including the small extension-facing mutation wrapping
  `:session/submit-synthetic-user-prompt` (immediate-turn feedback).
- At least one persisted demo route under `extensions/dev-http/dev/` exercising
  the platform end-to-end (slice 1).
- User/dev docs (`doc/`), changelog, tests.

### Out of scope (initially)

- Authentication beyond a localhost bind + per-launch token.
- Persisting session routes to disk (session routes are throwaway by design;
  "promote to a persisted `dev/` route" is a manual dev action, not automated).
- Non-localhost / remote access.
- A general plugin/marketplace model for renderers.
- **SSE / live page updates** (AMB-6). Server-sent-events live-updates are a
  future enhancement, not a deliverable of this task; pages render on request
  only. (Was listed as slicing slice 4; see Slicing.)
- A user-configurable server port (the port is ephemeral OS-assigned; O3/AMB-5).

## Slicing (vertical, behaviour-first)

Each slice delivers an **exercisable end-to-end behaviour**; no slice ships a
mechanism with nothing to exercise it (INC-11). The **session-route registry +
`/s/:route-id` dispatch subtree** is therefore introduced in **slice 2** — the
first slice that delivers a session-route behaviour and a registration surface —
**not** slice 1, where it would be an unexercisable mechanism (a persisted `dev/`
route never touches the session-route registry, and no registration surface
exists until slice 2).

1. **Platform + persisted route** (D5): lifecycle (`start/status/stop`) +
   integrant system + http-kit server (localhost+token) + token-enforcement
   middleware (AMB-18) + status projection + reitit router + one persisted demo
   route under `extensions/dev-http/dev/`. The slice-1 demo route uses
   **platform-only, hand-rolled handler output** (a full-power Clojure handler
   emitting its own HTML directly), independent of the Slice 2 declarative
   renderer set (INC-1). Complete behaviour: open the persisted route in a
   browser. (No session-route registry yet — deferred to slice 2, where it is
   first exercised.)
2. **Session-route registration + renderer set**: the **session-route registry +
   `/s/:route-id` dispatch subtree**, introduced together with its first two
   registration surfaces — the **`dev-present` tool** (declarative renderer set:
   markdown/table/vega/mermaid) and the REPL **`register-route!` fn** (registers
   an arbitrary ring handler fn, plus the **hiccup/file raw-handler escape-hatch
   helpers**, reachable via `register-route!` only — INC-2/INC-5). Both surfaces
   exercise the registry/dispatch introduced in this slice. Complete behaviour:
   register and open a session route from both paths.
3. **Choice interaction loop** (`:choices` renderer + the raw-handler **choices
   helper** (INC-7) + POST → first-class `psi.extension/*` mutation →
   synthetic-user-prompt → user message into originating session).

Out-of-scope future enhancement (not a slice of this task, per AMB-6):

- **SSE live-updates** so pages can track evolving data (e.g. workflow-run
  progress) without manual refresh.

## Acceptance criteria

- AC-1 `/dev-http start` starts a localhost-bound http-kit server on an
  ephemeral OS-assigned port (not user-configurable; O3/AMB-5) and reports its
  URL via `status`; `stop` cleanly halts it (integrant `halt!`), with no
  orphaned server on reload/restart.
- AC-2 A persisted route defined under `extensions/dev-http/dev/` and contributed
  through the conventional entry var (AMB-13) is collected at integrant
  `init`/reload and served by the running server.
- AC-3 The agent can call `dev-present` to register a session route from content
  data and receives back the route's **token-less base URL** (AMB-14; the
  token-embedded openable link is obtained via `/dev-http status`), which — opened
  with the token — renders the content with the selected renderer. Called while
  the server is **not running** (AMB-16), `dev-present` returns an error
  tool-result ("start the server first") and registers nothing.
- AC-4 A dev can register an arbitrary ring handler fn via `register-route!` and
  reach it at its URL. Called while the server is **not running** (AMB-16),
  `register-route!` errors ("start the server first") and registers nothing.
- AC-5 Each declarative renderer (`:markdown`, `:table`, `:vega`, `:mermaid`,
  `:choices`) produces the expected response for representative input (per the
  AMB-10 content shapes), and the raw-handler render helpers (the hiccup and
  file escape hatches plus the choices interaction helper — INC-7) — invoked from
  a `register-route!` handler fn, not `dev-present` (INC-5) — produce the expected
  response for representative input.
- AC-6 Submitting a `:choices` form with a selection posts it, and the selection
  is injected as a mid-conversation **user** message into the originating session
  and drives the agent's next turn. An empty / no-selection submit (AMB-15) is
  rejected as a no-op (nothing injected; the single shot is not consumed).
- AC-7 Access to **dynamic content routes** (HTML page routes, the choice POST
  endpoint, and file serving) requires the per-launch token; **vendored static
  JS/CSS assets are exempt** (inert, localhost-bound — AMB-1). Token enforcement
  is **uniform platform middleware** over the dynamic-route subtrees (the
  `/s/:route-id` session-route subtree and the persisted `dev/` route subtree), so
  `register-route!` raw handlers and persisted `dev/` handlers are auto-gated and
  never see an untokened request; the vendored static-asset subtree is the sole
  exempt path (AMB-18). The server binds to `127.0.0.1` only.
- AC-8 No HTTP handler reads or writes core state except through the extension
  `:query`/`:mutate` API; integrant usage stays inside the extension.
- AC-9 Docs + changelog updated; extension tests pass (Scry) and clj-kondo clean.

## Resolved decisions

- **D3 feedback (O1)** — immediate turn via `:session/submit-synthetic-user-prompt`;
  add a small extension-facing mutation wrapping it.
- **Session scope (O2)** — invoking session only; multi-session out of scope.
- **Port/token (O3)** — ephemeral OS-assigned port; per-launch token; URL + token
  surfaced in `status` and log.
- **Route-id collisions (O4)** — replace (last-write-wins).
- **Client assets (O5)** — vendored Vega-Lite / Mermaid JS; no CDN.

### Design-review follow-up resolutions

- **Persisted-route path (AF-1)** — extension-local `extensions/dev-http/dev/`
  via the extension's own `:dev` extra-path; not the project-global root `:dev`.
  Preserves strict extension isolation.
- **Status projection (AF-2, AF-4)** — project `running?`/`url` into `:state*`
  via dispatch for EQL/psi-tool introspection (nREPL precedent); the integrant
  system handle **and the secret `token`** stay extension-local/external (token
  surfaced via `status`/log only — OAuth credential-externality precedent, AF-4).
- **Choice mutation contract (AF-3)** — a first-class `psi.extension/*`
  dispatch-routed mutation declared in `:allowed-events` wraps
  `:session/submit-synthetic-user-prompt`; no internal-event back-door.
- **Token transport (AMB-1)** — URL query param; gates HTML routes, choice POST,
  `:file` serving; vendored static assets exempt.
- **Route-id assignment (AMB-2)** — optional caller-supplied id (replace on
  collision), else system-generated unique id; same model for both paths.
- **Mid-turn submit (AMB-3)** — queued and delivered as the next user turn (not
  rejected, not interrupting); "immediately" = no manual trigger.
- **REPL feedback target (AMB-4)** — `register-route!` takes an explicit
  `:session-id`; `dev-present` defaults to its invoking session; no target →
  presentation-only route.
- **Port configurability (AMB-5)** — not user-configurable; ephemeral
  OS-assigned (honors O3). AC-1 reworded.
- **SSE scope (AMB-6)** — out of scope; future enhancement (removed from slices,
  added to Out of scope).
- **Slice-1 example (INC-1)** — platform-only hand-rolled handler output,
  independent of the Slice 2 renderer set.
- **`dev-present` renderer restriction (INC-2)** — safe declarative renderers
  only; `:hiccup`/`:file` escape hatches via `register-route!` only.
- **Token externality (AF-4)** — the per-launch `token` is a credential-class
  secret kept in the extension-local handle, not projected into canonical
  `:state*`; surfaced live via `status`/log only. Only `running?`/`url` project
  (OAuth credential-externality precedent).
- **Choice-mutation sequencing (AF-5)** — the `psi.extension/*` choice mutation's
  pure handler emits a `:runtime/dispatch-event` follow-on effect targeting
  `:session/submit-synthetic-user-prompt` (effects-as-data), not an imperative
  in-handler dispatch (Dispatch sequencing contract).
- **Choice selection content (AMB-7)** — option `{:label, :value?}`;
  single-select default, optional `:multi?`; injected user message = selected
  `:value`(s) (joined by `", "` for multi-select), optionally prefixed by a
  `:prompt`.
- **Target liveness + registry lifetime (AMB-8)** — submit to an ended/closed
  target is dropped (no injection; browser told "session no longer active"); the
  session-route registry is cleared on server halt only, not on agent-session
  end. "Die with the server/session" = die with the server.
- **Double-`start` (AMB-9)** — idempotent: already-running `start` is a no-op
  returning the existing server URL (token-embedded copy-pasteable form via the
  non-journaled command-output channel, per INC-9); no second server, no restart,
  no error; no `restart` command.
- **Log membership (INC-3)** — exactly two mutation classes enter the log: status
  projection (`running?`/`url`) and interaction results; presentation /
  registration / asset serving are out-of-band; the `token` never enters the log.
- **Mermaid renderer scope (INC-4)** — Mermaid only; the "(and/or Graphviz)"
  claim is dropped to match the vendored Vega-Lite + Mermaid asset set (O5).
- **Status-projection mutation ownership (AF-6)** — the lifecycle
  `start`/`stop` status projection (`running?`/`url` → `:state*`) is a
  **first-class `psi.extension/*` dispatch-routed mutation declared in
  `:allowed-events`**, like the choice-submit mutation; the nREPL precedent
  governs only the projected *shape*, and the extension never dispatches the
  core-owned `:session/set-nrepl-runtime` event (AF-3 posture upheld).
- **`dev-present` content shapes (AMB-10)** — fixed per-renderer `:content`
  shapes: `:markdown`/`:mermaid` = string, `:vega` = Vega-Lite spec map,
  `:table` = canonical `{:headers [..] :rows [[..] ..]}` (single shape, no
  detection), `:choices` = the AMB-7 choices spec.
- **Repeat choice submission (AMB-11)** — a `:choices` route is **single-shot**:
  the first successful POST injects one user message and marks the route
  submitted; later POSTs no-op with "choice already submitted". A liveness-dropped
  submit (AMB-8) does not consume the shot. New decision presents a new route.
- **Escape-hatch reframing (INC-5)** — `:hiccup`/`:file` are **raw-handler
  render helpers** (functions a `register-route!` handler fn calls), not
  declarative `dev-present` renderer keywords; the declarative renderer set is
  `:markdown`/`:table`/`:vega`/`:mermaid`/`:choices`. AC-5 split accordingly.
- **Token-gating scope (INC-6)** — AC-7 reworded to require the token for
  **dynamic content routes** (HTML pages, choice POST, file serving) and to
  **exempt vendored static JS/CSS assets** (aligns with the AMB-1 resolution).
- **Status-projection scope (AF-7)** — the dev-http server is a process-wide
  **singleton**, so its status (`running?`/token-less base `url`) projects into
  **system/runtime-scoped** canonical state (queryable system-wide like
  `[:runtime :nrepl]` / OAuth login status, both `system_scope`), dispatched so
  it lands in system/runtime scope — **not** the slash-command's session-rebound
  implicit `:mutate` / `:mutate-session`. Scope asymmetry made explicit: status
  is system-scoped, the choice-submit mutation is session-scoped.
- **Stop/status when not running (AMB-12)** — `/dev-http stop` against a stopped
  server is a **no-op success** ("not running", no error); `/dev-http status`
  when stopped reports **`running? false`** with no `url`/`token` (and the
  projected canonical status is `running? false` with no `url`). Symmetric with
  AMB-9's idempotent `start`.
- **Persisted-route discovery (AMB-13)** — persisted routes are collected via a
  **single conventional entry var** (e.g. `dev-http.routes/routes`) returning a
  reitit route-data vector, `require`d/rebuilt at integrant `init`/reload; **no
  namespace auto-scan / marker convention**. AC-2 reworded to reference the entry
  var.
- **register-route! choice feedback (INC-7)** — the platform exposes a third
  raw-handler **choices interaction helper** (alongside the hiccup/file
  escape-hatch helpers) that emits a platform-wired choice form bound to the
  route's `:session-id` (AMB-4); it reuses the platform choice-POST + first-class
  `psi.extension/*` mutation + single-shot (AMB-11) / liveness (AMB-8) machinery.
  `:choices` is thus reachable declaratively via `dev-present` and imperatively
  via `register-route!`; `register-route!`'s `:session-id` is no longer
  vestigial. (hiccup/file remain `register-route!`-only escape hatches.)
- **`url` term disambiguation (INC-8)** — the **token-less base URL** is the
  value used in every journaled/canonical surface (canonical `:state*`,
  event-log per AF-4/INC-3, and the `dev-present` tool result per AMB-14); the
  **token-embedded copy-pasteable URL** (base + `?token=…`) is reconstructed at
  render time from the base URL + external token and appears only in non-journaled
  human-facing/REPL-local surfaces (status output, dev start-up log line,
  `/dev-http start` return per INC-9, `register-route!` REPL return per AMB-14).
  Governed by the journaled-vs-non-journaled principle, not a fixed list, so
  projecting/logging `url` cannot leak the token.
- **Live server-handle location/ownership (AF-8)** — the running integrant
  system/server/registry handle is held as a **runtime-owned managed handle on
  `ctx` keyed by logical identity** (e.g. `:dev-http/server`), **not**
  extension-local hidden state and **not** the core `:state*` atom — reconciling
  the live-handle location with META.md's managed-services principle and the
  process-wide-singleton runtime-handle precedents (nREPL `[:runtime :nrepl]`,
  project-nrepl registry) that AF-7's system-scoping points to. integrant still
  owns the in-extension `init`/`halt!` lifecycle under that handle; holding it on
  `ctx` keyed by logical identity makes it survive extension reload and forbids
  orphan/duplicate (reinforcing AC-1). The token stays external (AF-4) alongside
  the handle. The managed-service *transport* surface (`:type :subprocess`
  request/response) is built for psi-as-client subprocess RPC and is not adopted
  for an inbound in-process HTTP host; AF-8 adopts the ownership/location
  principle only. Supersedes the earlier "extension's own atom/system (as
  mcp-tasks-run/work-on)" framing (those are session/extension-scoped precedents;
  dev-http is a system-scoped singleton).
- **Registration-call return URL form (AMB-14)** — `dev-present` (journaled tool
  result) returns the **token-less base URL** (token-embedded would leak the
  token into replayable state; the developer obtains an openable link via
  `/dev-http status`); `register-route!` (non-journaled REPL return) returns the
  **token-embedded copy-pasteable URL** directly. Governed by the
  journaled-vs-non-journaled principle (INC-8).
- **Empty / no-selection choice submit (AMB-15)** — a `:choices` POST with zero
  options selected is **rejected as a no-op**: nothing injected (no `:prompt`-only
  message either), browser told "no selection", and the AMB-11 single shot is
  **not consumed** (route stays live). Single-select radios render with no
  default-checked option so an explicit choice is required. Mirrors AMB-8's
  drop-doesn't-consume; the single shot is reserved for a genuine decision (AC-6).
- **Token-embedded surface enumeration vs `start` return (INC-9)** — the
  idempotent `/dev-http start` return (AMB-9) is a **non-journaled human-facing
  command-output surface** (same class as `status` output / the log line), so it
  carries the **token-embedded copy-pasteable URL** legitimately. INC-8's closed
  "only status + log line" enumeration is replaced by the
  journaled-vs-non-journaled **principle**, under which the `start` return and the
  `register-route!` REPL return (AMB-14) are admitted non-journaled token-embedded
  surfaces without leaking the token into replayable/canonical state.
- **Registration when the server is not running (AMB-16)** — `dev-present`
  (AC-3) and `register-route!` (AC-4) invoked while `/dev-http` is stopped
  **fail with a clear "dev-http server is not running; start it with `/dev-http
  start`" error**; nothing is registered and **no route URL is returned** (no
  ephemeral-port base URL and, per AF-8, no live `ctx`-keyed registry exist while
  stopped). No **implicit auto-start** (it would conflict with the explicit
  `/dev-http start` command surface and AMB-9's idempotency) and no pre-server
  staging registry (it would break AF-8's "no live registry off `ctx` when
  stopped" and AMB-8's "registry = server lifetime"). The `dev-present` tool
  returns an **error tool-result** naming the remedy (a tool result is journaled,
  so it carries no URL/token); `register-route!` raises / returns an error value
  naming the remedy. Distinct from AMB-9 (already-**running** `start`
  idempotency) and AMB-12 (stop/status command edges when stopped) — AMB-16 is
  the **registration-call** not-running edge.
- **No-op choice-submit log membership / dispatch (INC-10)** — adopts option
  (i): the HTTP choice-POST handler applies a **pre-dispatch guard** (reading
  target-session liveness via `:query-session` and the registry-entry
  selection/single-shot flags) and dispatches the wrapping `psi.extension/*`
  choice-submit mutation **only for a genuine, live-target, non-empty, first-shot
  selection**. A dropped (dead-target — AMB-8), empty / no-selection (AMB-15), or
  already-submitted (AMB-11) POST is **short-circuited before any dispatch**: no
  wrapping mutation is dispatched, so no no-op choice mutation is event-sourced or
  recorded in the dispatch journal. INC-3's class (2) stays cleanly
  **message-producing interaction-result mutations only** (no class-(2)
  amendment needed); AMB-8/AMB-11/AMB-15 wording is corrected away from "the
  wrapping mutation no-ops" to "short-circuited by a pre-dispatch handler guard".
  Distinct from AMB-8 (liveness), AMB-15 (empty selection), AMB-11 (repeat), and
  INC-3 (log classes). (Refined by AMB-17 for the concurrent first-shot race: the
  pre-dispatch guard is best-effort and the authoritative single-shot mark moves
  into the dispatch-serialized mutation; a rare race-loser may dispatch and
  no-op.)
- **System-scoped status-projection surface (AF-9)** — AF-7's system/runtime-
  scoped projection is realized by dispatching the first-class
  `psi.extension/dev-http-set-status` event through a **non-session-rebound,
  system-scoped extension dispatch surface** (an explicit extension-API contract
  addition in the AF-3/AF-6 lineage, carrying no invoking session-id), whose pure
  handler writes the system/runtime-scoped `[:runtime :dev-http]` `:state*` key
  directly — **not** the slash-command-rebound `(:mutate api)` nor
  `(:mutate-session api)` (both session-scoped). The session-rebound `/dev-http`
  command handler uses this explicit system-scoped surface to trigger the
  projection. Locates AF-7's "system-scoped, not session-rebound" decision on a
  concrete extension-API contract without reaching into a core-owned projection
  event (AF-6 upheld). Distinct from AF-6 (event ownership) and AF-7 (scope
  decision).
- **Concurrent first-shot atomicity + single-shot mark location (AMB-17)** —
  following task 224's at-most-once funnel, the **authoritative single-shot mark
  is set inside the dispatch-serialized `psi.extension/*` choice-submit mutation's
  pure handler**, not the HTTP handler: the submitted state is a **canonical
  `:state*` flag** (submitted-route-id set, feedback-session-scoped), so
  atomicity comes from **dispatch serialization** (no test-and-set). The handler
  reads the flag — absent → add id + emit the `:runtime/dispatch-event` follow-on
  (both-or-neither); present → no-op. The INC-10 pre-dispatch HTTP guard is a
  **best-effort fast path** for the deterministically-known no-ops; under a
  concurrent first-shot race it may admit two dispatches, but only the first
  produces a message — guaranteeing **at most one user message per choice route**
  (AMB-11/AC-6). The rare race-loser is the one journaled no-op (reconciled in
  INC-3). Distinct from AMB-11 (sequential repeat), AMB-3 (timing), AMB-8
  (liveness), AMB-15 (empty selection).
- **Token-enforcement boundary (AMB-18)** — token validation is **uniform
  platform middleware** over the dynamic-route subtrees (the `/s/:route-id`
  session-route subtree and the persisted `dev/` route subtree), so
  `register-route!` raw handlers and persisted `dev/` handlers are auto-gated and
  **never see an untokened request** (enforcement is not the individual handler's
  responsibility); the vendored static-asset subtree is the sole exempt path.
  Pins the AC-7 enforcement layer for the router build/tests; classification of an
  opaque handler's output is never required. Distinct from AMB-1 (transport) and
  INC-6 (static-asset exemption wording).
- **Slicing behaviour-first + `register-route!` sliced (INC-11)** — the
  **session-route registry + `/s/:route-id` dispatch** moves out of slice 1
  (where it was an unexercisable mechanism — a persisted `dev/` route never
  touches it and no registration surface existed) into **slice 2**, introduced
  together with its first registration surfaces. **`register-route!`** (+ the
  hiccup/file raw-handler escape-hatch helpers) is now an explicit **slice-2**
  deliverable alongside `dev-present`; the choices helper stays in slice 3. Every
  slice now delivers an exercisable end-to-end behaviour. Distinct from INC-1
  (slice-1 demo-output example vs renderer-set ordering).
