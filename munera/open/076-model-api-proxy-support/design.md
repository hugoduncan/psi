Issue provenance
- GitHub issue: #27
- URL: https://github.com/hugoduncan/psi/issues/27
- Title: Add proxy support (socks, http, https, ...) for accessing model API

Goal
Add a complete, explicit design for outbound proxy support on psi model API access so users in restricted or corporate network environments can route model traffic through supported proxies without ad hoc code changes.

Problem
psi can already target custom providers, base URLs, and custom headers, but that does not by itself guarantee transport-level proxy support for outbound requests to model APIs. Users in environments that require HTTP, HTTPS, or SOCKS proxy egress may be unable to use psi at all unless proxy handling is a first-class supported capability.

Intent
Define the product behavior, configuration surface, scope boundaries, and acceptance criteria for proxy-mediated model API access across psi’s model-provider/runtime surfaces.

Scope
In scope
- outbound proxy support for model API requests made by psi
- support for the proxy classes explicitly called out in the issue: HTTP, HTTPS, and SOCKS, or a clearly documented compatibility surface that satisfies those classes
- a canonical configuration surface for proxy use
- precedence and interaction rules when multiple configuration sources could apply
- how proxy configuration applies across built-in and custom providers when they use psi’s canonical outbound model request path
- documentation sufficient for a user in a restricted network environment to configure and verify proxy usage
- tests that prove proxy configuration is interpreted and applied correctly on the canonical model API request path

Out of scope
- changing prompt behavior, model behavior, or provider semantics unrelated to transport routing
- introducing unrelated provider features
- solving all corporate-environment concerns beyond proxying outbound model API traffic
- guaranteeing support for provider SDK/network paths that bypass psi’s canonical outbound HTTP path, unless this task explicitly converges them onto that path
- advanced proxy auth schemes or enterprise certificate-management work beyond what is required to make the supported proxy surface explicit

Users and scenarios
- A user behind a corporate outbound proxy needs psi to reach Anthropic, OpenAI, or compatible model APIs.
- A user wants one documented way to configure proxying without patching code locally.
- A user may need either a global proxy setting for all model traffic or a provider-specific override when different providers require different network routing.

Required design decisions
1. Canonical configuration surface
The design must choose and document the supported configuration surfaces for proxying model API traffic. The surface must be explicit and minimal.

The design must state whether proxy configuration is supported through:
- environment variables only
- psi config only
- both environment variables and psi config

If both are supported, the design must define exact precedence.

2. Proxy targeting scope
The design must state whether proxy configuration is:
- global for all model API traffic
- overrideable per provider
- both, with explicit precedence and fallback

3. Supported proxy kinds
The design must explicitly state the supported proxy kinds and their canonical representation:
- HTTP
- HTTPS
- SOCKS

If SOCKS support is version-limited or library-limited, that limitation must be stated explicitly.

4. Canonical request-path applicability
The design must identify the canonical outbound model request path(s) that honor proxy configuration.

The design must state:
- which psi provider/runtime components are in scope for proxy application
- whether all model providers already share a common HTTP boundary
- if not, whether this task requires convergence onto a shared proxy-aware boundary or explicitly limits support to the providers already using that boundary

5. Error and visibility behavior
The design must specify the expected user-visible behavior when proxy configuration is invalid or unusable.

At minimum it must decide whether psi should:
- fail fast during request setup when proxy configuration is malformed
- surface connection/setup failures with explicit mention that proxy configuration was involved when that is knowable
- expose the effective proxy source in diagnostics, logs, or introspection, and if so at what level while avoiding secret leakage

6. Documentation surface
The design must require user-facing documentation that covers:
- how to configure proxy support
- configuration precedence
- supported schemes/examples
- known limitations
- how to verify that proxy-mediated access is being attempted

Solution shape
The preferred shape is a single canonical proxy-resolution layer for outbound model API requests.

That layer should:
- resolve the effective proxy configuration from the supported sources
- validate and normalize the configured proxy value(s)
- expose a canonical proxy configuration value to the outbound HTTP execution path
- avoid provider-by-provider ad hoc proxy handling when the transport path is shared

If provider-specific overrides are supported, they should compose with the shared resolver rather than introducing separate unrelated code paths.

Configuration contract
The final implementation derived from this design must provide one unambiguous configuration contract.

This design intentionally does not lock the exact syntax yet, but the eventual syntax must satisfy all of the following:
- a user can tell where to place proxy configuration
- a user can tell how to express at least HTTP, HTTPS, and SOCKS proxy endpoints
- a user can tell whether a setting applies globally or only to one provider
- a user can tell which source wins when both environment and psi config specify proxy settings

Acceptance criteria
- psi has a documented, canonical way to configure outbound proxying for model API traffic.
- The design defines whether proxy support is global, per-provider, or both, and the precedence rules are explicit.
- The design explicitly covers HTTP, HTTPS, and SOCKS proxy scenarios, including any supported-scope limitations.
- The design identifies the runtime/provider request path(s) that must honor proxy configuration.
- The design defines how invalid or unsupported proxy configuration fails and what users can observe.
- The design requires user-facing documentation with concrete configuration examples.
- The design is specific enough that an implementation task can proceed without needing further scope clarification about configuration source, precedence, or affected request paths.

Constraints
- Keep the design small and implementation-guiding rather than speculative.
- Prefer one obvious configuration model over a wide compatibility matrix.
- Avoid introducing multiple competing proxy surfaces unless there is a clear migration or compatibility reason.
- Preserve the project’s preference for explicit canonical surfaces over silent fallback behavior.

Architecture alignment
- psi already emphasizes canonical shared runtime surfaces over adapter-local or caller-local behavior.
- This task should follow that pattern by defining proxy support once at the canonical outbound model API transport boundary, not separately in each UI or command path.
- If provider implementations currently diverge, the design should call that out and choose either convergence or explicit limitation.

Open ambiguities after this refinement pass
- None. The task design is clear at the behavior/design level.

Non-goals for this task
- selecting a specific third-party HTTP client library replacement, unless existing runtime constraints make that unavoidable
- adding a broader enterprise networking subsystem beyond the proxy capability needed for model API access
