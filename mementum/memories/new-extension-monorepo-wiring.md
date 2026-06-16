💡 Wiring a NEW psi extension into the monorepo has three last-mile steps the plan/design routinely omit (each found by failure during 229 dev-http):

1. **Launcher catalog**: register `name → {:psi/init …, :development/:installed/:mvn coords}` in `psi-owned-extension-catalog` (`bases/main/src/psi/launcher/extensions.clj`). Without it the extension is never discovered.
2. **mvn deps go in ROOT `deps.edn` `:deps`** — the monorepo classpath does NOT merge the extension-local `deps.edn`. Keep the same deps in the extension-local `deps.edn` for standalone buildability, but the root file governs runtime.
3. **Paths**: add the extension's `src` (and any `dev`/`resources`) to root `deps.edn` extra-path blocks AND to the kaocha suite `:source-paths` in `tests.edn` (`:unit`/`:extensions`/`:integration`).

Extension tests run ONLY through the kaocha `:extensions` suite (`clojure -M:test extensions --focus …`); the scry `:test-paths` runner does not carry extension src on its classpath. Distinguish unit vs `^:integration` (real ephemeral-port boot) tests.
