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

1. **Persisted routes** — reitit route-data + handler namespaces under an
   **extension-local** `extensions/dev-http/dev/` source path, exposed via the
   extension's own `deps.edn` (a `:dev` extra-path scoped to the extension — not
   the project-global root `:dev`/`dev/` alias). This keeps the extension
   strictly isolated: it does not reach a project-global source location.
   Committed, full-power Clojure handlers, reloadable. Dev-only; never in the
   published jar.
2. **Session routes** — registered at runtime into a registry atom, reached
   through a single stable dispatch subtree (e.g. `/s/:route-id`). Throwaway;
   they die with the server/session. This avoids rebuilding the immutable reitit
   router on every registration. Re-registering an existing route-id **replaces**
   the prior entry (last-write-wins).

## Capability surface

### Registration (both declarative and fn-based)

- **`dev-present` tool** (model-callable, declarative/data): registers a session
  route from content data and returns its URL. Safe, replay-friendly, the model
  can drive it directly. Targets the built-in renderer set.
- **`register-route!` (REPL/dev, fn-based)**: dev registers an arbitrary ring
  handler fn into the registry for full-power session routes. In-process,
  throwaway, not persisted/replayed.

### Renderers

Built-in declarative renderers, plus escape hatches:

- `:markdown` — rendered via the existing commonmark dep.
- `:table` — tabular data.
- `:vega` — Vega-Lite spec → chart (vendored client JS).
- `:mermaid` — Mermaid diagram source → diagram (vendored client JS).
- `:choices` — a choice form (the interaction primitive; see below).
- `:hiccup` — raw hiccup escape hatch for arbitrary HTML.
- `:file` — serve an arbitrary file artifact from disk (HTML/SVG/PNG/PDF/…),
  for content produced out-of-band (e.g. a benchmark report).

Vega-Lite / Mermaid client JS is **vendored** and served by the extension
(offline-safe; no CDN/network dependency).

### Interaction

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

- The choice submission appears as a **user** message (the human's decision is
  genuine user input) and **drives the agent's next turn immediately**. This
  targets the `:session/submit-synthetic-user-prompt` path (the same mechanism
  the scheduler uses for delayed prompts), not the append-only `append-message`
  path. Because that path is currently dispatched only internally, a small
  extension-facing mutation wrapping it is **in scope**.
- A `:choices` route is **single-shot**: once a decision has been submitted,
  further submissions to that route are rejected (the page shows "already
  answered"). Exactly one user message is injected per choice prompt. The storage
  location of the answered flag is an implementation/planning concern (single
  developer, localhost — no multi-writer concurrency hardening required).
- Routes target the **invoking session only**; multi-session targeting is out of
  scope.

## Lifecycle

- Explicit command surface modeled on `project-nrepl`:
  `/dev-http start | status | stop`.
- **integrant** manages the extension-local system
  (`config → registry → router → server`), chosen for clean `halt!`/`init`
  reload ergonomics against the churny `dev/` routes.
- **Server**: http-kit, bound to `127.0.0.1` only. **Ephemeral** OS-assigned
  port. A **per-launch token** is required for access (dev-grade, not auth); the
  resolved URL and token are surfaced in `status` output and the log.

## Where the live server handle lives (deliberate decision)

The integrant system / server / registry handle is held in the **extension's own
atom**, following the existing extension precedent (`work-on`, `mcp-tasks-run`),
**not** in the core state atom and **not** as a new core managed-service type.

This is a deliberate trade-off against META.md's managed-services principle
("psi runtime owns process-scoped managed services on ctx"). For a **dev-only,
localhost, strictly-isolated** extension, introducing generic core extension-API
surface (a system-scoped dispatch path and a new managed-handle service type)
solely to host a dev convenience is disproportionate and would couple core to a
dev tool. The extension-atom approach keeps the blast radius inside the
extension, consistent with the isolated-mini-VSM extension posture. If a future
non-dev managed-service need arises, that generic surface can be designed on its
own merits — it is explicitly **out of scope** here.

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
  http-kit server (localhost + ephemeral port + token), reitit router,
  persisted-route loading from `extensions/dev-http/dev/`, session-route
  registry + dispatch subtree.
- The `dev-present` tool + `register-route!` REPL fn.
- The built-in renderer set incl. hiccup escape hatch and arbitrary-file serving,
  with vendored Vega/Mermaid client JS.
- The choice interaction loop back into the originating session via a small
  extension-facing mutation wrapping `:session/submit-synthetic-user-prompt`
  (immediate-turn user message), single-shot per choice route.
- At least one persisted demo route under `extensions/dev-http/dev/` exercising
  the platform end-to-end (slice 1).
- User/dev docs (`doc/`), changelog, tests.

### Out of scope

- Authentication beyond a localhost bind + per-launch token.
- Persisting session routes to disk (throwaway by design; "promote to a persisted
  `dev/` route" is a manual dev action).
- Non-localhost / remote access; multi-session targeting.
- New generic core managed-service / system-scoped dispatch surface (see "Where
  the live server handle lives").
- A general plugin/marketplace model for renderers.

## Slicing (vertical, behaviour-first)

1. **Platform end-to-end**: lifecycle (`start/status/stop`) + integrant system +
   http-kit server (localhost + ephemeral port + token) + reitit router +
   session-route registry dispatch + one persisted demo route under
   `extensions/dev-http/dev/` rendering something real (e.g. the EQL graph or a
   benchmark table). Token middleware gates each dynamic subtree as it is added.
2. **`dev-present` tool + renderer set** (markdown/table/vega/mermaid/file/hiccup).
3. **Choice interaction loop** (`:choices` renderer + the extension-facing
   submit-synthetic-user-prompt mutation + single-shot guard).
4. **SSE live-updates** so pages can track evolving data (e.g. workflow-run
   progress) without manual refresh.

## Acceptance criteria

- AC-1 `/dev-http start` starts a localhost-bound http-kit server on an ephemeral
  port and reports its URL + token via `status`; `stop` cleanly halts it
  (integrant `halt!`), with no orphaned server on reload/restart.
- AC-2 A persisted route defined under `extensions/dev-http/dev/` is served by the
  running server.
- AC-3 The agent can call `dev-present` to register a session route from content
  data and receives back a URL that renders the content with the selected
  renderer.
- AC-4 A dev can register an arbitrary ring handler fn via `register-route!` and
  reach it at its URL; re-registering a route-id replaces the prior entry.
- AC-5 Each renderer (`:markdown`, `:table`, `:vega`, `:mermaid`, `:choices`,
  `:hiccup`, `:file`) produces the expected response for representative input,
  with Vega/Mermaid assets served locally (no network).
- AC-6 Submitting a `:choices` form posts the selection, which is injected as a
  mid-conversation **user** message into the originating session and drives the
  agent's next turn.
- AC-7 A `:choices` route is single-shot: a second submission is rejected and at
  most one user message is injected per prompt.
- AC-8 Access requires the per-launch token; the server binds to `127.0.0.1`
  only; each dynamic subtree is token-gated.
- AC-9 No HTTP handler reads or writes core state except through the extension
  `:query`/`:mutate` API; integrant usage and the live handle stay inside the
  extension.
- AC-10 Docs + changelog updated; extension tests pass (Scry) and clj-kondo clean.

## Resolved decisions

- Persisted-route path → extension-local `extensions/dev-http/dev/` (AF-1).
- Choice feedback → immediate turn via a small extension-facing mutation wrapping
  `:session/submit-synthetic-user-prompt`.
- `:choices` routes are single-shot; flag-storage mechanism deferred to planning.
- Live server handle → extension-owned atom (precedent), not a new core
  managed-service surface (deliberate trade-off; documented above).
- Session scope → invoking session only.
- Port → ephemeral; per-launch token surfaced in `status` + log.
- Route-id collisions → replace (last-write-wins).
- Vega/Mermaid client JS → vendored; no CDN.
