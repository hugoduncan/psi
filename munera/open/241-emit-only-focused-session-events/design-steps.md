# Design steps — 241 emit-only-focused-session-events

## Architecture review follow-ups

- [ ] Resolve the "where to place the focus gate" open question consistent with RPC's fanout-ownership boundary: the projection-delivery rule (doc/architecture.md) makes RPC the single subscriber-aware fanout point that "recomputes payloads from current canonical state plus connection-local focus", and `emit-event!` already hosts the analogous `topic-subscribed?` gate. Prefer layering focus gating at that delivery/fanout boundary rather than scattering it across per-session emitter call-sites (`make-request-emitter`/progress loop), which would fragment fanout policy across emission sites and duplicate the session-scoped/cross-session partition. If payload-shape coupling is a concern, derive "session-scoped" structurally (e.g. from the presence of `:session-id`, consistent with `required-event-payload-keys`) rather than maintaining a second hand-curated event set.
