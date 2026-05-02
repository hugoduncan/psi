# 076 Model API proxy support

## Provenance
- GitHub issue: #27
- Issue URL: https://github.com/hugoduncan/psi/issues/27
- Issue title: Add proxy support (socks, http, https, ...) for accessing model API
- Refinement branch: issue-27-proxy-support-model-api
- Worktree: /Users/duncan/projects/hugoduncan/psi/issue-27-proxy-support-model-api

## Intent
Enable psi to reach model APIs through outbound proxies so users in restricted or corporate network environments can use psi without ad hoc source changes.

## Problem
psi currently supports provider-level endpoint, header, and authentication customization, but the issue requests transport-level outbound proxy support. In environments where all outbound traffic must traverse an HTTP, HTTPS, or SOCKS proxy, provider configuration alone is insufficient if the underlying HTTP client cannot be directed through a proxy by psi.

## Users and scenarios
### Primary user
An operator running psi in an environment where direct outbound access is blocked and all model API traffic must go through an organizational proxy.

### Core scenarios
1. A user configures proxy support without editing source code.
2. A user runs psi against a supported model provider and the outbound model API requests traverse the configured proxy.
3. A user can tell from the docs which proxy forms are supported, where the configuration is set, and what scope that configuration has.
4. A user gets the same proxy behavior across providers that use psi's shared model API transport path.

## Scope
### In scope
- A user-visible configuration surface for outbound proxying of model API requests.
- Support for the common proxy families explicitly named in the issue: HTTP, HTTPS, and SOCKS, or a documented canonical proxy surface that covers those corporate egress scenarios equivalently.
- Behavior that applies to model API access across psi's provider integrations rather than being limited to a single provider definition.
- Clear user documentation for configuring and using proxy support in restricted environments.
- Executable verification that proxy configuration is applied to outbound model API traffic.

### Out of scope
- General corporate-environment setup beyond outbound model API proxying.
- Changes to model behavior, prompt behavior, or non-network provider capabilities.
- Unrelated provider feature work.
- Proxy support for arbitrary external integrations unless they are part of the same model API transport path.
- Defining enterprise policy, credential management, or proxy infrastructure provisioning outside psi.

## Required behavior
1. psi must expose one canonical user-facing way to configure outbound proxying for model API traffic.
2. That configuration must affect the actual HTTP transport used for model API requests.
3. The behavior must apply at the shared model API transport boundary for providers that use that shared path, not as a one-off provider-specific patch unless a provider genuinely bypasses the shared path and that exception is documented.
4. The resulting feature must be usable without ad hoc source changes or local code patching.
5. The implementation must document any provider or transport paths that do not participate in the shared proxy behavior.

## Configuration-surface requirements
The implementation may choose the exact mechanism during implementation, but the design requires these invariants:
- exactly one canonical configuration story for users,
- no requirement for per-run source edits,
- clear statement of whether configuration is global, per provider, or a layered combination,
- clear statement of precedence if more than one configuration source is intentionally supported,
- the same documented story in both runtime behavior and user-facing docs.

## Documentation requirements
User-facing documentation must state:
- which proxy families are supported,
- how to configure them,
- whether the setting is global or provider-scoped,
- any precedence or override rules,
- any unsupported modes, boundaries, or caveats relevant to operators in restricted environments.

## Constraints
- The design must fit the existing provider/runtime architecture rather than introducing provider-specific special cases without necessity.
- The design must preserve a single obvious way to configure outbound model API proxying.
- The design must be testable without depending on external corporate infrastructure.
- The implementation must be shaped so unsupported proxy forms fail as explicit documented non-goals rather than silent partial support.

## Acceptance criteria
1. A user can configure psi so outbound model API requests are sent through a proxy.
2. The supported proxy types and configuration surface are documented in user-facing documentation.
3. Proxy configuration applies to the relevant model API transport path without requiring ad hoc code changes.
4. The behavior works across the intended provider/model API path rather than only for one narrow provider-specific path.
5. The implementation includes executable verification that proxy configuration is honored by the transport layer used for model API requests.
6. Any unsupported proxy mode, provider path, or transport boundary is documented explicitly so operators know what is and is not covered.
7. The final implementation leaves users with one unambiguous configuration story rather than multiple conflicting proxy mechanisms.

## Non-ambiguity statement
This design is complete and unambiguous at the task-design level.

Resolved design decisions:
- The task is specifically about transport-level outbound proxy support for model API access.
- The feature must be user-configurable and documented.
- The feature must apply at the shared model API transport boundary wherever that boundary is used.
- Unsupported boundaries, if any remain, must be documented explicitly.

Implementation latitude that remains acceptable and does not create design ambiguity:
- The exact canonical configuration mechanism may be chosen during implementation, provided it remains singular from the user's perspective and satisfies the acceptance criteria.
- The exact proof technique may be chosen during implementation, provided it executably verifies that configured proxy behavior reaches the transport used for model API requests.
