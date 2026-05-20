# 160 — Steps

- [ ] 1. Rewrite `load-startup-resources-via-mutations-in!` → `load-startup-resources-in!` in `bootstrap.clj`
  - Replace template loop: `session/dispatch-in!` ctx `:session/register-prompt-template` per template, `{:origin :core}`
  - Replace skill loop: `session/dispatch-in!` ctx `:session/register-skill` per skill, `{:origin :core}`
  - Replace tool loop: `session/dispatch-in!` ctx `:session/add-tool` per tool, `{:origin :core}`
  - Replace extension-path loop: `ext-rt/add-extension-in!` ctx session-id path (direct, no dispatch); wrap result to namespaced keys — destructure `{:keys [loaded? error]}`, produce `{:psi.extension/loaded? loaded? :psi.extension/path p :psi.extension/error error}` (use input `p` for path, matching mutation behaviour)
  - Keep extension init-var path unchanged (already direct)
  - Discard dispatch return values via `doseq` (counts read from session-data after all loops)
  - Remove `run-mutation-in!` private helper
  - Remove requires: `psi.agent-session.mutations`, `psi.query.core`
  - Add require: `psi.agent-session.extension-runtime` (as `ext-rt`) — needed for `ext-rt/add-extension-in!`
  - No new dispatch require needed: `session/dispatch-in!` uses existing `psi.agent-session.core` (already required as `session`)
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

- [x] 6. Fix extension-path return key mismatch (from implementation review)
  - `ext-rt/add-extension-in!` returns unnamespaced `{:loaded? :path :error}` but `bootstrap-in!` reads namespaced `:psi.extension/loaded?`, `:psi.extension/path`, `:psi.extension/error`
  - Decision: wrap result in step 1's extension-path replacement — same pattern as init-var path (lines 68–70) and the mutation it replaces
  - Updated step 1 extension-path sub-item to include key translation: destructure `{:keys [loaded? error]}`, produce `{:psi.extension/loaded? loaded? :psi.extension/path p :psi.extension/error error}` (use input `p`, not result `:path` which is the extension object)
