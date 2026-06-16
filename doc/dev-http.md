# dev-http — local HTTP side channel

`dev-http` is a **dev-time** psi extension: a localhost HTTP server that acts as
a rich, bidirectional side channel between the agent and the developer,
addressed by URL. It lets the agent (and dev-tree code) present things the
terminal cannot — benchmark tables, alternative-design comparisons, choice
prompts, diagrams, arbitrary file artifacts — in a browser, and lets the
developer's choices flow back into the originating session as user input.

It is **not** shipped end-user behaviour; it exists to make developing psi (and
developing with psi) richer and more interactive. The server binds to
`127.0.0.1` only, uses an ephemeral OS-assigned port, and gates access behind a
per-launch token (dev-grade, not authentication).

## Lifecycle

Use the `/dev-http` command:

- `/dev-http start` — start the localhost server (ephemeral port, fresh token).
- `/dev-http status` — report running state, URL, and token.
- `/dev-http stop` — cleanly halt the server.

`start` is idempotent: it halts any prior server before starting, so a
restart/reload never leaves an orphaned server. The running system is an
[integrant](https://github.com/weavejester/integrant) system
(`config → registry → router → server`) held in the extension's own atom — not
in psi core state.

## Presenting content: the `dev-present` tool

The model can call the `dev-present` tool to register a session route from
declarative content data and receive back a URL:

```
dev-present {:renderer "markdown" :data "# Results\n\n- fast\n- correct"}
```

It returns a URL like `http://127.0.0.1:53124/s/r-1a2b3c4d?token=…`. Open it in
a browser. An optional `route-id` gives a stable URL; re-registering an existing
`route-id` replaces the prior entry (last-write-wins).

### Renderers

| `:renderer` | `:data` shape | Output |
|-------------|---------------|--------|
| `markdown`  | a markdown string | rendered HTML (commonmark) |
| `table`     | `{:headers [...] :rows [[...] ...]}` | an HTML table |
| `vega`      | a Vega-Lite spec map | a chart (vendored Vega-Lite JS) |
| `mermaid`   | a Mermaid source string | a diagram (vendored Mermaid JS) |
| `hiccup`    | a hiccup tree | raw HTML (escape hatch) |
| `file`      | `{:path "/abs/path"}` | the file served with a content-type by extension |
| `choices`   | `{:prompt "…" :options ["A" "B"]}` | a choice form (see below) |

Vega-Lite and Mermaid client JS are **vendored** and served locally from the
extension (offline-safe; no CDN/network dependency).

## Registering full-power routes (REPL/dev)

From the REPL you can register arbitrary ring handlers or SSE feeds (in-process,
throwaway, not persisted/replayed):

```clojure
(require '[extensions.dev-http :as dev-http])

;; arbitrary ring handler
(dev-http/register-route! "my-page"
  (fn [_req] {:status 200 :headers {"content-type" "text/html"} :body "<h1>hi</h1>"}))

;; declarative content
(dev-http/register-content-route! "chart"
  {:renderer :vega :data my-vega-lite-spec})

;; SSE live feed
(dev-http/register-sse-route! "ticks"
  (fn [send! close!] (send! "tick 1") (send! "tick 2") (close!)))
```

## Persisted routes

Committed, full-power routes live under the extension-local
`extensions/dev-http/dev/` source path (a scoped `:dev` extra-path on the
extension's own `deps.edn` — never a project-global `dev/` location). Each
route namespace under `extensions.dev-http.dev.*` defines a `routes` var holding
reitit route data. They are reloadable and dev-only — never in a published jar.
The shipped `extensions.dev-http.dev.demo` route serves `/demo`.

## The choice interaction loop

A `:choices` route is the interaction primitive. The agent registers a choice
prompt, tells the developer the URL, and the developer picks an option in the
browser. On submission:

1. The selection is POSTed back to the route (inside the token-gated subtree).
2. The handler injects the selection as a **mid-conversation user message** into
   the originating session — the same canonical path the scheduler uses for
   delayed prompts (`psi.extension/submit-synthetic-prompt` →
   `:session/submit-synthetic-user-prompt`) — which drives the agent's next turn
   immediately.

A `:choices` route is **single-shot**: once a decision is submitted, further
submissions are rejected ("already answered"), and exactly one user message is
injected per prompt. Choices target the **invoking session only**.

Presentation is out-of-band (like logging/UI projection) and excluded from the
event log; only the choice-submission mutation enters the log, preserving replay
fidelity.

## Live updates (SSE)

A route may expose a `text/event-stream` feed; browser pages subscribe via
`EventSource` to receive pushed updates without manual refresh. The built-in
`/sse/registry` feed (token-gated) emits a snapshot of the current session-route
count. Register custom feeds with `register-sse-route!`.

## Security posture

- Binds to `127.0.0.1` only; never remote/non-localhost.
- Every **dynamic** subtree (persisted routes, `/s/` session routes, `/sse/`
  feeds, choice submits) is gated by the per-launch token, supplied via the
  `token` query parameter or the `x-dev-http-token` header. A mismatch is `403`.
- Vendored static client JS under `/assets/` is served ungated (it is public
  third-party library content with no session data, so token-less browser
  `<script>` requests resolve).
- Token is dev-grade, not authentication. This is a single-developer localhost
  tool with no multi-writer concurrency hardening.

## Architecture notes

- HTTP handlers read/write psi state only through the extension API map
  (`:query`/`:query-session`, `:mutate`/`:mutate-session`) — no direct core
  state access.
- The live integrant system, server, and registry handle live in the
  extension's own atom (precedent: `work-on`, `mcp-tasks-run`), not in psi core
  state and not as a core managed-service type.
- The one sanctioned core touch is the `psi.extension/submit-synthetic-prompt`
  mutation that wraps the internal synthetic-user-prompt dispatch.
