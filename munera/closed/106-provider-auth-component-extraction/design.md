# 106 — Provider-auth component extraction

## Goal

Extract the OAuth and provider-auth subsystem into a separate component so provider authentication no longer lives under `agent-session` ownership.

## Why

Task `105-agent-session-component-extraction-map` identified OAuth / provider auth as one of the clearest bounded subsystems currently latent inside `agent-session`.

The current namespace cluster is already strongly shaped as its own domain:

- `components/agent-session/src/psi/agent_session/provider_auth.clj`
- `components/agent-session/src/psi/agent_session/oauth/core.clj`
- `components/agent-session/src/psi/agent_session/oauth/store.clj`
- `components/agent-session/src/psi/agent_session/oauth/providers.clj`
- `components/agent-session/src/psi/agent_session/oauth/pkce.clj`
- `components/agent-session/src/psi/agent_session/oauth/callback_server.clj`

This subsystem is conceptually distinct from session lifecycle orchestration, prompt-turn semantics, tool execution, and workflow runtime ownership.

Extracting it first creates a cleaner dependency map for later work while avoiding the boundary ambiguity present in turn/prompt extractions.

## Problem

OAuth and provider-auth currently live under `psi.agent-session.*`, but they are not fundamentally session-orchestration concerns.

That creates three ownership problems:

- `agent-session` appears to own provider-auth and OAuth runtime concerns that are broader than session lifecycle orchestration
- downstream components that need provider auth must reach into `psi.agent-session.*` namespaces even when their dependency is really auth-specific rather than session-specific
- later turn/prompt extraction work is harder to reason about while provider-auth still sits under `agent-session`

## Intent

Create one explicit lower-level auth component for provider auth and OAuth concerns.

That component should own:

- OAuth context creation
- credential store creation/loading/persistence/locking
- provider registry and provider-specific login/refresh flows
- PKCE helpers
- callback server runtime
- provider-scoped API key resolution from OAuth/store/env/fallback sources
- provider-scoped request-auth options derived from model-registry auth config

That component should not own:

- session lifecycle orchestration
- session dispatch/mutation handler registration
- prompt lifecycle orchestration
- turn execution/runtime semantics
- workflow runtime behavior
- adapter or UI behavior

## Refactoring findings

Repo search shows a compact, coherent provider-auth/OAuth cluster with broad but straightforward consumer migration.

Current direct production consumers include:

- `components/app-runtime/src/psi/app_runtime.clj`
- `components/agent-session/src/psi/agent_session/runtime.clj`
- `components/agent-session/src/psi/agent_session/prompt_request.clj`
- `components/agent-session/src/psi/agent_session/commands.clj`
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
- `components/agent-session/src/psi/agent_session/state_accessors.clj`
- `components/agent-session/src/psi/agent_session/extensions/runtime_fns.clj`
- `components/rpc/src/psi/rpc/session/login.clj`

This is a stronger signal for component extraction than for internal `agent-session` reshaping.

## Scope

In scope:

- create a new `provider-auth` component
- move the authoritative OAuth namespace family out of `agent-session`
- move the authoritative provider-auth helper namespace out of `agent-session`
- update direct consumers to depend on the extracted component
- keep behavior unchanged
- move or update focused tests so ownership is explicit and still proven
- rename moved component-owned tests to `psi.provider-auth.*-test` namespaces so test ownership matches component ownership
- preserve subfamily test ownership explicitly:
  - provider-auth helper tests under `components/provider-auth/test/psi/provider_auth/core_test.clj`
  - OAuth-family tests under `components/provider-auth/test/psi/provider_auth/oauth/*_test.clj`
- record, at completion, which tests moved into `components/provider-auth/test/psi/provider_auth/` and which remained elsewhere, with a brief reason

Out of scope:

- redesigning OAuth semantics
- changing provider login UX
- changing dispatch effect semantics beyond namespace ownership updates
- changing app-runtime or RPC login behavior beyond dependency updates
- redesigning model-registry auth configuration
- broad cleanup of unrelated `agent-session` code

## Boundary

### In the new component

The extracted component should own the authoritative implementation of:

- current `provider_auth.clj` responsibilities
- current `oauth/core.clj` responsibilities
- current `oauth/store.clj` responsibilities
- current `oauth/providers.clj` responsibilities
- current `oauth/pkce.clj` responsibilities
- current `oauth/callback_server.clj` responsibilities

### Above the new component

The following responsibilities must remain outside the new component:

- session lifecycle/state orchestration
- dispatch handler registration and effect orchestration
- commands that present auth actions to users
- RPC transport/login request handling
- app-runtime workflow that invokes auth operations
- resolver/mutation projection of auth state into public graph surfaces

Boundary clarification:

- callers outside the new component may continue to own when auth operations are triggered and how auth state is surfaced to users
- the new component owns how provider auth works, not when the surrounding system chooses to invoke it

## Target shape

Chosen target for this task:

- component path: `components/provider-auth/`
- namespace family: `psi.provider-auth.*`

Naming clarification:

- the component is named `provider-auth` because its public higher-level responsibility is provider authentication
- the largest internal subsystem moved in this slice is the OAuth namespace family
- preserve that internal split explicitly in the extracted namespace family rather than collapsing everything into one `core` namespace

First-cut authoritative namespaces:

- `psi.provider-auth.core`
  - source file: `components/provider-auth/src/psi/provider_auth/core.clj`
  - owns current `provider_auth.clj` responsibilities
- `psi.provider-auth.oauth.core`
- `psi.provider-auth.oauth.store`
- `psi.provider-auth.oauth.providers`
- `psi.provider-auth.oauth.pkce`
- `psi.provider-auth.oauth.callback-server`

API-surface clarifications:

- provider-scoped API key / request-option resolution should be exposed from `psi.provider-auth.core`
- OAuth context/login/logout/refresh/query APIs should remain exposed from `psi.provider-auth.oauth.core`
- lower helper functions may remain public where current tests or legitimate consumers already depend on them, but the extracted component should present one obvious owner per surface

Ownership clarifications:

- preferred steady-state dependency slope should be:
  - higher-level app/runtime/rpc/agent-session code -> `psi.provider-auth.*`
- authoritative extracted `psi.provider-auth.*` namespaces must not depend on `psi.agent-session.*` implementation namespaces at completion
- `psi.provider-auth.core` may continue to depend on `psi.ai.model-registry`

Compatibility-shim preference:

- default expectation is no compatibility shim unless the edit sequence concretely requires one to keep the tree compiling during migration
- if a temporary shim is introduced, it must be removed in the same slice before final verification
- the old `psi.agent-session.provider-auth` and `psi.agent-session.oauth.*` namespaces must not remain authoritative owners at completion

## Consumer migration set

Known direct production consumers to evaluate in this slice:

- `components/app-runtime/src/psi/app_runtime.clj`
- `components/agent-session/src/psi/agent_session/runtime.clj`
- `components/agent-session/src/psi/agent_session/prompt_request.clj`
- `components/agent-session/src/psi/agent_session/commands.clj`
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
- `components/agent-session/src/psi/agent_session/state_accessors.clj`
- `components/agent-session/src/psi/agent_session/extensions/runtime_fns.clj`
- `components/rpc/src/psi/rpc/session/login.clj`
- tests and any remaining direct consumers found by repo search

## Acceptance

- a separate `provider-auth` component exists
- the authoritative provider-auth and OAuth implementation no longer resides under `components/agent-session/`
- the authoritative namespace names match the new component ownership
- no new component cycle is introduced
- all direct consumers compile against the extracted namespaces
- focused provider-auth verification is green from the new component boundary
- at least one higher-level consuming path still works unchanged in behavior
- extracted authoritative `psi.provider-auth.*` namespaces do not depend on `psi.agent-session.*` implementation namespaces directly
- any compatibility shim is used only temporarily during migration and removed before completion

## Suggested migration sequence

1. create `components/provider-auth/` and add repo/component deps
2. move `provider_auth.clj` to `psi.provider-auth.core`
3. move `oauth/store.clj` to `psi.provider-auth.oauth.store`
4. move `oauth/pkce.clj` to `psi.provider-auth.oauth.pkce`
5. move `oauth/callback_server.clj` to `psi.provider-auth.oauth.callback-server`
6. move `oauth/providers.clj` to `psi.provider-auth.oauth.providers`
7. move `oauth/core.clj` to `psi.provider-auth.oauth.core`
8. update direct consumers
9. update/move focused tests
10. remove any temporary compatibility shims
11. run focused verification and record final ownership in task notes

## Verification intent

Focused verification should cover both the extracted component and at least one higher-level consumer.

Representative focused verification surfaces after migration:

- moved provider-auth helper tests under `components/provider-auth/test/psi/provider_auth/core_test.clj`
- moved OAuth-family tests under `components/provider-auth/test/psi/provider_auth/oauth/`
- higher-level consuming-path tests such as app-runtime, RPC login, or runtime/prompt-request tests that still prove unchanged behavior through the extracted component

## Related work

- task `105-agent-session-component-extraction-map` is the umbrella architectural map that identified provider-auth as one of the simplest first extractions
- this task is a concrete child extraction under that umbrella
