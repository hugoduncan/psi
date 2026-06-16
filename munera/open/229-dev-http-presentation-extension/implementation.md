# 229 — Implementation notes

Append-only local memory of in-flight decisions and discoveries.

## Slice 0 — Scaffold + wiring (2026-06-15)

- Extension entry point: ns `extensions.dev-http`, `defonce ^:private state`
  atom `{:api nil :system nil}`, `init` captures the api map and returns `nil`
  (matches work-on/mcp-tasks-run convention).
- **Discovery (beyond plan): launcher catalog registration is required.**
  Extensions are mapped name→init via `psi-owned-extension-catalog` in
  `bases/main/src/psi/launcher/extensions.clj`. Added a `psi/dev-http` entry
  (`:psi/init 'extensions.dev-http/init`, dev/installed `:local/root
  "extensions/dev-http"`, jar `:mvn/version :psi/release-version`). The plan's
  wiring list omitted this; without it the extension would never be discovered.
- **Discovery: new mvn deps must live in root `deps.edn` `:deps`, not only the
  extension-local `deps.edn`.** The monorepo classpath is governed by root
  `deps.edn` (extension src dirs are aggregated as extra-paths; their
  extension-local deps.edn is not merged). Added `metosin/reitit-ring 0.7.2`,
  `http-kit/http-kit 2.8.0`, `hiccup/hiccup 2.0.0-RC3`, `integrant/integrant
  0.13.1` to root `:deps`. The extension-local `deps.edn` keeps the same deps so
  the extension is buildable standalone.
- **Discovery: extension tests run only through the kaocha `:extensions` suite**
  (`tests.edn`), which supplies extension `:source-paths`. The scry core runner
  via the `:test-paths` alias does NOT carry extension src on its classpath, so
  `clojure -M:test-paths -m scry.cli --namespace …` cannot load extension
  namespaces. Focused run that works:
  `clojure -M:test extensions --focus extensions.dev-http-test`.
  Added dev-http `:source-paths`/`:test-paths` to the `:unit`/`:extensions`/
  `:integration` suites in `tests.edn`, and `extensions/dev-http/src` to the
  root `deps.edn` runtime/test extra-path blocks.
- Dep versions pinned: reitit-ring 0.7.2, http-kit 2.8.0, hiccup 2.0.0-RC3,
  integrant 0.13.1. (`malli`, `commonmark` already on classpath per design.)
- Slice 0 verification: deps resolve (`clj -P`), entry-point ns loads, focused
  test green (1 test / 2 assertions), clj-kondo clean, `:psi` and `:test`
  aliases resolve.

## Slice 1 — Platform end-to-end (2026-06-16)

- Namespaces added: `config`, `registry`, `middleware`, `routes` (persisted
  loader), `router`, `system` (integrant), plus the `dev/.../demo.clj` route.
  Entry-point `dev_http.clj` now holds `start!`/`stop!`/`status-text`/
  `route-url`/`register-route!` + the `/dev-http start|status|stop` command.
- **Deviation: `route-url` lives in the entry-point ns, not `config`/`registry`.**
  Plan listed `route-url` under registry. A URL needs the *live server's*
  resolved ephemeral port + token (only known after integrant init), which live
  in the entry-point's `@state` system map — so `route-url` belongs there.
- **Token gating: parse from `:query-string` manually** (no ring params
  middleware dep). `request-token` reads `token=` from the query string or the
  `x-dev-http-token` header. Every dynamic subtree (persisted + `/s/`) sits under
  one reitit `["" {:middleware [[wrap-token token]]} …]` node.
- **http-kit 2.8 API:** `(run-server handler {:ip … :port 0
  :legacy-return-value? false})` returns a server object; `server-port` gives the
  resolved ephemeral port, `server-stop!` halts it (used in `ig/halt-key!`).
- **Persisted-route loader needs the `dev/` path on the test classpath.** The
  kaocha `:extensions`/`:integration` suites and root `:test` alias previously
  carried only `extensions/dev-http/src`. Added `extensions/dev-http/dev` to
  those (tests.edn `:extensions`+`:integration` source-paths; root deps.edn
  `:test` extra-paths) so `load-persisted-routes` can scan + serve the demo route
  in tests (AC-2). The scoped `:dev` extra-path in the extension-local deps.edn
  still governs runtime/standalone; the project-global root `:dev` is untouched.
- **`:allowed-events`: none this slice.** Slice 1 dispatches no core events
  (only api-level `:register-command`/`:log`). R8 (allowed-events for the
  synthetic-prompt path) is deferred to Slice 3 where the core mutation lands.
- **Loader robustness:** `load-persisted-routes` returns `[]` when the `dev/`
  resource is absent or non-file (e.g. jar) — never reaches a published jar.
- Verification: `clojure -M:test extensions --focus extensions.dev-http-test`
  (6 tests / 19 assertions, non-integration) and
  `clojure -M:test integration --focus extensions.dev-http-test`
  (1 test / 16 assertions, real ephemeral-port boot) both green; clj-kondo clean
  across src/dev/test.

## Slice 2 — dev-present tool + renderer set (2026-06-16)

- Namespaces added: `renderers` (pure content-map → ring response) and `tool`
  (`dev-present` factory). Entry-point gained `register-content-route!` and
  registers the tool in `init` via `(:register-tool api)`.
- **Renderer dispatch is a plain `:renderer → fn` map** (`renderers/render`),
  not a multimethod (coding standard: avoid multimethods / global mutable
  dispatch). `renderer-keys` is the supported set; unknown ⇒ 400.
- **`data` accessors read keyword *or* string keys** via a small `kget`. The
  same renderer serves idiomatic REPL data (keyword keys) and JSON-tool data
  (string keys). `:hiccup` additionally coerces JSON string tags → keywords so
  a decoded `["div" …]` renders as elements.
- **Markdown via `org.commonmark.renderer.html.HtmlRenderer`** (already on the
  classpath through the root `commonmark` dep) — distinct from the TUI's AST
  walker; HTML output, not ANSI.
- **Vega/Mermaid client JS vendored** under `resources/dev_http/vendor/`
  (vega 5.30.0, vega-lite 5.21.0, vega-embed 6.26.0, mermaid 10.9.1; ~4 MB
  total). Pages embed `<script src="/assets/…">` + the spec/source. JSON spec
  serialized with cheshire (transitively present).
- **Deviation (beyond plan): assets are served from an UNGATED `/assets/:asset`
  subtree.** A browser `<script src>` request carries no token, so token-gating
  the vendored public JS would break every Vega/Mermaid page. The assets are
  third-party library bytes with no session data, so serving them ungated is
  safe; **every *dynamic* subtree stays token-gated** (AC-8 unaffected). Path
  traversal guarded (reject `..`). `asset-handler` lives in `renderers` and is
  wired as a second top-level route alongside the gated root in `router`.
- **Deviation (beyond plan): `extensions/dev-http/resources` added to the
  classpath** in every block that already carried `…/src` (root `deps.edn`,
  `tests.edn` `:unit`/`:extensions`/`:integration`, extension-local `deps.edn`
  `:paths`) so `io/resource "dev_http/vendor/…"` resolves at runtime and in
  tests.
- **`:format-request`** is required by the tool registry; used
  `call-summary/text-key-format-request "dev-present" "renderer"` (call-summary
  is transitive via `psi/agent-session` → `psi/tool-runtime`).
- Verification: `clojure -M:test extensions --focus extensions.dev-http-test`
  (11 tests / 60 assertions) and `… integration …` (1 test / 20 assertions,
  real boot incl. AC-3 content route + AC-5 ungated wire fetch) both green;
  clj-kondo clean across src/test.

## Slice 3 — Choice interaction loop (2026-06-16)

- **Core mutation (the one sanctioned core touch):**
  `psi.extension/submit-synthetic-prompt` added to
  `components/agent-session/.../mutations/prompts.clj` + `all-mutations`. It
  wraps plain `user-msg` text in a canonical user message record
  (`{:role "user" :content [{:type :text :text …}] :timestamp (Instant/now)
  :source :extension}`) and dispatches `:session/submit-synthetic-user-prompt`
  with `{:origin :mutations}`, returning `{:psi.extension/prompt-submitted? …}`.
  Distinct from `send-prompt` (which uses the `deliver-extension-prompt!` path).
- **R8 resolved — no `:allowed-events` needed.** The permission interceptor
  (`dispatch.clj`) gates only `:origin :extension` dispatches. The extension
  reaches the mutation through the api `:mutate-session` (a Pathom mutation, not
  an extension-origin event), and the mutation's inner dispatch carries
  `:origin :mutations`. So the synthetic-prompt path is not permission-gated and
  the extension needs no event-permission declaration.
- **Core mutation test** drives the *downstream* AI turn through the
  `:execute-prepared-request-fn` ctx seam (testing-without-mocks: inject a
  nullable executor on ctx, not a `with-redefs` of the boundary var) returning a
  canonical stub assistant message — deterministic, no network. Asserts exactly
  one injected `user`/`:extension` message with the submitted text + canonical
  lifecycle events in the event log.
- **`dev_http/choices.clj`:** one method-dispatched ring handler per choices
  route. GET renders a token-gated `<form method=post action="/s/<id>?token=…">`
  (token read per-request via `mw/request-token`); POST parses `choice=` from the
  urlencoded body, runs the single-shot `claim-answer!`, and on first win calls
  `((:mutate-session api) session-id 'psi.extension/submit-synthetic-prompt
  {:user-msg answer})`.
- **Single-shot guard (LOCKED design choice):** the answered flag lives in the
  session-route **registry entry** (`{:answered? true :answer …}`), set
  atomically inside one `swap!` on the registry atom. Second submit → "already
  answered" page, no second injection. (Localhost single-user; R6 accepted.)
- **Origin session-id capture:** the `dev-present` tool reads `(:session-id opts)`
  from its `:execute` opts (confirmed `execute-tool-runtime-in!` threads
  `:session-id` into tool opts) and threads it into the content map; the
  entry-point `content-handler` builds the choices handler closing over it
  (invoking-session-only).
- **`body-string` accepts String *or* InputStream** — `slurp` on a raw String
  treats it as a filename, which broke the unit handler test; the real http-kit
  body is an InputStream. Guarded both.
- **`:choices` is in the tool's supported set but NOT in `renderers/render`** —
  it is interactive and needs per-request context (token/route-id/session-id), so
  it is handled at the route layer (`content-handler` branch), not the pure
  renderer map. `tool/supported-renderers` = `renderers/renderer-keys ∪ {:choices}`.
- Verification: `clojure -M:test extensions --focus extensions.dev-http-test`
  (12 tests / 75 assertions) + `… integration …` (2 tests / 29 assertions, real
  GET-form → POST-choice → captured mutation → single-shot loop) + agent-session
  `submit-synthetic-prompt-mutation-test` (1 test / 6 assertions) all green;
  clj-kondo clean.

## Slice 4 — SSE live-updates (2026-06-16)

- **`dev_http/sse.clj`** uses http-kit `as-channel` (the 2.8 async-channel API;
  `with-channel` is legacy). `make-handler` opens the stream in `:on-open`,
  sends an initial ring-response map (`content-type: text/event-stream`) whose
  body is a `data: open` event — http-kit treats the first `send!` of a response
  map as the header-setting send — then invokes `(emit-fn send! close!)`.
- **Demonstrated feed `/sse/registry`** lives in the token-gated subtree
  (router `gated-root`), emits one `data: routes N` snapshot of the current
  registry count, then closes (so a non-streaming client request completes —
  keeps the integration test deterministic with no sleeps/hangs).
  `register-sse-route!` (entry-point) registers arbitrary live feeds as throwaway
  session routes via the existing `register-route!`.
- **Testing note:** `as-channel` needs the real http-kit server connection, so
  the SSE handler is exercised only via the integration boot (token gating is
  unit-tested because the 403 short-circuits in middleware before `as-channel`).
- Verification: extensions suite 14 tests / 77 assertions; integration suite
  3 tests / 34 assertions (incl. real SSE connect → snapshot read); clj-kondo
  clean.
