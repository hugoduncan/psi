# 229 — Implementation notes

Append-only local memory: decisions, discoveries, review notes.

## Plan-review ambiguity pass (2026-06-16)

ACTIONABLE. 2 ambiguities → design-steps.md. Highest: DI-1 fixes internal
`:next` fall-through but leaves terminal-`:yield :text` resolution for the two
summary steps unspecified — the standalone `/delegate` result-text path
(`canonical_workflows`/`terminal-yielded-text`) keys strictly off
`(last :step-order)`, diverging from the delegate-gate path
(`terminal-result-envelope` via `:terminal-outcome`); converged standalone run
could surface the never-run not-converged summary. Second: `N iterations`
template source undefined. See design-steps.md.
