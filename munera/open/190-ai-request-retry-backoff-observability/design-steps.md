# Design follow-up steps

- [x] Specify the authoritative storage/projection path for completed provider retry history: whether retry attempt records are derived from provider telemetry captures, stored under session state, or stored elsewhere; how they are keyed by session/turn/request/attempt; and which existing/new EQL resolvers expose the required session-, turn-, and request-level retry answers.
- [x] Specify how provider-boundary retry keeps active retry/backoff status visible through the existing TUI/Emacs/app-runtime session retry projection while a delay is pending, including when `:retry`, `:retry-attempt`, phase/status fields are set and cleared, and whether this replaces or preserves the old statechart retry state.
