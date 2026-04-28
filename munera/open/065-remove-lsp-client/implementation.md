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
