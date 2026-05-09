# 135 — Higher-order workflow references

Implementation notes will record:

- the chosen external shape for workflow references, with current preferred candidate `{:type :workflow-ref :name "..."}`
- the chosen IR representation for higher-order delegate targets, with current preferred candidate `:target` union of static workflow-name string or source-spec-like map
- the exact validation boundaries for malformed references, wrong-type source values, unknown workflow names, and unavailable workflow targets, with current preferred layered split: authored target-shape validation in grammar/compiler, workflow-ref value validation in runtime source resolution, and final availability validation in canonical delegate lookup/enforcement
- whether implementation preserves the preferred semantic failure split of authored-shape failure, runtime-type failure, lookup failure, and availability failure
- confirmation that dynamic `:target` reuses the existing `source-spec` shape exactly, including bare `:from` and optional `:path`/`:projection`
- confirmation that any dynamic `:target` using `:projection` still resolves to a valid workflow-reference value
- confirmation that structured data outputs, rather than yielded free-form text, are the canonical transport for workflow references between steps
- confirmation that workflow references are treated as ordinary structured data values rather than a new global output type system
- confirmation that semantic distinction between lookup failure and availability failure is preserved, even if external error wording is later normalized
- the canonical runtime lookup/enforcement path reused for dynamic delegation
- whether plain strings participate anywhere in the dynamic path and, if so, the exact coercion rule and rationale
- the proof that static delegation behavior remained compatible
- any residual ambiguity or deferred follow-on work, such as collection-level higher-order operators or workflow-generation ideas that were intentionally left out of scope
- whether implementation follows the worked-example guidance for success, runtime-type failure on plain-string dynamic targets, and availability failure on unavailable workflow references
