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
  throwaway, not persisted/replayed. This is the only path to the `:hiccup` and
  `:file` escape hatches.

**Route-id assignment (AMB-2).** Both `dev-present` and `register-route!` accept
an **optional caller-supplied `:route-id`**. When supplied, it is used verbatim
and re-registering an existing id **replaces** the prior entry (O4,
last-write-wins). When omitted, the platform **generates a unique id**
(collision-free). Both registration paths share this single id model.

### Renderers (D2)

Built-in declarative renderers, plus escape hatches:

- `:markdown` — rendered via the existing commonmark dep.
- `:table` — tabular data.
- `:vega` — Vega-Lite spec → chart (client-side lib).
- `:mermaid` — Mermaid diagram source → diagram. **Mermaid only; no Graphviz**
  — the vendored client-asset set is Vega-Lite + Mermaid only (O5/INC-4).
- `:choices` — a choice form (the interaction primitive; see D3).
- `:hiccup` — raw hiccup escape hatch for arbitrary HTML. **Escape hatch:
  REPL `register-route!` only; not reachable from the `dev-present` tool**
  (INC-2).
- `:file` — serve an arbitrary file artifact from disk (HTML/SVG/PNG/PDF/…),
  for content produced out-of-band (e.g. a benchmark report). **Escape hatch:
  REPL `register-route!` only; not reachable from the `dev-present` tool**
  (INC-2).

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
takes an explicit `:session-id` argument naming the feedback target; if omitted,
the route is **presentation-only** (its `:choices`/POST feedback is disabled —
there is nowhere to deliver the user message).

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
submission is **dropped** (the wrapping mutation no-ops — nothing is injected) and
the browser receives a clear "session no longer active" response. Registry
lifetime is tied to the **server**, not the invoking agent session: the
session-route registry lives in the extension's integrant system and is cleared
only on server **halt** (`/dev-http stop` / reload), **not** when an invoking
agent session ends. A route whose target session has ended therefore remains
served but becomes effectively presentation-only (feedback dropped per above).
"Die with the server/session" thus means *die with the server* (registry =
server lifetime); feedback delivery is independently gated on per-target-session
liveness.

## Lifecycle (D4 + integrant)

- Explicit command surface modeled on `project-nrepl`:
  `/dev-http start | status | stop`.
- **Double-`start` (AMB-9)**: `/dev-http start` is **idempotent**. When the
  server is already running, `start` is a **no-op that returns the existing
  `url` + `token`** (and reports "already running"); it does **not** start a
  second server, restart, or error. There is **no `restart` command** — to
  restart, `stop` then `start`. This upholds AC-1's no-orphaned-server guarantee.
- **integrant** manages the extension-local system
  (`config → registry → router → server`), chosen for clean `halt!`/`init`
  reload ergonomics against the churny `dev/` routes.
- **Boundary**: integrant is scoped strictly inside the `dev-http` extension. It
  does not touch core state, dispatch, `system-bootstrap`, or any other
  component. The server instance/registry live in the extension's own atom/system
  (as `mcp-tasks-run`, `work-on` already do), never in the core state atom.
- **Server**: http-kit, bound to `127.0.0.1` only. **Ephemeral port** (OS-assigned
  at start; not user-configurable — see O3). A **per-launch token** is required
  for access (dev-grade, not auth); both the resolved URL and token are surfaced
  in the `status` output and the log.
- **Token transport + gated routes (AMB-1)**: the per-launch token is carried as
  a **URL query param** (`?token=…`), so the URL surfaced in `status`/log is
  copy-pasteable and opens directly in a browser. The token gates **all dynamic
  content routes** — HTML page routes, the choice POST endpoint, and `:file`
  serving. **Vendored static JS/CSS assets are exempt** (inert, localhost-bound,
  no state access), keeping asset URLs simple. Server-rendered pages propagate
  the token into their own same-origin links and the choice POST.
- **Status projection (AF-2, AF-4)**: the observable server status — `running?`
  and the resolved `url` — is projected into canonical `:state*` via a dispatch
  mutation for EQL/psi-tool introspection, matching the nREPL `[:runtime :nrepl]`
  / OAuth / workflow-progress precedent. The **per-launch `token` is deliberately
  NOT projected** into canonical state: per the OAuth credential-externality
  precedent (the State-boundary table keeps the credential store external and
  projects only login *status*), the token is a credential-class secret and must
  not land in the replayable event-log / dispatch-trace. The token stays in the
  extension-local handle and is surfaced live via the `status` output / log path
  (which reads the external handle). Only `running?`/`url` status metadata is
  projected; the integrant **system instance/handle (and the token) stay
  extension-local/external** (never in the core state atom), preserving the
  isolation boundary below.
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
  `:session/submit-synthetic-user-prompt` (AF-3).
- **Status projection, handle + secret externality.** Server status
  (`running?`/`url`) is projected into canonical `:state*` via dispatch for
  introspection, while the integrant system instance/handle **and the per-launch
  `token`** stay in the extension's own atom/system — never the core state atom
  (AF-2, AF-4). `running?`/`url` projection mirrors the nREPL `[:runtime :nrepl]`
  precedent; token externality mirrors the OAuth credential-externality precedent
  (secrets do not enter canonical/replayable state). The live token is surfaced
  via `status`/log only.
- **Replay fidelity / log membership (INC-3).** Exactly **two** dev-http mutation
  classes are event-sourced and enter the log: (1) **status-projection
  mutations** (lifecycle `start`/`stop` projecting `running?`/`url` into
  `:state*`), and (2) **interaction-result mutations** (choice submits → user
  message). Everything else — page GET rendering, route registration, vendored
  asset serving — is **presentation/out-of-band** and excluded from the log (same
  posture as TUI/RPC input being event sources). The non-deterministic `url` that
  enters the log via class (1) is precedented (nREPL endpoint metadata is
  likewise non-deterministic in the log); the secret `token` is **excluded** from
  both classes and never enters the log (AF-4).
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
- The built-in renderer set incl. hiccup escape hatch and arbitrary-file serving.
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

1. **Platform end-to-end** (D5): lifecycle (`start/status/stop`) + integrant
   system + http-kit server (localhost+token) + status projection + reitit
   router + session-route registry dispatch + one persisted demo route under
   `extensions/dev-http/dev/`. The slice-1 demo route uses **platform-only,
   hand-rolled handler output** (a full-power Clojure handler emitting its own
   HTML directly), independent of the Slice 2 declarative renderer set (INC-1).
2. **`dev-present` tool + renderer set** (markdown/table/vega/mermaid; `:file`
   and `:hiccup` escape hatches reachable via `register-route!` only — INC-2).
3. **Choice interaction loop** (`:choices` renderer + POST → first-class
   `psi.extension/*` mutation → synthetic-user-prompt → user message into
   originating session).

Out-of-scope future enhancement (not a slice of this task, per AMB-6):

- **SSE live-updates** so pages can track evolving data (e.g. workflow-run
  progress) without manual refresh.

## Acceptance criteria

- AC-1 `/dev-http start` starts a localhost-bound http-kit server on an
  ephemeral OS-assigned port (not user-configurable; O3/AMB-5) and reports its
  URL via `status`; `stop` cleanly halts it (integrant `halt!`), with no
  orphaned server on reload/restart.
- AC-2 A persisted route defined under `extensions/dev-http/dev/` is served by
  the running server.
- AC-3 The agent can call `dev-present` to register a session route from content
  data and receives back a URL that renders the content with the selected
  renderer.
- AC-4 A dev can register an arbitrary ring handler fn via `register-route!` and
  reach it at its URL.
- AC-5 Each renderer (`:markdown`, `:table`, `:vega`, `:mermaid`, `:choices`,
  `:hiccup`, `:file`) produces the expected response for representative input.
- AC-6 Submitting a `:choices` form posts the selection, which is injected as a
  mid-conversation **user** message into the originating session and drives the
  agent's next turn.
- AC-7 Access requires the per-launch token; the server binds to `127.0.0.1`
  only.
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
  returning the existing `url`+`token`; no second server, no restart, no error;
  no `restart` command.
- **Log membership (INC-3)** — exactly two mutation classes enter the log: status
  projection (`running?`/`url`) and interaction results; presentation /
  registration / asset serving are out-of-band; the `token` never enters the log.
- **Mermaid renderer scope (INC-4)** — Mermaid only; the "(and/or Graphviz)"
  claim is dropped to match the vendored Vega-Lite + Mermaid asset set (O5).
