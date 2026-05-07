2026-05-07

Implemented the provider-auth / OAuth component extraction as a structural move with no compatibility shim.

What changed
- Added new component `components/provider-auth/` with `deps.edn`.
- Moved authoritative namespaces to:
  - `components/provider-auth/src/psi/provider_auth/core.clj` -> `psi.provider-auth.core`
  - `components/provider-auth/src/psi/provider_auth/oauth/core.clj` -> `psi.provider-auth.oauth.core`
  - `components/provider-auth/src/psi/provider_auth/oauth/store.clj` -> `psi.provider-auth.oauth.store`
  - `components/provider-auth/src/psi/provider_auth/oauth/providers.clj` -> `psi.provider-auth.oauth.providers`
  - `components/provider-auth/src/psi/provider_auth/oauth/pkce.clj` -> `psi.provider-auth.oauth.pkce`
  - `components/provider-auth/src/psi/provider_auth/oauth/callback_server.clj` -> `psi.provider-auth.oauth.callback-server`
- Removed the old authoritative source files from `components/agent-session/src/psi/agent_session/`.
- Updated root config:
  - `deps.edn` local component deps plus source/test path aliases now include `components/provider-auth/src` and `components/provider-auth/test`
  - `tests.edn` unit/integration source and test paths now include the new component
- Updated consuming component deps:
  - `components/agent-session/deps.edn`
  - `components/app-runtime/deps.edn`
  - `components/rpc/deps.edn`
- Updated direct production consumers to require `psi.provider-auth.*` instead of `psi.agent-session.*` auth namespaces.
- Updated higher-level tests that intentionally remain in their owning components to require `psi.provider-auth.oauth.core`.

clj-surgeon usage
- Used `clj-surgeon :op :ls` on:
  - `components/agent-session/src/psi/agent_session/provider_auth.clj`
  - `components/agent-session/src/psi/agent_session/oauth/core.clj`
  - `components/agent-session/src/psi/agent_session/oauth/providers.clj`
- Used `clj-surgeon :op :deps` on:
  - `components/provider-auth/src/psi/provider_auth/core.clj`
  - `components/provider-auth/src/psi/provider_auth/oauth/core.clj`
  - `components/agent-session/src/psi/agent_session/oauth/providers.clj` at `register-default-providers!`
- Used `clj-surgeon :op :rename-ns` while migrating the copied files into their extracted namespace names.

Test ownership results
Moved into `components/provider-auth/test/psi/provider_auth/` because they are component-owned proofs of the extracted boundary:
- `components/provider-auth/test/psi/provider_auth/core_test.clj`
- `components/provider-auth/test/psi/provider_auth/oauth/core_test.clj`
- `components/provider-auth/test/psi/provider_auth/oauth/store_test.clj`
- `components/provider-auth/test/psi/provider_auth/oauth/store_lock_test.clj`
- `components/provider-auth/test/psi/provider_auth/oauth/providers_test.clj`
- `components/provider-auth/test/psi/provider_auth/oauth/pkce_test.clj`
- `components/provider-auth/test/psi/provider_auth/oauth/callback_server_test.clj`

Remained in higher-level components because they prove consuming behavior rather than provider-auth component internals:
- `components/agent-session/test/psi/agent_session/runtime_test.clj`
- `components/agent-session/test/psi/agent_session/resolvers_test.clj`
- `components/app-runtime/test/psi/app_runtime_test.clj`
- `components/app-runtime/test/psi/extension_install_startup_test.clj`
- `components/rpc/test/psi/rpc_prompt_command_test.clj`

Reason for leaving those higher-level tests in place
- they verify app-runtime / rpc / agent-session behavior through the extracted auth boundary
- moving them would blur ownership by making component tests responsible for orchestration behavior owned elsewhere

Boundary verification
- No compatibility shim was introduced.
- Old `psi.agent-session.provider-auth` and `psi.agent-session.oauth.*` authoritative source files were removed in the same slice.
- Repo search after migration found no remaining authoritative old provider-auth/oauth requires/usages in active component source/test/config paths.
- Extracted authoritative `psi.provider-auth.*` namespaces do not require `psi.agent-session.*` implementation namespaces.
- Provider registration/bootstrap behavior remained in `psi.provider-auth.oauth.providers` unchanged in semantics; only ownership moved.

Verification run
- Focused extracted-component verification:
  - `clojure -M:test --focus psi.provider-auth.core-test --focus psi.provider-auth.oauth.core-test --focus psi.provider-auth.oauth.providers-test --focus psi.provider-auth.oauth.store-test --focus psi.provider-auth.oauth.store-lock-test --focus psi.provider-auth.oauth.pkce-test --focus psi.provider-auth.oauth.callback-server-test`
  - result: `31 tests, 129 assertions, 0 failures`
- Focused higher-level consuming-path verification:
  - `clojure -M:test --focus psi.rpc-prompt-command-test --focus psi.app-runtime-test --focus psi.agent-session.runtime-test --focus psi.agent-session.resolvers-test --focus psi.extension-install-startup-test`
  - result: `65 tests, 354 assertions, 0 failures`

Outcome
- Provider-auth is now an explicit lower-level component.
- Higher-level app/runtime/rpc/agent-session code now depends on `psi.provider-auth.*`.
- The extracted auth component is authoritative and no longer lives under `agent-session` ownership.
