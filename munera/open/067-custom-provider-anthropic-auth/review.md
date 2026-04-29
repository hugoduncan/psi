# Review

Status: ready to close.

- Implementation matches the core design invariant: `:api` selects transport, `:provider` selects auth/base-url/identity.
- Fix is small and follows existing architecture.
- Required gap addressed: a focused negative regression now proves a custom `:anthropic-messages` provider with no configured auth still produces the existing missing-auth failure.
- Optional follow-on addressed: provider-auth resolution shared between prompt-request and runtime helper paths is now centralized in `psi.agent-session.provider-auth`.
