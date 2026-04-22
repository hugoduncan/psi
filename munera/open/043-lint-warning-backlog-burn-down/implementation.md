2026-04-22
- Task created after `042` cleared hard lint errors but exposed a large warning backlog.
- Starting point:
  - `bb fmt:check` passes
  - `bb lint` reports `errors: 0`
  - `bb lint` still exits non-zero because warnings remain and the repo treats warnings as failing
- Initial dominant categories:
  - unused requires
  - unused bindings
  - unused private vars
  - unresolved namespace warnings in split TUI files and some tests
- Execute as an area-based warning burn-down rather than a single noisy sweep.

2026-04-22
- TUI slice:
  - removed dead top-level `psi.tui.app` aliases left behind by the split-file extraction (`ui-actions`, duplicate `input-pos`, unused tool style defs)
  - trimmed unused helpers/requires from `app_input_selector_test.clj`, `app_test.clj`, and `app_view_runtime_test.clj`
  - this did not fix the remaining split-file `in-ns` unresolved-namespace warnings; those are still present and appear to need either a lint config approach or a different extraction shape
  - `bb lint` warning count moved from `235` to `213`

2026-04-22
- RPC slice:
  - removed unused requires from `rpc.events`, `rpc.session.prompt`, and the RPC test support/tests
  - replaced a few fully-qualified references in tests with aliased requires so `clj-kondo` resolves vars cleanly (`rpc.events`, `session/query-in`)
  - cleaned unused destructured `state`/`session-id` bindings in RPC tests
  - targeted `clj-kondo` on the full RPC warning set now reports `errors: 0, warnings: 0`

2026-04-22
- Agent-session source slice:
  - removed dead helpers and unused requires/bindings in dispatch/effect/scheduler/session-state/runtime-fns/canonical-workflows/project-nrepl/resolvers/service/workflow progression namespaces
  - fixed a couple of redundant-let sites while touching nearby code
  - verified the touched source slice with targeted `clj-kondo` (`errors: 0, warnings: 0`)
  - broader agent-session test warning backlog remains and is still the largest remaining category
  - current repo-wide `bb lint` is now down to `136` warnings remaining

2026-04-22
- Scattered source cleanup slice:
  - fixed missing `clojure.string` aliases in model registry and agent-session JSON-RPC/workflow attempt source files
  - removed unused helpers/bindings in app-runtime navigation/context surfaces, workflow psi-tool handling, and LSP document close handling
  - simplified OpenAI chat completion header construction to remove a redundant-let warning without changing request semantics
  - verified the touched cross-component source slice with targeted `clj-kondo` (`errors: 0, warnings: 0`)

2026-04-22
- App-runtime test cleanup slice:
  - removed unused `testing` refers from the small projection/selector tests
  - removed an unused `event-queue` binding from `app_runtime_test.clj`

2026-04-22
- Extension cleanup slice:
  - removed unused test requires/helpers in auto-session-name, commit-checks, and LSP extension tests
  - removed dead workflow-step locals in `mcp_tasks_run.clj`
  - added an explicit `workflow-file-loader` alias in the workflow-loader delegate tests so `with-redefs` targets resolve cleanly under lint

2026-04-22
- Agent-session test slice 1:
  - removed unused `testing` refers and dead requires from extension/scheduler/post-tool tests
  - simplified one scheduler test local binding to clear a redundant-let/unused-result warning

2026-04-22
- Agent-session/introspection/recursion/ai warning slice:
  - removed duplicate `psi.agent-session.test-support` require in `eql_introspection_api_error_test.clj`
  - added explicit `clojure.string` aliases in child-session/graph/jsonrpc/introspection tests and switched call sites to aliases so `clj-kondo` resolves them cleanly
  - added explicit `clojure.edn` alias in `model_dispatch_test.clj` and converted local EDN reads to the alias
  - removed unused `clojure.java.io` / recursion policy test requires and promoted one test helper from private to public to match cross-namespace usage
  - targeted `clj-kondo` on the touched files now reports `errors: 0, warnings: 0`
