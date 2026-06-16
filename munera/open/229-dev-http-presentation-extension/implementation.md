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
