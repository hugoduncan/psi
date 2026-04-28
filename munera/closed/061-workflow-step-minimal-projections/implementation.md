# 061 — Implementation notes

This task is Phase 2 extracted from umbrella task 059.

Key constraints:
- keep projection vocabulary small and declarative
- do not add transcript/message projection here
- do not introduce a transformation DSL

Implemented:
- extended `workflow_file_authoring_resolution.clj` so `:session :input` and `:session :reference` accept `:projection :text`, `:projection :full`, and `:projection {:path [...]}` layered on top of task-060 `{:from ...}` source selection
- preserved task-060 defaults/backward compatibility by keeping omitted `:projection` equivalent to the canonical text view
- compile-time projection validation now rejects unsupported operators, malformed `{:path ...}` values, and unexpected projection keys with clear compiler/load errors
- added focused compiler tests for projection compilation, malformed projection validation, and named prior-step non-adjacent structured extraction
- added focused loader tests proving projected workflow-file authoring loads/compiles and malformed projections surface as load errors
- focused test run green: `clojure -M:test --focus psi.agent-session.workflow-file-compiler-test --focus psi.agent-session.workflow-file-loader-test` → `17 tests, 128 assertions, 0 failures`

Review:
- matches the task design and preserves the existing compile-to-binding-ref architecture
- projection/source separation is clear and backward compatibility is intact
- main gap: no execution-level test yet proves projected bindings flow through materialization/prompt rendering at runtime
- secondary gap: plan item to re-check real workflow examples is not yet evidenced

Code-shaper review:
- shape is good: projection compilation stays local to authoring resolution and preserves the canonical binding-ref contract
- small shaping follow-on: remove or simplify now-unused helper parameters / contracts where possible
- consistency follow-on: tighten error-shaping style if another touch to this area is needed
- main robustness follow-on remains one execution-level proof test

Follow-on review execution:
- added an execution-level workflow test proving projected bindings flow through runtime materialization and prompt rendering
- simplified `source-root` by removing the unused binding-key parameter
- normalized one local source/projection compile error path through a shared `binding-error` helper
- re-checked `.psi/workflows/gh-bug-triage-modular.md`: it still documents the current linear-by-definition-order limitation and is a plausible future consumer of explicit projection/source authoring, but no example change was required for task 061 itself

Terse review note:
- accepted: matches the design, preserves the compile-to-binding-ref architecture, and now has the missing execution-level proof test
- non-blocking follow-on: `workflow_file_authoring_resolution.clj` is becoming multi-concern; split by authoring concern if this surface grows further
- non-blocking follow-on: standardize authoring error-shaping style and consider consolidating repetitive malformed-projection tests

Review feedback execution:
- split authoring concerns into `workflow_file_authoring_session.clj` and `workflow_file_authoring_routing.clj`, leaving `workflow_file_authoring_resolution.clj` as a thin compatibility façade
- standardized local authoring error shaping around shared helpers for invalid/unexpected-key cases
- consolidated representative malformed projection/source validation into a table-driven authoring-session test while keeping compiler-facing validation coverage

Code-shaper terse review note:
- accepted: seams are now clearer, the compile-to-binding-ref architecture remains intact, and validation/proof coverage is strong
- non-blocking follow-on: if `:session` authoring grows further, split `workflow_file_authoring_session.clj` internally by source/projection vs override compilation
- non-blocking follow-on: make authoring error text fully uniform and watch for test duplication drift between authoring-session and compiler-facing suites

Code-shaper feedback execution:
- extracted shared authoring error helpers to `workflow_file_authoring_errors.clj`
- tightened authoring-session internal separation by isolating override compilation behind `compile-session-overrides`
- made validation text more uniform around `invalid-in`-shaped messages and scope-specific wording
- expanded table-driven authoring-session tests to cover both source/projection and override validation
- reduced compiler-facing validation overlap to one representative malformed override proof while keeping integration-level signal
