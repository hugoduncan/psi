# 229 — Plan

Derived from the stable `design.md`. Design is settled; this plan is the *how*,
sliced behaviour-first per design §Slicing. Keep the blast radius inside the
extension; the only sanctioned core touch is one small extension-facing mutation
(§Slice 3).

## Approach

### Extension layout (new `extensions/dev-http/`)

```
extensions/dev-http/
  deps.edn                         ; extension-local deps + :dev extra-path (scoped)
  src/extensions/dev_http.clj      ; entry-point ns `extensions.dev-http`, (defn init [api] …)
  src/extensions/dev_http/         ; platform namespaces (system, server, router,
                                   ;   registry, renderers, tool, command, choices, sse)
  dev/extensions/dev_http/dev/     ; persisted routes (committed, dev-only, reloadable)
  resources/dev_http/vendor/       ; vendored Vega-Lite + Mermaid client JS
  test/extensions/dev_http/        ; tests
```

Entry-point convention (loader, confirmed): the extension file defines an `init`
fn in its ns; `init` receives the runtime ExtensionAPI map (`:query`,
`:query-session`, `:mutate`, `:mutate-session`, `:register-tool`,
`:register-command`, `:on`, `:notify`, `:path`, `:log`, …). The extension never
reaches into core namespaces — all reads/writes flow through that api map.

### Live handle in the extension's own atom (design decision, locked)

A `(defonce ^:private state (atom {…}))` holds the api map + the running integrant
system. Precedent: `work-on`, `mcp-tasks-run`. **No** core managed-service type,
**no** system-scoped dispatch path is added (explicitly out of scope per design
§"Where the live server handle lives").

### Integrant system (lifecycle ergonomics)

Keys form a linear dependency: `:dev-http/config → :dev-http/registry →
:dev-http/router → :dev-http/server`.

- `:dev-http/config` — resolved settings: bind host `127.0.0.1`, port `0`
  (ephemeral), freshly generated per-launch token, persisted-route source dir,
  the captured api map.
- `:dev-http/registry` — the session-route registry atom (`route-id → entry`),
  shared with `register-route!` / `dev-present`. Plain atom built by `init-key`.
- `:dev-http/router` — a reitit-ring handler built from (a) persisted routes
  loaded from the extension-local `dev/` source and (b) the stable
  `/s/:route-id` dispatch subtree that resolves against the registry atom at
  request time (so session-route churn never rebuilds the immutable router).
  Token middleware wraps each dynamic subtree.
- `:dev-http/server` — http-kit server bound to config; `halt-key!` calls the
  http-kit stop fn. Resolved URL + token surfaced to `status` and the log.

`start` = `(ig/init system-config)` stored in the atom; `stop` =
`(ig/halt! system)`; idempotent (halt any prior system before init on restart so
no orphaned server survives reload — AC-1).

### Lifecycle command surface

Register one prefixed command `/dev-http` via `(:register-command api)` with a
handler that parses `start | status | stop` (subcommand dispatch in the
extension, modeled on `project-nrepl`'s `/project-repl start|status|stop`).
`status` reports running?/URL/token (or "not running").

### Router / route sources

1. **Persisted routes**: scan the extension-local `dev/` path for route-defining
   namespaces (reitit route-data vectors + handler fns), required at router-build
   time. Exposed only via the extension's own `deps.edn` `:dev` extra-path — never
   the project-global root `:dev`. Dev-only; never in a published jar.
2. **Session routes**: a registry atom; `/s/:route-id` looks up the entry and
   dispatches its handler. Re-registering an existing route-id **replaces** the
   entry (last-write-wins). Throwaway; dies with the server.

Token middleware gates **every** dynamic subtree (persisted `dev/` subtree and
`/s/` subtree). Token supplied via query param or header; mismatch → 403.

### Registration surfaces

- **`dev-present` tool** (`:register-tool`): declarative content map →
  `{:renderer … :data …}`, registers a session route, returns its URL. Model-callable,
  replay-friendly. Targets the built-in renderer set.
- **`register-route!`** (public REPL/dev fn in the extension ns): registers an
  arbitrary ring handler fn into the registry. In-process, throwaway.

### Renderers (Slice 2)

A renderer multimethod / map keyed by `:renderer`:
`:markdown` (org.commonmark, already on classpath) · `:table` (hiccup table) ·
`:vega` (HTML page embedding vendored Vega-Lite JS + the spec) · `:mermaid`
(HTML page embedding vendored Mermaid JS + the source) · `:hiccup` (raw hiccup →
HTML) · `:file` (serve a file artifact from disk with content-type by extension)
· `:choices` (Slice 3). Vega/Mermaid assets are **vendored** under
`resources/dev_http/vendor/` and served by the extension (no CDN/network).

### Choice interaction loop (Slice 3)

- `:choices` renderer emits a form `POST`-ing the selection to the route's submit
  endpoint (within the token-gated subtree).
- The POST handler reads only via `:query-session` and writes via
  `:mutate-session`, calling a **new small extension-facing mutation** that wraps
  the internal `:session/submit-synthetic-user-prompt` dispatch (params
  `{:session-id … :user-msg …}`, returns `{:submitted? …}` — same path the
  scheduler uses). This injects a mid-conversation **user** message into the
  invoking session and drives its next turn immediately.
  - This mutation is the **one sanctioned core touch**: add
    `psi.extension/submit-synthetic-prompt` to `components/agent-session`
    mutations (alongside `send-prompt`/`append-message`), output
    `{:psi.extension/prompt-submitted? …}`. It is *not* the managed-service
    surface that is out of scope. Existing `psi.extension/send-prompt` uses the
    `deliver-extension-prompt!` path, **not** `submit-synthetic-user-prompt`, so a
    new wrapper is required (design §Interaction confirms this is in scope).
- **Single-shot guard (flag storage decision — LOCKED):** the answered flag lives
  in the **session-route registry entry** in the extension atom
  (`{:answered? true :answer …}` set on first successful submit). Rationale:
  single developer, localhost, no multi-writer concurrency → no external store or
  hardening needed (design defers mechanism to planning; this is the proportionate
  choice). A second submit to an already-answered route → rejected with an
  "already answered" page; at most one user message injected per prompt.
- Routes target the **invoking session only** (registry entry captures the
  origin session-id at registration time).

### SSE live-updates (Slice 4)

A `text/event-stream` endpoint (within the token-gated subtree) using http-kit's
async channel. A route may expose an SSE feed; pages subscribe via `EventSource`
to receive pushed updates without manual refresh. Kept minimal: one demonstrated
feed (e.g. registry/route data evolving), no general pub/sub framework.

### Wiring / install

- `extensions/dev-http/deps.edn`: extension-local deps
  (`metosin/reitit-ring`, `http-kit`, `hiccup`, `integrant`); `:dev` alias /
  extra-path for the scoped `dev/` source; `:test` alias with
  `extension-test-helpers`. (`malli`, `commonmark` already on classpath.)
- `extensions/deps.edn`: add `psi/dev-http {:local/root "dev-http"}` and the
  test extra-path.
- root `deps.edn`: add `extensions/dev-http/src` to the relevant extra-paths.
- `.psi/extensions.edn`: add `psi/dev-http {}` to enable install (dev-time).

### Architectural constraints honoured

- Reads via `:query`/`:query-session`; writes via `:mutate`/`:mutate-session`.
  No HTTP handler touches core state directly (AC-9).
- One-way / no shims: integration only through the documented api map.
- Minimal declared `:allowed-events` (only what the extension dispatches —
  notably the synthetic-prompt path); verify gating at implementation time.
- Presentation is out-of-band, excluded from the event log; only the
  interaction-result mutation enters the log (replay fidelity).
- Runtime fn-route registration + the live server are accepted side-effecting
  dev resources outside the deterministic core (isolated, dev-only).

## Risks

- **R1 — New-dep classpath wiring.** reitit-ring/http-kit/hiccup/integrant must
  resolve on both runtime and test classpaths via the extension-local deps.edn +
  root extra-paths. Mitigation: wire + `clj -P`/REPL-load smoke before Slice 1
  logic.
- **R2 — Orphaned server on reload (AC-1).** integrant restart must `halt!` any
  prior system first. Mitigation: idempotent start that halts existing
  `:system` in the atom before init; explicit reload test.
- **R3 — Token gating completeness (AC-8).** Every dynamic subtree (persisted +
  session + submit + SSE) must be token-gated; an ungated leaf is a hole.
  Mitigation: apply middleware at the subtree root, test 403 on each subtree.
- **R4 — Core mutation scope creep.** The synthetic-prompt wrapper is the only
  core change; keep it a thin dispatch wrapper mirroring `send-prompt`, no new
  semantics. Mitigation: review diff stays in `mutations/*` + `all-mutations`.
- **R5 — http-kit test flakiness.** Socket-bound integration tests can be flaky.
  Mitigation: unit-test renderers/router/registry as pure ring handlers (no
  socket); one real ephemeral-port boot for AC-1/2/3/6/7/8 integration, using the
  nullable extension API for the `:query`/`:mutate` seam (testing-without-mocks:
  http server is genuine, the extension-api seam is nullable).
- **R6 — Single-shot race.** Accepted as non-issue (localhost single-user). The
  registry-entry flag is set on first successful submit; concurrent double-submit
  is out of scope per design.
- **R7 — Vendored JS correctness/offline.** Pin specific Vega-Lite + Mermaid
  builds, serve from resources, verify pages render with no network. Mitigation:
  assert asset routes return the vendored bytes; document versions.
- **R8 — `allowed-events` gating.** Unclear whether the extension must declare
  `:session/submit-synthetic-user-prompt` in its allowed-events or whether the
  core mutation carries its own authority. Mitigation: determine at implementation
  time; declare the minimal set that makes the submit path work, no broader.

## Slice order (vertical, behaviour-first)

0. **Scaffold + wiring** — extension dir, deps, install entry, entry-point `init`,
   classpath smoke (de-risks R1; no user-visible behaviour yet).
1. **Platform end-to-end** — `/dev-http start|status|stop`, integrant system
   (config→registry→router→server), http-kit on `127.0.0.1`+ephemeral port+token,
   reitit-ring router, persisted-route loading from extension-local `dev/`,
   session-route registry + `/s/:route-id` dispatch, token middleware on each
   dynamic subtree, **one persisted demo route** rendering something real.
   (AC-1, AC-2, AC-4 partial, AC-8.)
2. **`dev-present` tool + renderer set** — markdown/table/vega/mermaid/file/hiccup
   with vendored Vega/Mermaid JS; `register-route!` REPL fn; last-write-wins
   replace. (AC-3, AC-4, AC-5.)
3. **Choice interaction loop** — `:choices` renderer + new
   `psi.extension/submit-synthetic-prompt` core mutation + registry-entry
   single-shot guard; injection as immediate-turn user message into the invoking
   session. (AC-6, AC-7.)
4. **SSE live-updates** — `text/event-stream` endpoint within the token-gated
   subtree; one demonstrated live feed.

Cross-cutting close-out (after Slice 4): docs (`doc/`), changelog, full Scry +
clj-kondo green. (AC-9, AC-10.)
