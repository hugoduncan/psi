# Design steps — 241 emit-only-focused-session-events

## Architecture review follow-ups

- [x] Resolve the "where to place the focus gate" open question consistent with RPC's fanout-ownership boundary: the projection-delivery rule (doc/architecture.md) makes RPC the single subscriber-aware fanout point that "recomputes payloads from current canonical state plus connection-local focus", and `emit-event!` already hosts the analogous `topic-subscribed?` gate. Prefer layering focus gating at that delivery/fanout boundary rather than scattering it across per-session emitter call-sites (`make-request-emitter`/progress loop), which would fragment fanout policy across emission sites and duplicate the session-scoped/cross-session partition. If payload-shape coupling is a concern, derive "session-scoped" structurally (e.g. from the presence of `:session-id`, consistent with `required-event-payload-keys`) rather than maintaining a second hand-curated event set.

## Ambiguity review follow-ups

- [x] Specify the `nil`-focus fallback precisely: define which session is the "initial/default" session that still emits when `focus-session-id` is `nil` (the code's `default-session-id-in` = first listed session), and state the intended behaviour when focus is `nil` *and multiple sessions have activity* — does the fallback emit only for the first-listed session and suppress the rest, and is that intended?
- [x] Resolve the undecided `session/updated` partition (open question 2, the design's stated "crux"): decide whether a non-focused session emits a terminal `session/updated` on phase-completion (so the session tree shows "done" without refocus) or whether `context/updated` alone carries per-session phase. The acceptance criteria currently list `session/updated` as focus-gated without resolving this terminal case.

## Inconsistency review follow-ups

- [x] Reconcile the contradictory classification of `session/resumed` and `session/rehydrated`: the Scope section lists them as "subject to focus gating," the Constraints section says "do not gate emission that is part of the focus/navigation transition itself," and the Why section establishes these two events *are* the rehydration bundle emitted only on navigation (i.e. after focus is set to that session). State explicitly whether they belong in the focus-gated set (implying a non-focused emission path that would be suppressed) or are excluded because their only emission path always passes the gate — the current text asserts both.
