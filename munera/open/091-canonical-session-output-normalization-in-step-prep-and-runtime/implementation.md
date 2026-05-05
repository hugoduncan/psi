2026-05-05
- Task created from implementation review follow-up spanning `077` and `089`.
- Rationale: session output normalization is mostly converged in docs/IR/compiler, but runtime step preparation and statechart execution still show split responsibility and duplication risk.
- This task is intended as a narrow shaping/convergence slice, not a redesign of the broader workflow runtime.
- Design review follow-up:
  - clarified that the task should prefer an existing authoritative interpretation layer over introducing a new peer helper/seam
  - clarified that accidental compat-era duplication may be removed when the resulting canonical behavior is made explicit in focused tests
  - clarified that `workflow_source_resolution.clj` is in play only if it is already the clearest home for the authoritative interpretation, not as a reason to broaden the slice

2026-05-05 implementation
- Centralized accepted-result path normalization in `components/agent-session/src/psi/agent_session/workflow_source_resolution.clj` by teaching `resolve-binding-ref` to route `:step-output` `:outputs` reads through canonical IR semantics.
- Added `resolve-accepted-result-path` so compat-shaped binding paths like `[step-id :outputs :final-llm-reply]` and `[step-id :outputs :text]` no longer bypass canonical output/yield interpretation.
- Canonical rule implemented:
  - declared output keys resolve via `workflow-ir/step-output-value`
  - legacy `:outputs :text` reads normalize through `workflow-ir/step-yield-field-value` when the step yields `:text`
  - non-output accepted-result paths still read directly from the stored envelope
- This keeps runtime callers delegating downward to `workflow_source_resolution` instead of re-stating output normalization in step prep.
- Added focused proof in `workflow_source_resolution_test.clj` that both `:outputs :final-llm-reply` and compat `:outputs :text` resolve to the same canonical session text for a canonical session step.
- Existing `workflow_step_prep_test.clj` and `workflow_ir_runtime_adoption_test.clj` continue proving canonical `:yield :text` / `:output :final-llm-reply` behavior through runtime materialization.
- Verification run:
  - `clojure -M:test --focus psi.agent-session.workflow-source-resolution-test --focus psi.agent-session.workflow-step-prep-test --focus psi.agent-session.workflow-ir-runtime-adoption-test` → green (`10 tests, 20 assertions, 0 failures`)
  - `clojure -M:test --focus psi.agent-session.workflow-statechart-runtime-test --focus psi.agent-session.workflow-execution-test` → green (`31 tests, 127 assertions, 0 failures`)

2026-05-06 review
- Actionable: task acceptance/steps claim focused proof for canonical session contributions vs compat preload interaction, but current coverage proves the two paths separately rather than in one mixed scenario with explicit precedence/combination semantics.
