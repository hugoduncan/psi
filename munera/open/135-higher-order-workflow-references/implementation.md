# 135 — Higher-order workflow references

Implemented notes:

- Chosen external workflow-reference shape: `{:type :workflow-ref :name "..."}` in `psi.workflow-runtime.model/workflow-ref-schema`.
- Chosen IR target shape: `:delegate {:target (string | source-spec) ...}` via `psi.workflow-runtime.ir/delegate-target-schema`.
- Dynamic `:target` reuses canonical `source-spec` exactly, including `:from` plus optional `:path` or `:projection`; no parallel target mini-language was added.
- Grammar/compiler validation boundary:
  - `psi.workflow-runtime.target-ir-compiler/compile-delegate-target` rejects authored `:target` values that are neither workflow-name strings nor source-spec maps.
- Runtime workflow-reference validation boundary:
  - `psi.workflow-step-materialization.source-resolution/resolve-workflow-ref-source-spec` resolves dynamic targets and requires the resolved value to satisfy `workflow-ref-schema`.
- Canonical lookup boundary:
  - `psi.workflow-runtime.statechart-runtime.delegate/resolve-delegate-target-definition` resolves the final workflow name and uses the existing canonical registry lookup path.
- Plain strings remain valid only as static authored `:target` values.
- Plain strings do not participate in the dynamic path; resolved dynamic values must be explicit workflow refs. No coercion rule was added.
- Structured data outputs are the canonical transport for workflow refs between steps; no yielded-text workflow-ref path was introduced.
- Workflow refs are treated as ordinary structured data values, not a new output type system.
- Static delegation behavior remained compatible; tests were updated to assert the added `:resolved-target` diagnostic breadcrumb.
- Implemented proof covers:
  - IR schema support for dynamic delegate targets
  - compiler acceptance of dynamic target source-specs
  - compiler rejection of malformed authored target maps
  - runtime rejection when dynamic target resolves to plain string data
  - runtime success when dynamic target resolves to explicit workflow ref
  - end-to-end higher-order choose-then-delegate execution
- Availability-vs-lookup semantic split is only partially implemented in this slice:
  - explicit lookup failure exists when the referenced workflow is not registered at delegation time
  - no distinct session-availability gating path was found in the current canonical workflow runtime, so no new availability-only branch was invented here
- Deferred follow-on:
  - add explicit workflow availability/capability gating for dynamic/static delegation if/when the canonical runtime grows a distinct available-vs-known workflow model
  - consider a dedicated lower namespace for workflow-reference helpers if the concept broadens beyond delegate targeting
