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
