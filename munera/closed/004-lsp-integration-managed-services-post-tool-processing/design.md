SUPERSEDED by `065-remove-lsp-client` on 2026-04-28.

Goal: continue the LSP extension work on top of managed services and post-tool processing.

Context:
- The extension-owned LSP vertical slice is already landed.
- Managed stdio JSON-RPC attachment, diagnostics enrichment, and command surfaces are in place.
- Remaining work is about confidence in the real runtime path, test simplification, and observability/debug telemetry decisions.

Acceptance:
- LSP flows are proven through the real runtime path where practical
- diagnostics-oriented tests rely less on nullable stubs
- runtime telemetry/observability is sufficient to debug startup and request flow
