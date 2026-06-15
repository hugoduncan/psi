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
   existing `dev/` source path (`{:dev {:extra-paths ["dev"]}}`). Committed,
   full-power Clojure handlers, reloadable. Dev-only; never in the published jar.
2. **Session routes** — registered at runtime into a registry atom, reached
   through a single stable dispatch subtree (e.g. `/s/:route-id`). Throwaway;
   they die with the server/session. This avoids rebuilding the immutable reitit
   router on every registration.

## Capability surface

### Registration (D1 = both)

- **`dev-present` tool** (model-callable, declarative/data): registers a session
  route from content data and returns its URL. Safe, replay-friendly, the model
  can drive it directly. Targets the built-in renderer set.
- **`register-route!` (REPL/dev, fn-based)**: dev registers an arbitrary ring
  handler fn into the registry for full-power session routes. In-process,
  throwaway, not persisted/replayed.

### Renderers (D2)

Built-in declarative renderers, plus escape hatches:

- `:markdown` — rendered via the existing commonmark dep.
- `:table` — tabular data.
- `:vega` — Vega-Lite spec → chart (client-side lib).
- `:mermaid` — Mermaid (and/or Graphviz) diagram source → diagram.
- `:choices` — a choice form (the interaction primitive; see D3).
- `:hiccup` — raw hiccup escape hatch for arbitrary HTML.
- `:file` — serve an arbitrary file artifact from disk (HTML/SVG/PNG/PDF/…),
  for content produced out-of-band (e.g. a benchmark report).

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
Because that submission path is currently dispatched only internally, a small
extension-facing mutation wrapping it is **in scope** for this task.

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
  at start). A **per-launch token** is required for access (dev-grade, not auth);
  both the resolved URL and token are surfaced in the `status` output and the log.
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

## Architectural constraints (must hold)

- **Reads via resolvers, writes via mutations.** HTTP GET handlers read only via
  the extension `:query` / `:query-session` API; any state change (notably the
  choice feedback) goes through `:mutate` / `:mutate-session`. No direct core
  state access from HTTP handlers.
- **One-way / no shims.** The extension integrates only through the documented
  extension API map; it does not reach into core namespaces to mutate state.
- **Untrusted-extension posture.** Minimal declared capability surface; declare
  `:allowed-events` for events it dispatches.
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
  from `dev/`, session-route registry + dispatch subtree.
- The `dev-present` tool + `register-route!` REPL fn.
- The built-in renderer set incl. hiccup escape hatch and arbitrary-file serving.
- The choice interaction loop back into the originating session via mutation,
  including the small extension-facing mutation wrapping
  `:session/submit-synthetic-user-prompt` (immediate-turn feedback).
- At least one persisted demo route under `dev/` exercising the platform
  end-to-end (slice 1).
- User/dev docs (`doc/`), changelog, tests.

### Out of scope (initially)

- Authentication beyond a localhost bind + per-launch token.
- Persisting session routes to disk (session routes are throwaway by design;
  "promote to a persisted `dev/` route" is a manual dev action, not automated).
- Non-localhost / remote access.
- A general plugin/marketplace model for renderers.

## Slicing (vertical, behaviour-first)

1. **Platform end-to-end** (D5): lifecycle (`start/status/stop`) + integrant
   system + http-kit server (localhost+token) + reitit router + session-route
   registry dispatch + one persisted demo route under `dev/` rendering something
   real (e.g. the EQL graph or a benchmark table).
2. **`dev-present` tool + renderer set** (markdown/table/vega/mermaid/file/hiccup).
3. **Choice interaction loop** (`:choices` renderer + POST → mutation → user
   message into originating session).
4. **SSE live-updates** so pages can track evolving data (e.g. workflow-run
   progress) without manual refresh.

## Acceptance criteria

- AC-1 `/dev-http start` starts a localhost-bound http-kit server on a
  configurable/ephemeral port and reports its URL via `status`; `stop` cleanly
  halts it (integrant `halt!`), with no orphaned server on reload/restart.
- AC-2 A persisted route defined under `dev/` is served by the running server.
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
