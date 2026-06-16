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

## Plan-review inconsistency pass (2026-06-16)

ACTIONABLE. 2 inconsistencies → design-steps.md. (1) `review-task-design-test`
already RED in psi-main (asserts :max-iterations 6 vs edn 3 after de19cc5bf);
plan Slice 2 presumes a green baseline. Confirmed via focused run
(1 fail, -6 +3). (2) `task-lifecycle-test` is positionally hard-coded
(count/name/type vectors, nth indices, repeat-9 yields); plan's "extend OR add
new 229 test" alternative leaves it failing — existing test must be updated in
Slices 2 & 3. See design-steps.md.
