# 160 — Steps

- [x] 1. Rewrite `load-startup-resources-via-mutations-in!` → `load-startup-resources-in!` in `bootstrap.clj`
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

- [x] 2. Remove `:mutations` from bootstrap summary
  - `bootstrap-in!`: drop `:mutations` key from the summary map
  - `session_state/model.clj`: remove `:mutations` from `:startup-bootstrap` schema
  - `resolvers/session.clj` (`startup-bootstrap-resolver`): remove `:psi.startup/mutations` from output + body
  - `introspection/resolvers.clj` (`startup-bootstrap-summary`): remove `:psi.startup/mutations` from output + body

- [x] 3. Update tests
  - `introspection/agent_session_test.clj`: remove `:psi.startup/mutations` query + assertion
  - Scan for any other test asserting `:mutations` in startup summary

- [x] 4. Verify
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/bootstrap.clj`
  - Focused test: `clojure -M:test --focus psi.agent-session.model-dispatch-test`
  - Focused test: `clojure -M:test --focus psi.introspection.agent-session-test`
  - Focused test: `clojure -M:test --focus psi.agent-session.dispatch-test`
  - Broader: `clojure -M:test --focus psi.app-runtime-bootstrap-test`
  - Lint full: `clj-kondo --lint src components/*/src components/*/test`

- [x] 5. Commit: `⚒ 160: replace mutation-mediated bootstrap with direct dispatch`

- [x] 6. Fix extension-path return key mismatch (from implementation review)
  - `ext-rt/add-extension-in!` returns unnamespaced `{:loaded? :path :error}` but `bootstrap-in!` reads namespaced `:psi.extension/loaded?`, `:psi.extension/path`, `:psi.extension/error`
  - Decision: wrap result in step 1's extension-path replacement — same pattern as init-var path (lines 68–70) and the mutation it replaces
  - Updated step 1 extension-path sub-item to include key translation: destructure `{:keys [loaded? error]}`, produce `{:psi.extension/loaded? loaded? :psi.extension/path p :psi.extension/error error}` (use input `p`, not result `:path` which is the extension object)

- [x] 7. Add test: `bootstrap-in!` with non-empty skills and tools (from test review)
  - Call `bootstrap-in!` with ≥1 skill and ≥1 tool (plus ≥1 template for existing coverage)
  - Assert `skill-count`, `tool-count`, `prompt-count` all > 0 in session-data after bootstrap
  - Assert the resources appear in session-data (`:skills`, `:tools`, `:prompt-templates`)
  - This covers AC2: skills and tools registered via direct dispatch produce the same session-data outcome as mutation-mediated path
  - Added as `bootstrap-resource-registration-test` in `model_dispatch_test.clj`

- [x] 8. Add test: dispatch event log contains resource registration events during bootstrap (from test review)
  - Call `bootstrap-in!` with ≥1 template, ≥1 skill, ≥1 tool after `kernel/clear-event-log!`
  - Assert event log contains `:session/register-prompt-template`, `:session/register-skill`, `:session/add-tool` events
  - Currently asserts `:origin :mutations` (via EQL mutation wrappers); update to `:origin :core` after step 1 converts to direct dispatch
  - This verifies the mechanism change: direct dispatch events appear in the log with correct origin
  - Added as `bootstrap-dispatch-event-log-test` in `model_dispatch_test.clj`

- [x] 9. Remove redundant weak assertion in `bootstrap-resource-registration-test` (from test-shaper review)
  - Removed `(is (pos? (count (:tools agent-data))) ...)` — subsumed by the stronger `(some #(= "test-tool" ...) ...)` assertion
  - Also flattened nested `let` into single binding block (eliminated kondo warning)

- [x] 10. Assert `bootstrap-in!` return summary counts in `bootstrap-resource-registration-test` (from test-shaper review)
  - Captured `bootstrap-in!` return value in `summary`; asserts `(:prompt-count summary)` = 1, `(:skill-count summary)` = 1, `(:tool-count summary)` ≥ 1
  - Closes return-shape coverage gap at the bootstrap level (AC3 partial; complements introspection test)

- [x] 11. Add explicit step: update `bootstrap-dispatch-event-log-test` origin assertion from `:mutations` to `:core` (from test-shaper review)
  - Added as step 12 below

- [x] 12. Update `bootstrap-dispatch-event-log-test` origin assertion from `:mutations` to `:core` (after step 1)
  - In `model_dispatch_test.clj`, change `:origin :mutations` assertions to `:origin :core` and update test message strings
  - Must be done as part of or immediately after step 1 (direct dispatch conversion)
  - Step 3's `:mutations` scan targets the summary key, not dispatch event origins — this is a separate concern

- [x] 13. Fix typo in steps.md step 6 done-note: `:psi.parameter/path` → `:psi.extension/path` (from code-shaper review)
  - The step 6 done-note records the wrong namespace for the path key; verify step 1 sub-item text is correct (it is) and fix the step 6 note to prevent copy-paste propagation

- [x] 14. Update `bootstrap-in!` docstring after rewrite (from code-shaper review)
  - Step 2 in the docstring says "load prompts/skills/tools/extensions via EQL mutations" — change to reflect direct dispatch/runtime calls
  - Do as part of or immediately after step 1

- [x] 15. Add docstring to `refresh-active-tools-in!` (from code-shaper review)
  - Every other public fn in `bootstrap.clj` has a docstring; add one for consistency
