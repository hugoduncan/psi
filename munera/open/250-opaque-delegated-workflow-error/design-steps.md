# Design steps — architectural review (design-review session, turn 1)

- [ ] Define one canonical owner for delegated-child failure normalization at the
      workflow-runtime delegate-step boundary. The parent workflow attempt should
      receive one structured failure envelope derived from the persisted child
      run's canonical failure surfaces (including step attempt
      `:execution-error` and `:terminal-outcome`), while agent-session/tool/API
      layers only project or render that envelope. Do not let each caller inspect
      child sessions or independently synthesize competing error semantics. This
      preserves the single-source/one-way architecture and keeps generic workflow
      execution semantics in workflow runtime rather than in an adapter.

- [ ] Replace the suggested raw stack-trace propagation with a bounded, redacted,
      deterministic delegated-failure diagnostic contract. Preserve an actionable
      human-readable message plus stable structured cause metadata (for example
      reason and child run/step/attempt identity), retain a generic fallback when
      no safe cause is available, and do not expose arbitrary exception data,
      transcripts, provider payloads, or session internals through the parent
      `:error` surface. Raw stack traces are unstable implementation detail and
      may leak sensitive/runtime-local data across the child-parent boundary.
