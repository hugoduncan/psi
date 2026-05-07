Approach:
- treat this as a structural extraction, not a semantic redesign
- create `components/provider-auth/` with authoritative namespaces under `psi.provider-auth.*`
- move the OAuth namespace family and the provider-auth helper namespace in one slice so the new component becomes authoritative immediately
- prefer no compatibility shim; introduce a temporary shim only if the edit sequence concretely requires it to keep the tree compiling during migration, and remove it before completion

Authoritative target namespaces:
- `components/provider-auth/src/psi/provider_auth/core.clj` -> `psi.provider-auth.core`
- `components/provider-auth/src/psi/provider_auth/oauth/core.clj` -> `psi.provider-auth.oauth.core`
- `components/provider-auth/src/psi/provider_auth/oauth/store.clj` -> `psi.provider-auth.oauth.store`
- `components/provider-auth/src/psi/provider_auth/oauth/providers.clj` -> `psi.provider-auth.oauth.providers`
- `components/provider-auth/src/psi/provider_auth/oauth/pkce.clj` -> `psi.provider-auth.oauth.pkce`
- `components/provider-auth/src/psi/provider_auth/oauth/callback_server.clj` -> `psi.provider-auth.oauth.callback-server`

Implementation sequence:
1. create `components/provider-auth/` directories and destination namespaces/files
2. update project configuration so the new component participates in source and test paths
   - root `deps.edn` / `tests.edn`
   - consuming component deps for at least `components/agent-session`, `components/app-runtime`, and `components/rpc`
   - any explicit test alias path lists only where needed
3. move provider-auth and oauth namespaces into the new component namespace family
4. update direct production consumers across app-runtime, agent-session, and rpc
5. update direct test consumers and move clearly component-owned tests
6. remove any temporary compatibility shims before verification
7. run focused verification for the new component plus at least one higher-level consuming path
8. record final ownership and migration notes in `implementation.md`

Consumer migration expectations:
- app/runtime/rpc/agent-session callers should depend directly on `psi.provider-auth.*` namespaces that match the API they actually use
- extracted authoritative namespaces must not depend on `psi.agent-session.*` implementation namespaces directly at completion
- completion requires a final repo search confirming no remaining authoritative uses of:
  - `psi.agent-session.provider-auth`
  - `psi.agent-session.oauth.core`
  - `psi.agent-session.oauth.store`
  - `psi.agent-session.oauth.providers`
  - `psi.agent-session.oauth.pkce`
  - `psi.agent-session.oauth.callback-server`

Testing strategy:
- preserve existing proof where possible
- move only tests clearly owned by the extracted provider-auth component boundary
- rename moved component-owned tests to `psi.provider-auth.*-test` namespaces so namespace ownership matches component ownership
- preserve subfamily ownership in moved tests:
  - provider-auth helper tests under `psi.provider-auth.core-test`
  - OAuth-family tests under `psi.provider-auth.oauth.*-test`
- keep higher-level app-runtime/rpc/agent-session integration tests in place and update their requires only
