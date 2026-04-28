# 061 — Steps

- [x] Define `:projection :text`, `:projection :full`, and `:projection {:path [...]}` semantics
- [x] Compile source+projection specs into canonical binding refs
- [x] Reject malformed/unsupported projections while preserving task-060 source-selection behavior
- [x] Add focused tests, including named prior-step non-adjacent source selection with projection
- [x] Run focused workflow tests
- [x] Add one execution-level workflow test proving projected bindings flow through `materialize-step-inputs` / prompt rendering
- [x] Re-check at least one real `.psi/workflows/` example for improved structured extraction authoring
- [x] Simplify any now-unused helper parameters/contracts in `workflow_file_authoring_resolution.clj` if still warranted after the runtime-proof test
- [x] Normalize error-shaping style in projection/source compilation if this area is touched again
- [x] If workflow authoring surface grows further, split `workflow_file_authoring_resolution.clj` by concern (source/projection vs overrides vs routing)
- [x] Standardize authoring error-shaping style across the whole authoring-resolution surface
- [x] Consider consolidating repetitive malformed-projection validation tests with table-driven coverage
- [x] If `:session` authoring grows further, split `workflow_file_authoring_session.clj` internally by source/projection vs override compilation
- [x] Make authoring error text fully uniform across authoring-session validation paths
- [x] Watch for and reduce overlapping test intent between authoring-session and compiler-facing validation suites if duplication grows
