🔁 Adding a new psi-owned extension requires two registrations, not one.

1. `.psi/extensions.edn` — declares the extension for the project
2. `psi-owned-extension-catalog` in `components/agent-session/src/psi/agent_session/extension_installs.clj` — maps the lib symbol to `{:psi/init 'ns/init ...}`

Missing (2) causes the runtime to **silently skip** the extension at load time. The extension is not counted as an error, startup shows 0 errors, but the extension simply isn't activated. Discovered when `gh-bug-triage-modular` failed with "Deterministic operation not found" because `psi/github` was in extensions.edn but not in the catalog → `github/find-issue` was never registered.

Fix: add entry to `psi-owned-extension-catalog`:
```clojure
'psi/github {:psi/init 'psi.github.extension/init
             :source-policies {:installed {:local/root "extensions/github"}}}
```
