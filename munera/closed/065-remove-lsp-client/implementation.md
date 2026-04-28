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
