# Implementation

Task created to resolve whether `skill-registry` really requires preserved registration order, and to remove that ordering from the contract if it is only accidental.

Audit notes from refinement:

- `:session/register-skill` uses only `:changed?` to decide whether to emit `:runtime/refresh-system-prompt`; it does not depend on insertion order
- prompt/discovery consumers read `all-skills`, `skill-names`, or `find-skill`, but the observed need is deterministic listing and exact-name lookup rather than "show skills in the order they were registered"
- workflow child-session shaping resolves explicit skill names through exact-name lookup and does not consume registry order
- current direct proof of insertion-order semantics lives mainly in `skill-registry` unit tests and task `164` audit text, not in a caller that meaningfully branches on registration sequence

Refined likely direction:

- preserve duplicate-ignore and `:added?` / `:changed?`
- drop insertion order as registry semantics if the remaining audit confirms no real caller dependence
- replace it with canonical name-sorted read surfaces so higher prompt/discovery callers stay deterministic without each re-sorting independently

## 2026-05-22 ambiguity review

Found actionable ambiguities: the task did not define `design-steps.md` despite requesting follow-ups there; "canonical `:name` order" lacks comparator/case/locale precision; the artifacts do not say whether sorted order is a read-projection contract or stored `:skills` / `register-skill` result contract; prompt/display/introspection surfaces are named broadly but raw-vector consumers make the affected surface set unclear; task `164` update scope is underspecified.


## 2026-05-22 ambiguity review follow-up

Found one additional actionable ambiguity: the design allows outcome A (keep registration order if a real dependency exists), but `plan.md` / `steps.md` are written only for the removal path and do not say what implementation, tests, or task `164` update should happen if the audit proves insertion order is required.
