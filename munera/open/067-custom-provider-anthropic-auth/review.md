# Review

Status: ready to close.

- Implementation matches the core design invariant: `:api` selects transport, `:provider` selects auth/base-url/identity.
- Fix is small and follows existing architecture.
- Required gap addressed: a focused negative regression now proves a custom `:anthropic-messages` provider with no configured auth still produces the existing missing-auth failure.
- Optional follow-on remains: provider-auth resolution is still duplicated between prompt-request and runtime helper paths, so precedence drift is still possible.
- Optional follow-on: either extract a shared provider-auth resolver or explicitly document why the duplication remains acceptable.
