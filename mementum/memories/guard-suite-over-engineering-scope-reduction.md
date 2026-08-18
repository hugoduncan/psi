🔁 A regression/guard suite guarding a tiny fix can itself become the recursion
locus. Task 252's clj-kondo fix was a 2-line `:lint-as` config change, but its
committed guard suite grew to ~1,980 lines across 47+ implementation-review
slices and never converged: the review loop reviewed the guards' guards
(ERROR-vs-FAIL taxonomy, jar-export file-set enumeration, exec-format start-
failure sub-classing) rather than the fix, violating `small` and
`simple(x) > complex(x)`. The resolution was scope reduction — delete the whole
suite (`lint_config_test*.clj`), keep the fix, restore the flipped tests.edn
knob the suite had claimed.

Tells: guard-suite size disproportionate to the change; review items keep
arriving about the guards' edge cases, not the mechanism; the suite itself
acquires fixtures, predicates, and taxonomy a "real" test of the fix would never
need. When the loop demonstrably does not converge, stop adding and cut: retain
the minimal fix + one direct proof, drop the escalation.

Distinct from `design-review-loop-divergence` (review-loop wording churn): this
is the guard/test suite as its own over-engineering attractor.
