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


## 2026-05-22 ambiguity follow-up execution

Completed all newly added ambiguity follow-up items in `design-steps.md`. The task artifacts now explicitly separate `design-steps.md` as the ambiguity follow-up surface from `steps.md` as the implementation checklist, define canonical skill-name ordering as case-sensitive locale-independent JVM string order, clarify that registry outputs/results and user/model-visible projections must be canonical while arbitrary incoming session vectors are not trusted ordering contracts, enumerate prompt/display/introspection surfaces to audit, define task `164` update scope for both removal and keep-order branches, and add the explicit keep-order branch to `plan.md` and `steps.md`.

## 2026-05-22 inconsistency review

Found one actionable inconsistency: `design.md` keeps outcome C open (registry-layer ordering non-semantic with presentation/prompt layers sorting), but `plan.md`, `steps.md`, and the task `164` update scope only define the canonical name-sorted removal branch or the keep-order branch.

## 2026-05-22 inconsistency follow-up execution

Completed the newly added `design-steps.md` inconsistency follow-up. Outcome C remains viable in `design.md` and now has matching execution guidance: branch C means registry-layer order-insensitive membership/count/exact lookup, with deterministic canonical sorting owned only by higher prompt/display/introspection surfaces. Updated `plan.md`, `steps.md`, and the task `164` update scope guidance to distinguish branch B canonical registry listing, branch C presentation-owned sorting, and the keep-order branch.

## 2026-05-22 ambiguity review

No new actionable ambiguity feedback. Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` audit excerpts, and representative skill registry / resolver / prompt / TUI surfaces; the current artifacts now define the decision branches, follow-up surfaces, ordering contract candidates, affected surfaces, and task `164` update expectations clearly enough for implementation.

## 2026-05-22 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity follow-up steps remain complete, so no design, plan, or implementation checklist changes were needed.

## 2026-05-22 inconsistency review

Found one actionable inconsistency: `design.md` says `design-steps.md` is the actionable surface for ambiguity-review follow-up items only, but this task's prior inconsistency review already used `design-steps.md` for inconsistency follow-up and the current requested review protocol also requires inconsistency follow-ups there.

## 2026-05-22 inconsistency follow-up execution

Completed the newly added `design-steps.md` inconsistency follow-up. Updated `design.md` so `design-steps.md` is the design-review follow-up surface for both ambiguity-review and inconsistency-review items, and clarified that `steps.md` remains reserved for later implementation execution rather than design-review follow-up execution.

## 2026-05-22 ambiguity review

No new actionable ambiguity feedback. Rechecked `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` audit references, and representative registry/resolver/prompt/TUI code paths; the branch choices, canonical ordering candidate, branch C alternative, keep-order fallback, affected surfaces, and task `164` update expectations remain clear enough for implementation.

## 2026-05-22 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preloaded no-feedback ambiguity review. All design follow-up steps remain complete, so no `design.md`, `plan.md`, or `steps.md` changes were needed.

## 2026-05-22 inconsistency review

No new actionable inconsistency feedback. Rechecked `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` audit/update guidance, and representative skill registry/resolver/prompt code paths; the artifacts now consistently distinguish branch B canonical registry listing, branch C presentation-owned sorting, and the keep-order fallback, with `design-steps.md` as the shared design-review follow-up surface.
