# 229 — Steps

Checklist derived from `plan.md`. Tick items as completed; note sha/decisions
inline. Grouped by slice. Each slice ends green (focused Scry + clj-kondo) before
the next begins.

## Slice 0 — Scaffold + wiring

- [x] Create `extensions/dev-http/` dir tree (`src/extensions/`, `dev/`,
      `resources/dev_http/vendor/`, `test/extensions/`).
- [x] Write `extensions/dev-http/deps.edn`: `:paths ["src"]`, deps
      (`metosin/reitit-ring`, `http-kit`, `hiccup`, `integrant`, clojure,
      `psi/agent-session`), a scoped `:dev` alias adding the `dev` extra-path,
      and a `:test` alias with `extension-test-helpers` + kaocha.
- [x] Add `psi/dev-http {:local/root "dev-http"}` to `extensions/deps.edn`
      `:deps` and `"dev-http/test"` to its `:test` `:extra-paths`.
- [x] Add `extensions/dev-http/src` to the relevant `:extra-paths` blocks in root
      `deps.edn` (also `tests.edn` suite source/test paths; new deps added to root
      `:deps`; launcher catalog `psi-owned-extension-catalog` entry).
- [x] Add `psi/dev-http {}` to `.psi/extensions.edn` `:deps`.
- [x] Create entry-point `extensions/dev-http/src/extensions/dev_http.clj` with
      ns `extensions.dev-http`, a `(defonce ^:private state (atom {…}))`, and a
      stub `(defn init [api] …)` capturing the api map into the atom.
- [x] Smoke: resolve deps + REPL-load the entry-point ns with no errors
      (de-risk R1).
- [x] Add a minimal `dev_http_test` asserting `init` captures the api map
      (nullable extension API). Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: scaffold dev-http extension + wiring` (39086baec).

## Slice 1 — Platform end-to-end

- [x] `dev_http/config.clj`: build config (host `127.0.0.1`, port `0`,
      generate per-launch token, api map). `route-url` lives in the entry-point
      ns (needs the live server's resolved port/token), not config.
- [x] `dev_http/registry.clj`: session-route registry atom + `register-entry!`
      (last-write-wins by route-id), `get-entry`, `entries`.
- [x] `dev_http/router.clj`: reitit-ring router builder combining persisted
      routes + the stable `/s/:route-id` dispatch subtree (looks up registry at
      request time); `create-default-handler` 404.
- [x] `dev_http/middleware.clj`: token middleware (read token from query
      string/header; mismatch → 403); applied at each dynamic subtree root.
- [x] `dev_http/system.clj`: integrant `init-key`/`halt-key!` for
      `:dev-http/config`, `:dev-http/registry`, `:dev-http/router`,
      `:dev-http/server` (http-kit start/stop); `:server` exposes resolved
      port/URL/token.
- [x] Persisted-route loader (`dev_http/routes.clj`): scan extension-local `dev/`
      for `extensions.dev-http.dev.*` namespaces; require + collect their `routes`
      var (reitit route-data) at router-build time.
- [x] One persisted demo route under
      `extensions/dev-http/dev/extensions/dev_http/dev/demo.clj` (hiccup HTML page).
- [x] `init`: register `/dev-http` command (handler parses `start|status|stop`);
      `start` = idempotent integrant init (halt prior `:system` first); `status`
      = running?/URL/token; `stop` = `ig/halt!`.
- [x] Declare minimal `:allowed-events`: none required this slice — Slice 1
      dispatches no core events (uses api-level `:register-command`/`:log` only).
      Revisit at Slice 3 (synthetic-prompt path, R8).
- [x] Tests: registry last-write-wins; router dispatch for persisted + `/s/`
      routes (pure ring handler, no socket); token middleware 403 on each subtree;
      integration boot on ephemeral port → request demo route 200 (AC-2),
      `start`/`status`/`stop` lifecycle with no orphaned server on restart
      (AC-1), `127.0.0.1` bind + token required (AC-8).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: platform end-to-end (lifecycle + server + router + registry + demo route)` (dc68345fc).

## Slice 2 — `dev-present` tool + renderer set

- [x] `dev_http/renderers.clj`: dispatch on `:renderer` →
      `:markdown` (commonmark) · `:table` (hiccup) · `:hiccup` (raw) ·
      `:file` (serve disk artifact, content-type by extension) ·
      `:vega` (HTML embedding vendored Vega-Lite JS + spec) ·
      `:mermaid` (HTML embedding vendored Mermaid JS + source).
      Dispatch via a plain `:renderer → fn` map (no multimethod). `data`
      accessors read keyword *or* string keys (REPL vs JSON-tool input).
- [x] Vendor Vega-Lite + Mermaid client JS into
      `resources/dev_http/vendor/`; add an **ungated** `/assets/:asset` route
      serving them locally; pin + document versions (vega 5.30.0,
      vega-lite 5.21.0, vega-embed 6.26.0, mermaid 10.9.1). Added
      `extensions/dev-http/resources` to all classpath blocks.
- [x] `dev_http/tool.clj`: `dev-present` tool (`:register-tool`) — content map
      `{:renderer … :data …}` → register session route → return URL. Takes a
      `register-content!` seam fn; nil return ⇒ "server not running" error.
- [x] Public `register-route!` REPL/dev fn registering an arbitrary ring handler
      into the registry (already present from Slice 1; added
      `register-content-route!` for declarative content).
- [x] Wire `dev-present` + `register-route!` to last-write-wins replace on
      route-id collision (registry `register-entry!` semantics).
- [x] Tests: each renderer produces expected response for representative input
      (AC-5); Vega/Mermaid assets served locally with no network (AC-5,
      byte-equality + over-the-wire ungated fetch); `dev-present` returns a URL
      that renders the content (AC-3); `register-route!` reachable + re-register
      replaces prior entry (AC-4).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: dev-present tool + renderer set (vendored vega/mermaid)`.

## Slice 3 — Choice interaction loop

- [x] Core mutation: add `psi.extension/submit-synthetic-prompt` in
      `components/agent-session` mutations (thin wrapper dispatching
      `:session/submit-synthetic-user-prompt` with `{:session-id … :user-msg …}`,
      returning `{:psi.extension/prompt-submitted? …}`); add to `all-mutations`.
      Wraps plain text in a canonical user message record; dispatches with
      `:origin :mutations`. Kept minimal — no new semantics (R4).
- [x] Core mutation test (agent-session): wrapper injects a mid-conversation
      **user** message into the target session and reports submitted? (drives the
      downstream turn deterministically via the `:execute-prepared-request-fn`
      ctx seam — no network).
- [x] `dev_http/choices.clj`: `:choices` renderer emits a token-gated form
      POSTing the selection back to `/s/:route-id` (method-dispatched handler).
- [x] Submit handler: write via `:mutate-session` calling
      `psi.extension/submit-synthetic-prompt`; origin session-id captured at
      registration time (from the `dev-present` tool `opts`/`register-content!`,
      invoking-session-only).
- [x] Single-shot guard: store `{:answered? true :answer …}` in the registry
      entry on first successful submit (atomic `claim-answer!`); second submit →
      "already answered" page, no second injection.
- [x] Tests: submitting a `:choices` form injects exactly one user message into
      the originating session (AC-6); second submission rejected, at most one
      injection (AC-7); handler writes only via `:mutate-session` (AC-9). Unit
      handler test + real-server integration loop (nullable api records the
      mutation).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: choice interaction loop (submit-synthetic mutation + single-shot)`.

  Note (R8 resolved): no `:allowed-events` declaration needed — the submit path
  is a Pathom mutation (`:mutate-session`), and its inner core dispatch uses
  `:origin :mutations`; the permission interceptor only gates `:origin :extension`
  dispatches.

## Slice 4 — SSE live-updates

- [x] `dev_http/sse.clj`: `text/event-stream` endpoint within the token-gated
      subtree using http-kit `as-channel`/`send!`/`close`. `make-handler`
      `(emit-fn send! close!)` opens the stream, sends an initial `open` event
      (sets headers), then hands `send!`/`close!` to the feed.
- [x] One demonstrated live feed: `/sse/registry` (token-gated) emits a snapshot
      of the current session-route count then closes; `register-sse-route!` REPL
      fn registers arbitrary live feeds as session routes.
- [x] Tests: SSE event formatting; `/sse/registry` token-gated (403 without
      token, unit); integration — connected client over the real server receives
      `data: open` + `data: routes N` reflecting registry state (AC-8 token
      required).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: SSE live-updates`.

## Close-out (cross-cutting)

- [x] Verify no HTTP handler reads/writes core state except via
      `:query`/`:mutate`; integrant + live handle stay inside the extension
      (AC-9). HTTP handlers touch state only via `(:mutate-session api)` (choices
      submit); renderers are pure; the integrant system + registry live in the
      extension atom only.
- [x] Docs: add `doc/dev-http.md` (lifecycle, renderers, choices, SSE, token,
      dev-only posture) and link from README extensions list.
- [x] Changelog: `[Unreleased]` Added entry for the dev-http extension.
- [x] Update `mementum/state.md` capabilities section to mention dev-http.
- [x] Full Scry suite green; clj-kondo clean across extension + touched core.
- [x] Commit: `⚒ 229: docs + changelog + coherence`.

## Implementation review follow-ups (round 1)

- [x] Remove the dead no-op subscription in `init`:
      `((:on api) "session_switch" (fn [_ev] nil))`
      (`extensions/dev-http/src/extensions/dev_http.clj`). It was added in the
      Slice 1 commit, is documented nowhere (design/plan/steps/implementation),
      and registers a real session-event subscription that does nothing —
      incidental complexity. Delete it, or, if a future hook is genuinely
      intended, replace the no-op with the actual behaviour and document it.
      Deleted (no future hook intended).
- [x] Make the not-running precondition consistent across the sibling
      registration fns in `dev_http.clj`. `register-route!` threw `ex-info`
      while `register-content-route!` / `register-sse-route!` returned `nil` for
      the same "server not running" condition. Unified on the nil idiom:
      `register-route!` now returns nil when the server is not running
      (`register-sse-route!` delegates to it, so it is nil too); this matches
      the `register-content!` seam contract the `dev-present` tool relies on.
- [x] De-duplicate shared helpers:
      `kget` is defined verbatim in both `renderers.clj` and `choices.clj`;
      the urlencoded key/value regex parse `([^&=]+)=([^&]*)` is duplicated
      between `middleware/query-token` and `choices/form-choice`. Extract each
      into one shared location rather than copies. `kget` → new
      `extensions.dev-http.util`; urlencoded parse → `mw/urlencoded-param`
      (HTTP concern, in middleware) used by both `query-token` and
      `form-choice`.
- [x] Resolve the dead `source` param in the core `submit-synthetic-prompt`
      mutation (`components/agent-session/.../mutations/prompts.clj`): `source`
      is destructured and `(or source :extension)` is used, but `source` is not
      in `::pco/params` and no caller supplies it, so the non-`:extension`
      branch is unreachable. Either drop the param (always `:extension`) or
      declare and wire it through a caller. Dropped the param; `:source` is
      always `:extension`.

## Implementation review follow-ups (round 2)

- [ ] `claim-answer!` (`extensions/dev-http/src/extensions/dev_http/choices.clj`)
      performs a side effect inside the `swap!` update fn: it `reset!`s an
      external `won` atom from within the function passed to `swap!`. This is the
      documented swap-side-effect anti-pattern (`swap!` may retry its fn). It
      happens to converge here because every invocation re-`reset!`s `won`, but
      it violates the "update fn is pure" idiom. Rewrite using `swap-vals!` and
      derive the win from the returned old/new state, e.g.
      `(let [[old _] (swap-vals! reg (fn [m] …))] (not (:answered? (get old route-id))))`,
      removing the inner `won` atom.
- [ ] Duplicate session-route URL path shape `"/s/" <route-id> "?token=" <token>`
      is hand-built in two places — `route-url` (`dev_http.clj`, with base-url)
      and `render-form`'s form `action` (`choices.clj`, relative). The reitit
      template `/s/:route-id` (`router.clj`) is the third encoding of the same
      dispatch path. A path change must be made in all three. Extract the
      relative session-route path (`"/s/" route-id`) into one shared helper used
      by both URL builders so the dispatch path has a single source of truth.
- [ ] `handle-command`'s `"start"` branch (`dev_http.clj`) hand-builds a
      `"dev-http started\n  url:   …\n  token: …"` string that duplicates the
      url/token formatting already produced by `status-text`. Reuse a single
      formatting helper (or have `start` log `status-text` after starting) so the
      running-status presentation has one source.
