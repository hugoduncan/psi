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
   they die with the server/session. This avoids rebuilding the immutable reitit
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
- `:mermaid` — Mermaid (and/or Graphviz) diagram source → diagram.
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

**Extension-API contract for the wrapping mutation (AF-3).** The submission path
(`:session/submit-synthetic-user-prompt`) is currently dispatched only
internally. Rather than reach into that internal event as a back-door, this task
adds a **first-class `psi.extension/*` mutation** (e.g.
`psi.extension/dev-http-submit-choice`) reached through the extension
`:mutate-session` API. It is dispatch-routed and **declared in the extension's
`:allowed-events`**; its handler internally dispatches
`:session/submit-synthetic-user-prompt`. This is an explicit, documented
extension-API contract update (one-way / no shim, untrusted-extension posture),
not an internal-event bridge.

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

## Lifecycle (D4 + integrant)

- Explicit command surface modeled on `project-nrepl`:
  `/dev-http start | status | stop`.
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
- **Status projection (AF-2)**: the observable server status — `running?`, the
  resolved `url`, and the `token` — is projected into canonical `:state*` via a
  dispatch mutation for EQL/psi-tool introspection, matching the nREPL
  `[:runtime :nrepl]` / OAuth / workflow-progress precedent. Only this status
  metadata is projected; the integrant **system instance/handle stays
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
- **Status projection, handle externality.** Server status (`running?`/`url`/
  `token`) is projected into canonical `:state*` via dispatch for introspection,
  while the integrant system instance/handle stays in the extension's own
  atom/system — never the core state atom (AF-2). This mirrors the nREPL
  `[:runtime :nrepl]` precedent.
- **Replay fidelity.** Presentation is out-of-band and excluded from the event
  log; only interaction-result mutations enter the log (same posture as TUI/RPC
  input being event sources).
- **Determinism boundary.** Runtime fn-route registration and the live server are
  side-effecting dev resources outside the deterministic core; this is accepted
  precisely because the extension is isolated and dev-only.

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
- **Status projection (AF-2)** — project `running?`/`url`/`token` into `:state*`
  via dispatch for EQL/psi-tool introspection (nREPL precedent); integrant
  system handle stays extension-local.
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
