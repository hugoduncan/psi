# 057 — Implementation notes

## Provenance

Continuation of 056 Phase A architecture. Design derived from the statechart ↔ workflow correspondence established in 056's design document and tightened to align with it explicitly.

## Resolved ambiguities

The 057 task now makes these implementation-shaping decisions explicit:

- Every executable step compiles to canonical step-local `.acting` and `.blocked` states; judged steps additionally compile `.judging`.
- Only actor execution can block in Phase A. Judge execution does not block.
- Entry actions execute work; acting/judging exits record success/failure/judge data; blocked entry records blocked data.
- Working memory is authoritative for execution control; the workflow-run atom is its projected external view.
- `workflow-run[:status]` is derived from active chart state after each processed event.
- Judge routing uses a static `:judge/signal` event plus payload guards rather than dynamic event names.
- Entering `.acting` allocates a fresh attempt id; `.judging` shares that attempt; actor retry/resume allocate fresh attempts; judge retry stays on the same attempt and same judge session.
- The event queue is FIFO; terminal entry discards queued tail events; blocked is represented by quiescence in a step-local `.blocked` state.
- Phase B helper functions may be reused only as record/update substrate. The chart is the sole owner of control flow.
