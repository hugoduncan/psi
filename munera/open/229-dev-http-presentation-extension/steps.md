# 229 — Steps

Checklist derived from `plan.md`. Tick items as completed; note sha/decisions
inline. Grouped by slice. Each slice ends green (focused Scry + clj-kondo) before
the next begins.

## Slice 0 — Scaffold + wiring

- [ ] Create `extensions/dev-http/` dir tree (`src/extensions/`, `dev/`,
      `resources/dev_http/vendor/`, `test/extensions/`).
- [ ] Write `extensions/dev-http/deps.edn`: `:paths ["src"]`, deps
      (`metosin/reitit-ring`, `http-kit`, `hiccup`, `integrant`, clojure, the
      psi components the api/tests need), a scoped `:dev` alias adding the `dev`
      extra-path, and a `:test` alias with `extension-test-helpers` + kaocha.
- [ ] Add `psi/dev-http {:local/root "dev-http"}` to `extensions/deps.edn`
      `:deps` and `"dev-http/test"` to its `:test` `:extra-paths`.
- [ ] Add `extensions/dev-http/src` to the relevant `:extra-paths` blocks in root
      `deps.edn`.
- [ ] Add `psi/dev-http {}` to `.psi/extensions.edn` `:deps`.
- [ ] Create entry-point `extensions/dev-http/src/extensions/dev_http.clj` with
      ns `extensions.dev-http`, a `(defonce ^:private state (atom {…}))`, and a
      stub `(defn init [api] …)` capturing the api map into the atom.
- [ ] Smoke: resolve deps + REPL-load the entry-point ns with no errors
      (de-risk R1).
- [ ] Add a minimal `dev_http_test` asserting `init` captures the api map
      (nullable extension API). Focused Scry green; clj-kondo clean.
- [ ] Commit: `⚒ 229: scaffold dev-http extension + wiring`.

## Slice 1 — Platform end-to-end

- [ ] `dev_http/config.clj`: build config (host `127.0.0.1`, port `0`,
      generate per-launch token, persisted-route source dir, api map).
- [ ] `dev_http/registry.clj`: session-route registry atom + `register-entry!`
      (last-write-wins by route-id), `get-entry`, `entries`, `route-url`.
- [ ] `dev_http/router.clj`: reitit-ring router builder combining persisted
      routes + the stable `/s/:route-id` dispatch subtree (looks up registry at
      request time); `not-found` handler.
- [ ] `dev_http/middleware.clj`: token middleware (read token from query
      param/header; mismatch → 403); applied at each dynamic subtree root.
- [ ] `dev_http/system.clj`: integrant `init-key`/`halt-key!` for
      `:dev-http/config`, `:dev-http/registry`, `:dev-http/router`,
      `:dev-http/server` (http-kit start/stop); `:server` exposes resolved
      port/URL.
- [ ] Persisted-route loader: scan extension-local `dev/` for route-defining
      namespaces; require + collect their reitit route-data at router-build time.
- [ ] One persisted demo route under
      `extensions/dev-http/dev/extensions/dev_http/dev/` rendering something real
      (e.g. EQL graph or a benchmark table via the platform).
- [ ] `init`: register `/dev-http` command (handler parses `start|status|stop`);
      `start` = idempotent integrant init (halt prior `:system` first); `status`
      = running?/URL/token; `stop` = `ig/halt!`.
- [ ] Declare minimal `:allowed-events` for the extension (resolve gating per R8).
- [ ] Tests: registry last-write-wins; router dispatch for persisted + `/s/`
      routes (pure ring handler, no socket); token middleware 403 on each subtree;
      integration boot on ephemeral port → request demo route 200 (AC-2),
      `start`/`status`/`stop` lifecycle with no orphaned server on restart
      (AC-1), `127.0.0.1` bind + token required (AC-8).
- [ ] Focused Scry green; clj-kondo clean.
- [ ] Commit: `⚒ 229: platform end-to-end (lifecycle + server + router + registry + demo route)`.

## Slice 2 — `dev-present` tool + renderer set

- [ ] `dev_http/renderers.clj`: dispatch on `:renderer` →
      `:markdown` (commonmark) · `:table` (hiccup) · `:hiccup` (raw) ·
      `:file` (serve disk artifact, content-type by extension) ·
      `:vega` (HTML embedding vendored Vega-Lite JS + spec) ·
      `:mermaid` (HTML embedding vendored Mermaid JS + source).
- [ ] Vendor Vega-Lite + Mermaid client JS into
      `resources/dev_http/vendor/`; add asset routes serving them locally; pin +
      document versions.
- [ ] `dev_http/tool.clj`: `dev-present` tool (`:register-tool`) — content map
      `{:renderer … :data …}` → register session route → return URL.
- [ ] Public `register-route!` REPL/dev fn registering an arbitrary ring handler
      into the registry (in-process, throwaway).
- [ ] Wire `dev-present` + `register-route!` to last-write-wins replace on
      route-id collision.
- [ ] Tests: each renderer produces expected response for representative input
      (AC-5); Vega/Mermaid assets served locally with no network (AC-5);
      `dev-present` returns a URL that renders the content (AC-3);
      `register-route!` reachable + re-register replaces prior entry (AC-4).
- [ ] Focused Scry green; clj-kondo clean.
- [ ] Commit: `⚒ 229: dev-present tool + renderer set (vendored vega/mermaid)`.

## Slice 3 — Choice interaction loop

- [ ] Core mutation: add `psi.extension/submit-synthetic-prompt` in
      `components/agent-session` mutations (thin wrapper dispatching
      `:session/submit-synthetic-user-prompt` with `{:session-id … :user-msg …}`,
      returning `{:psi.extension/prompt-submitted? …}`); add to `all-mutations`.
      Keep it minimal — no new semantics (R4).
- [ ] Core mutation test (agent-session): wrapper injects a mid-conversation
      **user** message into the target session and reports submitted? (mirror the
      scheduler/prompt-lifecycle test style).
- [ ] `dev_http/choices.clj`: `:choices` renderer emits a token-gated form
      POSTing the selection to the route's submit endpoint.
- [ ] Submit handler: read via `:query-session`, write via `:mutate-session`
      calling `psi.extension/submit-synthetic-prompt`; capture origin session-id
      from the registry entry (invoking-session-only).
- [ ] Single-shot guard: store `{:answered? true :answer …}` in the registry
      entry on first successful submit; second submit → "already answered" page,
      no second injection.
- [ ] Tests: submitting a `:choices` form injects exactly one user message into
      the originating session and drives its next turn (AC-6); second submission
      rejected, at most one injection (AC-7); handler uses only `:query`/`:mutate`
      api (AC-9).
- [ ] Focused Scry green; clj-kondo clean.
- [ ] Commit: `⚒ 229: choice interaction loop (submit-synthetic mutation + single-shot)`.

## Slice 4 — SSE live-updates

- [ ] `dev_http/sse.clj`: `text/event-stream` endpoint within the token-gated
      subtree using http-kit async channels.
- [ ] One demonstrated live feed (e.g. registry/route data evolving) pushed to
      subscribed pages via `EventSource`.
- [ ] Tests: SSE endpoint is token-gated; a connected client receives a pushed
      update (integration, ephemeral port).
- [ ] Focused Scry green; clj-kondo clean.
- [ ] Commit: `⚒ 229: SSE live-updates`.

## Close-out (cross-cutting)

- [ ] Verify no HTTP handler reads/writes core state except via
      `:query`/`:mutate`; integrant + live handle stay inside the extension
      (AC-9).
- [ ] Docs: add `doc/dev-http.md` (lifecycle, renderers, choices, SSE, token,
      dev-only posture) and link from the docs index.
- [ ] Changelog: `[Unreleased]` Added entry for the dev-http extension.
- [ ] Update `mementum/state.md` capabilities section to mention dev-http.
- [ ] Full Scry suite green; clj-kondo clean across extension + touched core.
- [ ] Commit: `⚒ 229: docs + changelog + coherence`.
