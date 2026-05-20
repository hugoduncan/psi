# 160 — Steps

- [ ] 1. Rewrite `load-startup-resources-via-mutations-in!` → `load-startup-resources-in!` in `bootstrap.clj`
  - Replace template loop: `dispatch/dispatch!` ctx `:session/register-prompt-template` per template
  - Replace skill loop: `dispatch/dispatch!` ctx `:session/register-skill` per skill
  - Replace tool loop: `dispatch/dispatch!` ctx `:session/add-tool` per tool
  - Keep extension init-var path unchanged (already direct)
  - Remove `run-mutation-in!` private helper
  - Remove requires: `psi.agent-session.mutations`, `psi.query.core`
  - Add require: `psi.agent-session.dispatch`
  - Rename call site in `bootstrap-in!`

- [ ] 2. Remove `:mutations` from bootstrap summary
  - `bootstrap-in!`: drop `:mutations` key from the summary map
  - `session_state/model.clj`: remove `:mutations` from `:startup-bootstrap` schema
  - `resolvers/session.clj` (`startup-bootstrap-resolver`): remove `:psi.startup/mutations` from output + body
  - `introspection/resolvers.clj` (`startup-bootstrap-summary`): remove `:psi.startup/mutations` from output + body

- [ ] 3. Update tests
  - `introspection/agent_session_test.clj`: remove `:psi.startup/mutations` query + assertion
  - Scan for any other test asserting `:mutations` in startup summary

- [ ] 4. Verify
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/bootstrap.clj`
  - Focused test: `clojure -M:test --focus psi.agent-session.model-dispatch-test`
  - Focused test: `clojure -M:test --focus psi.introspection.agent-session-test`
  - Focused test: `clojure -M:test --focus psi.agent-session.dispatch-test`
  - Broader: `clojure -M:test --focus psi.app-runtime-bootstrap-test`
  - Lint full: `clj-kondo --lint src components/*/src components/*/test`

- [ ] 5. Commit: `⚒ 160: replace mutation-mediated bootstrap with direct dispatch`
