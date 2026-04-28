Created 2026-04-28.

Initial task design drafted from the request to remove the LSP client.
Pending user clarification on scope and relationship to existing task 004 before planning.

2026-04-28 — inventory + supersession kickoff
- inventoried the active LSP surface across runtime, extension catalog/install, tests, build/test aliases, docs, and munera task references
- confirmed the reusable infrastructure worth preserving is the generic managed-service registry/request/notification surface in:
  - `components/agent-session/src/psi/agent_session/services.clj`
  - `components/agent-session/src/psi/agent_session/service_protocol.clj`
  - `components/agent-session/src/psi/agent_session/mutations/services.clj`
  - `components/agent-session/src/psi/agent_session/resolvers/services.clj`
  - `components/agent-session/src/psi/agent_session/extensions/api.clj`
- confirmed the stdio JSON-RPC runtime adapter is currently only exercised by the LSP client path and is therefore a likely removal target rather than preserved generic infrastructure:
  - `components/agent-session/src/psi/agent_session/service_protocol_stdio_jsonrpc.clj`
  - related `service_protocol_stdio_jsonrpc*` tests/fixtures
- confirmed the built-in LSP capability still appears in multiple active surfaces and must be removed coherently:
  - extension source and tests under `extensions/lsp/`
  - launcher/runtime extension catalogs in `bases/main/src/psi/launcher/extensions.clj` and `components/agent-session/src/psi/agent_session/extension_installs.clj`
  - source/test path wiring in `deps.edn`, `tests.edn`, `tests-workflow-isolated.edn`, and `build.clj`
  - docs in `doc/extensions.md`
  - active task `004-lsp-integration-managed-services-post-tool-processing`
- marked task 004 as superseded by 065 in task-local files so removal is now the active direction

2026-04-28 — removal pass
- removed the built-in LSP extension library and its tests under `extensions/lsp/`
- removed the stdio JSON-RPC runtime adapter and its dedicated tests/fixtures because it no longer has a non-LSP consumer:
  - `components/agent-session/src/psi/agent_session/service_protocol_stdio_jsonrpc.clj`
  - `components/agent-session/test/psi/agent_session/service_protocol_stdio_jsonrpc_*`
  - `components/agent-session/test/psi/agent_session/jsonrpc_echo_bb.clj`
- preserved the generic managed-service surfaces and simplified them back to protocol-agnostic semantics:
  - `services.clj`
  - `service_protocol.clj`
  - `mutations/services.clj`
  - `resolvers/services.clj`
  - `extensions/api.clj`
- removed LSP-specific install/catalog/build wiring from:
  - `deps.edn`
  - `tests.edn`
  - `tests-workflow-isolated.edn`
  - `build.clj`
  - `extensions/deps.edn`
  - `bases/main/src/psi/launcher/extensions.clj`
  - `components/agent-session/src/psi/agent_session/extension_installs.clj`
- recast retained tests away from LSP names and JSON-RPC helpers toward generic managed-service semantics
- updated footer/status fixture text in shared UI tests so they no longer imply built-in LSP support
- removed the built-in LSP section from `doc/extensions.md`
- focused verification green:
  - `clojure -M:test --focus psi.agent-session.service-protocol-test --focus psi.agent-session.extensions-service-protocol-api-test --focus psi.agent-session.mutations-service-protocol-test --focus psi.agent-session.services-eql-test --focus psi.agent-session.extensions-post-tool-api-test --focus psi.agent-session.mutations-post-tool-test --focus psi.agent-session.tool-execution-test --focus psi.app-runtime.footer-test --focus psi.rpc-test --focus psi.rpc-events-test --focus psi.tui.app-view-runtime-test`
  - result: `80 tests, 305 assertions, 0 failures`

2026-04-28 — post-close review note
- reviewed against design/plan with the munera-task-review skill
- outcome: approved; implementation matches the extract-and-remove design, preserves the intended generic managed-service architecture, and does not introduce concerning new patterns
- strengths:
  - removal is vertically coherent across code, tests, docs, build wiring, and task surfaces
  - retained managed-service infrastructure is simpler and more protocol-agnostic after the LSP-specific layer deletion
  - verification evidence is strong: focused green proof plus full unit suite green
- optional non-blocking follow-up only:
  - remaining `clojure-lsp` references are tooling/editor metadata rather than product LSP support
  - if a future protocol-specific integration appears, it should be added as an integration-local adapter rather than by re-expanding the generic service core

2026-04-28 — optional follow-up executed
- decided to leave `:clojure-lsp/ignore` metadata in place as editor/tooling implementation detail, not product LSP support
- renamed one lingering explanatory comment in `components/memory/src/psi/memory/store.clj` so it now refers to editor/tooling unused-var analysis generically rather than to `clojure-lsp` specifically
- codified the future adapter guidance in `doc/extensions.md` under managed services:
  - generic managed-service core owns lifecycle/transport
  - protocol semantics should live in integration-local adapters
  - future JSON-RPC-like integrations should prove their behavior with integration-local tests rather than by expanding the shared core

2026-04-28 — code-shaper review note
- reviewed with the code-shaper skill for simplicity, consistency, and robustness
- outcome: approved; the removal improved shape rather than merely deleting code
- simplicity:
  - `service_protocol.clj` is now narrowly generic
  - `mutations/services.clj` no longer hides protocol-specific runtime attachment in service creation
  - the managed-service core now has one job instead of generic lifecycle plus one integration-specific protocol layer
- consistency:
  - retained naming and projections now match actual generic ownership
  - tests and docs were reshaped away from LSP-specific semantics toward true managed-service semantics
- robustness:
  - fewer special-case branches and fewer misleading public-ish seams remain
  - the added adapter guidance reduces the risk of future protocol-specific logic re-expanding the shared core accidentally
- non-blocking suggestion only:
  - if helpful later, sharpen the `service_protocol.clj` namespace docstring to state explicitly that protocol framing is out of scope for the generic service layer
