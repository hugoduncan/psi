Not started.

Settled target:
- component path: `components/turn-statechart/`
- namespace family: `psi.turn_statechart.*`
- first-cut authoritative namespace: `psi.turn_statechart.core`
- first-cut source file: `components/turn-statechart/src/psi/turn_statechart/core.clj`

Initial direction:
- the extracted namespace must be renamed to match the new component name/path
- keeping `psi.agent-session.turn-statechart` as the authoritative namespace under a non-`agent-session` component is explicitly not acceptable for done-ness
- a compatibility shim is allowed only as a migration aid during the slice and must be removed before task completion
- focused statechart tests move with the component
- all direct consumers migrate to the extracted namespace in this slice
