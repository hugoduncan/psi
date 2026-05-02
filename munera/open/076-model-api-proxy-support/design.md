# 076 Model API proxy support

## Provenance
- GitHub issue: #27
- Issue URL: https://github.com/hugoduncan/psi/issues/27
- GitHub PR: #66
- PR URL: https://github.com/hugoduncan/psi/pull/66
- PR branch: issue-27-proxy-support-model-api
- Issue title: Add proxy support (socks, http, https, ...) for accessing model API
- Refinement branch: issue-27-proxy-support-model-api
- Worktree: /Users/duncan/projects/hugoduncan/psi/issue-27-proxy-support-model-api

## Intent
Enable psi to reach model APIs through outbound proxies so users in restricted or corporate network environments can use psi without ad hoc source changes.

## Problem
psi currently supports provider-level endpoint, header, and authentication customization, but the issue requests transport-level outbound proxy support. In environments where all outbound traffic must traverse an HTTP, HTTPS, or SOCKS proxy, provider configuration alone is insufficient if the underlying shared HTTP transport cannot be directed through a proxy by psi.

## Existing architecture and fit
- Model API calls currently flow through the shared AI transport layer in `components/ai/`.
- The built-in Anthropic and OpenAI providers both issue HTTP requests through `clj-http.client/post`.
- Custom provider definitions change API base URLs, auth, headers, and model metadata, but they do not currently provide a dedicated transport-proxy surface.
- Because the transport boundary is shared, proxy behavior should be introduced once in the common request-options path used by provider transport functions, instead of inventing provider-specific proxy logic.

This task therefore fits the existing architecture by adding a canonical proxy-configuration projection into provider request option construction, while keeping provider protocol semantics unchanged.

## Users and scenarios
### Primary user
An operator running psi in an environment where direct outbound access is blocked and all model API traffic must go through an organizational proxy.

### Core scenarios
1. A user configures proxy support without editing source code.
2. A user runs psi against a supported model provider and the outbound model API requests traverse the configured proxy.
3. A user can tell from the docs which proxy forms are supported, where the configuration is set, and what scope that configuration has.
4. A user gets the same proxy behavior across providers that use psi's shared model API transport path.
5. A user can still override provider base URLs independently of proxying; proxy configuration affects transport routing, not model/provider identity.

## Scope
### In scope
- A user-visible configuration surface for outbound proxying of model API requests.
- Support for the common proxy families explicitly named in the issue: HTTP, HTTPS, and SOCKS.
- Behavior that applies to model API access across psi's provider integrations rather than being limited to a single provider definition.
- Clear user documentation for configuring and using proxy support in restricted environments.
- Executable verification that proxy configuration is applied to outbound model API traffic.
- Configuration parsing and normalization sufficient to support a single canonical runtime state shape for proxy settings.

### Out of scope
- General corporate-environment setup beyond outbound model API proxying.
- Changes to model behavior, prompt behavior, or non-network provider capabilities.
- Unrelated provider feature work.
- Proxy support for arbitrary external integrations unless they are part of the same model API transport path.
- Defining enterprise policy, credential management, or proxy infrastructure provisioning outside psi.
- Runtime UI for interactively editing proxy settings.

## Chosen implementation strategy
Implement proxy support as a shared transport concern in `components/ai/`, backed by one canonical configuration source: environment-driven proxy settings read by psi at runtime and translated into clj-http request options.

### Why this strategy fits
- `clj-http` already supports proxy-related request options, so psi can rely on the existing HTTP client rather than adding a new transport stack.
- Environment-driven proxy configuration matches operator expectations in restricted environments and avoids inventing a new project-specific config file surface for a feature that is usually deployment-scoped.
- A shared request-options helper keeps Anthropic, OpenAI, and compatible custom providers aligned through one mechanism.
- This approach preserves the current provider model architecture: provider definitions continue to describe endpoint/auth/model semantics, while transport routing concerns stay at the transport boundary.

## Canonical configuration story
The canonical user-facing configuration surface is environment variables.

### Supported environment variables
- `HTTP_PROXY`
- `HTTPS_PROXY`
- `ALL_PROXY`
- lowercase equivalents when present (`http_proxy`, `https_proxy`, `all_proxy`)
- `NO_PROXY` / `no_proxy` only if the implementation can support it coherently through clj-http for the same transport path; otherwise it must be explicitly documented as unsupported in this task's implementation.

### Canonical semantics
- `HTTPS_PROXY` applies to HTTPS model API URLs.
- `HTTP_PROXY` applies to HTTP model API URLs.
- `ALL_PROXY` is the fallback when the scheme-specific variable is absent.
- Scheme-specific variables take precedence over `ALL_PROXY`.
- Uppercase and lowercase names are treated as equivalent, with uppercase winning if both are set to different values.
- Provider/model config files do not gain a separate proxy field in this task.
- Users configure proxies outside psi and then run psi normally.

This yields one unambiguous story: psi inherits proxy settings from the process environment and applies them to model API transport.

## Required runtime behavior
1. When a built-in or compatible provider transport prepares an outbound HTTP request, psi must resolve the effective proxy from the request URL scheme and the environment.
2. psi must translate that resolved proxy into the clj-http request options expected by the underlying client.
3. If no proxy environment variable applies, psi must preserve current direct-connection behavior.
4. If a proxy URI is malformed or unsupported, psi must fail explicitly with a clear diagnostic rather than silently ignoring the setting.
5. Proxy support must apply consistently across the shared model API transport path, including built-in OpenAI and Anthropic providers and custom providers that reuse those transport implementations.
6. Provider capture callbacks must continue to work; proxy support must not remove existing request/response capture behavior.
7. Proxy support must not alter request payloads, auth semantics, or provider selection logic.

## Main procedural approach
1. Add a small shared helper namespace in `components/ai/` responsible for:
   - reading proxy-related environment variables,
   - normalizing them into a canonical internal map,
   - selecting the effective proxy for a target request URL,
   - converting the chosen proxy into clj-http request options.
2. Route all model-provider `http/post` calls through a shared request-options merge that includes those proxy options.
3. Keep provider request construction separate from transport option enrichment: request body/headers stay provider-owned; proxy transport options are added immediately before the `http/post` boundary.
4. Add focused unit tests around proxy resolution and request-option injection.
5. Add provider-level tests proving that OpenAI and Anthropic transport calls pass the expected proxy options to `http/post` when the relevant environment variables are set.
6. Update user docs to describe the canonical environment-based configuration story, supported proxy variable names, precedence, and caveats.

## Intended implementation slices
1. Inspect the existing transport helper seams in `components/ai/src/psi/ai/providers/openai/transport.clj` and `components/ai/src/psi/ai/providers/anthropic.clj` and choose the narrowest shared enrichment point that both providers can use without changing provider protocols.
2. Add a dedicated proxy helper namespace under `components/ai/src/psi/ai/` with pure functions for environment normalization, URL-scheme selection, proxy URI parsing, and clj-http option projection.
3. Keep environment reading at the edge of that helper so tests can exercise pure logic with injected env maps instead of mutating process state globally.
4. Extend the provider transport call path so the final request map passed into `clj-http.client/post` is `(merge existing-options proxy-options)` and remains directly observable to existing stubs.
5. Add focused unit tests for the helper first, then provider transport tests for both OpenAI and Anthropic integration points.
6. Update docs only after the exact supported URI forms and any `NO_PROXY` limitation are proven by the implementation/tests.

## Builder-oriented implementation approach
### Files and namespaces expected to change
- Add one new shared helper namespace under `components/ai/src/psi/ai/`, likely `proxy.clj` or equivalently named, containing only proxy normalization/selection/projection logic.
- Update `components/ai/src/psi/ai/providers/openai/transport.clj` so the final options map passed to `clj-http.client/post` is enriched by the shared proxy helper.
- Update `components/ai/src/psi/ai/providers/anthropic.clj` at the equivalent `http/post` boundary with the same enrichment call.
- Add focused tests under the `components/ai/test/psi/ai/` tree for the shared helper and update provider transport tests in the existing OpenAI and Anthropic test namespaces.
- Update user docs in `README.md`, `doc/psi-project-config.md`, and `doc/custom-providers.md` only where they describe real supported behavior after implementation is proved.

### Integration point constraints
- The builder must not add proxy parameters to provider protocol methods, runtime session state, or model definitions.
- The builder must not thread proxy state through unrelated runtime layers if the provider transport already has enough information via request URL and process environment.
- The builder should enrich only the final clj-http request options map, preserving current provider-owned request body, headers, auth, capture hooks, response decoding, and exception behavior.
- If the codebase already has a common request-options helper used by both providers, extend that helper instead of inserting duplicate calls in multiple places; otherwise a direct shared helper call at each provider `http/post` boundary is acceptable.

### Expected helper API shape
The implementation should be explicit enough that a later builder does not need to invent the helper contract. A suitable public surface is:
- `normalize-proxy-env` — pure, takes an env map, returns normalized logical proxy config.
- `effective-proxy-for-url` — pure, takes normalized config plus request URL, returns either `nil` or a parsed effective proxy map.
- `proxy-request-options` — pure, takes parsed effective proxy data, returns a clj-http-compatible options map.
- `request-proxy-options` or equivalent convenience helper — edge function that reads the process env once and returns the final options map for a given request URL.

Names may differ, but the separation of concerns must remain: normalize env → resolve effective proxy → project request options.

### Expected request enrichment flow
For each provider transport call:
1. Construct the current provider request map exactly as today.
2. Determine the request URL string already being posted.
3. Call the shared proxy helper with that URL.
4. Merge the returned options into the final request map immediately before `http/post`.
5. Leave the no-proxy case as an empty map merge so current behavior is unchanged.

The implementation should prefer a shape equivalent to:
```clojure
(let [request-options {...provider-owned request options...}
      proxy-options   (proxy/request-proxy-options request-url)]
  (http/post request-url (merge request-options proxy-options)))
```
If a shared helper returns the final merged map instead, that is acceptable only if the provider-owned request map remains directly observable to tests and capture seams.

## Key algorithms and rules
### 1. Environment normalization
Input: current process environment map.

Normalization rules:
- read uppercase and lowercase forms for each supported variable;
- for each logical key, prefer uppercase over lowercase when both are present;
- ignore blank values;
- keep only the proxy variables relevant to transport resolution.

Canonical normalized shape:
```clojure
{:http  "http://proxy.example:8080"
 :https "http://secure-proxy.example:8443"
 :all   "socks5://proxy.example:1080"
 :no-proxy "example.com,localhost"} ; only if supported in the implementation
```

### 2. Effective-proxy selection
Input: normalized proxy config and request URL.

Selection rules:
- parse the request URL scheme;
- for `https` URLs, choose `:https` first, else `:all`;
- for `http` URLs, choose `:http` first, else `:all`;
- for any other scheme, do not proxy and treat it as outside the supported surface unless the underlying transport already supports it and the implementation chooses to cover it explicitly;
- if `NO_PROXY` support is implemented, bypass the proxy when the target host matches the bypass rules.

### 3. Request-option projection
Input: effective proxy URI.

Projection rules:
- parse the proxy URI once into its components;
- require a host and numeric port; missing host/port is an error, not an implicit default;
- map supported schemes to clj-http options in the exact form expected by the client, including host, port, and protocol-specific flags;
- attach auth fields only if present in the proxy URI and supported by clj-http;
- merge the resulting proxy options with existing request options without changing existing headers/body/as/throw-exceptions behavior.

### 4. Failure behavior
- malformed proxy URI => explicit exception with the offending environment variable name;
- unsupported proxy scheme => explicit exception listing the supported schemes for this task;
- absent proxy => no added proxy options.

## Main data structures and state shapes
### Normalized proxy config
```clojure
{:http     string?
 :https    string?
 :all      string?
 :no-proxy string?} ; optional, only if supported
```

### Parsed effective proxy
```clojure
{:source-env :https
 :raw-uri    "http://proxy.example:8080"
 :scheme     :http
 :host       "proxy.example"
 :port       8080
 :username   "user"      ; optional
 :password   "secret"    ; optional, avoid logging raw secret
}
```

### Request option enrichment output
A plain clj-http options map merged into the provider's existing request map, for example:
```clojure
{:proxy-host "proxy.example"
 :proxy-port 8080
 :proxy-user "user"
 :proxy-pass "secret"
 :proxy-scheme :http}
```

The final concrete keys must match `clj-http` expectations, but the helper should expose one internal projection boundary so providers do not each reinvent it.

## Interface surfaces to add or change
### Code surfaces
- Add a shared proxy helper namespace under `components/ai/src/psi/ai/`.
- Update OpenAI transport code to enrich outbound `http/post` options with resolved proxy settings.
- Update Anthropic transport code to enrich outbound `http/post` options with resolved proxy settings.
- If there is already a common request-helper seam for provider transports, prefer extending that seam instead of duplicating helper calls.

### Documentation surfaces
- Update `README.md` and/or `doc/psi-project-config.md` with the canonical proxy configuration story.
- Add or update a focused doc section describing:
  - supported variables,
  - precedence,
  - supported proxy URI forms,
  - whether `NO_PROXY` is supported,
  - examples for HTTP/HTTPS and SOCKS usage.
- Update `doc/custom-providers.md` to clarify that custom providers reuse the same transport-level proxy behavior when they go through the built-in OpenAI or Anthropic transport paths.

### User-visible command/config surfaces
- No new slash command.
- No new `.psi/project.edn` or model-definition proxy field.
- No new resolver/API surface is required for this task unless the implementation adds introspection for diagnostics; if so, it must be documented as auxiliary, not as the primary configuration mechanism.

## Architectural fit and constraints
- The change must preserve the current separation between provider semantics and transport mechanics.
- The helper should be pure-by-default and not require wider runtime state threading.
- Existing request capture/test seams should remain intact so current provider tests can continue to observe the final request map at the `http/post` boundary.
- The implementation must not introduce a parallel proxy story in project config or provider definitions.

## Invariants
- One canonical user configuration story: environment variables.
- Transport-level proxy support is orthogonal to provider definition and model selection.
- OpenAI and Anthropic transports must resolve proxy configuration the same way.
- Direct behavior remains unchanged when proxy variables are unset.
- Proxy parsing and selection logic lives in one shared place.
- Secrets from proxy credentials must not be exposed in request-capture logs without redaction.

## Edge cases and explicit decisions
- If both uppercase and lowercase environment variables are present with different values, uppercase wins.
- If both scheme-specific and `ALL_PROXY` are present, the scheme-specific value wins.
- Blank env values behave as unset.
- Request URLs without `http` or `https` schemes are outside the supported proxy surface and should not silently coerce into a supported scheme.
- SOCKS support is in scope only if `clj-http` supports the required option projection cleanly from the same helper. If a named SOCKS variant is not actually supported by the underlying client, the implementation must fail clearly and document the limitation instead of claiming broad SOCKS support.
- `NO_PROXY` support is optional in this task design only if the implementation surface cannot support it coherently with the chosen client options. The final implementation must either support it and document matching rules, or document it as unsupported.
- Proxy support applies only to model API HTTP traffic; it does not imply proxying unrelated runtime subsystems.
- Existing tests that stub `http/post` must keep working; helper injection should remain observable at the final request map passed into `http/post`.

## Verification expectations
The implementation is done only when all of the following are true:
1. Focused unit tests cover environment normalization, precedence, effective-proxy selection, malformed URI handling, missing host/port handling, and unsupported-scheme handling.
2. OpenAI transport tests prove that an applicable proxy environment variable yields proxy options in the final `http/post` request map.
3. Anthropic transport tests prove the same.
4. Tests also prove that no proxy options are added when no relevant environment variable is set.
5. If proxy credentials are supported, tests prove they are projected correctly and not leaked in any diagnostic surface that is asserted.
6. Docs clearly describe the supported proxy configuration story and any boundaries or caveats.

### Verification commands to expect during implementation
The builder should expect to run focused AI-component tests first, then broader verification only if needed. At minimum, verification should include:
- focused tests for the new proxy helper namespace,
- focused Anthropic transport tests,
- focused OpenAI transport tests,
- any doc or lint checks normally run for touched files if the repository has a standard task for them.

Exact commands may follow existing project conventions, but the builder must preserve a narrow feedback loop and record the real commands/results in `implementation.md`.

## Alternatives considered
### Add proxy fields to provider definitions
Rejected because proxying is a deployment/transport concern, not model-definition identity. It would create a second competing configuration story and force duplicate settings across providers.

### Add a new psi-specific config file surface for proxy settings
Rejected because it duplicates well-understood process-environment behavior, adds new configuration machinery, and is less natural for corporate execution environments.

### Implement proxy logic separately inside each provider
Rejected because the built-in providers already share the same HTTP client boundary and duplicated logic would drift.

### Add broad runtime/global networking settings first
Rejected for this task because the requested behavior is specifically model API reachability and the narrowest correct change is at the shared AI transport boundary already used by the affected providers.

## Acceptance criteria
1. A user can configure psi so outbound model API requests are sent through a proxy by setting documented environment variables before launching psi.
2. The supported proxy types and configuration surface are documented in user-facing documentation.
3. Proxy configuration applies to the relevant model API transport path without requiring ad hoc code changes.
4. The behavior works across the intended provider/model API path rather than only for one narrow provider-specific path.
5. The implementation includes executable verification that proxy configuration is honored by the transport layer used for model API requests.
6. Any unsupported proxy mode, provider path, or transport boundary is documented explicitly so operators know what is and is not covered.
7. The final implementation leaves users with one unambiguous configuration story rather than multiple conflicting proxy mechanisms.

## Non-ambiguity statement
This design is complete and unambiguous for implementation handoff.

No material ambiguities remain. The later builder should not invent a different configuration surface or provider-specific mechanism; the intended implementation is shared, environment-driven transport proxy support at the `components/ai` HTTP boundary, with explicit tests and docs.
