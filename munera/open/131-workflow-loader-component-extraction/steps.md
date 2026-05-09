- [x] Review current workflow loading/discovery/authored-definition preparation surfaces and identify the true loader ownership boundary
- [x] Record which current namespaces/files were reviewed as possible loader owners, including an explicit disposition for `workflow-file-loader`, `workflow-file-parser`, `workflow-file-compiler`, `workflow-file-authoring-errors`, `workflow-file-authoring-session`, `workflow-file-authoring-preload`, `workflow-file-authoring-routing`, and `workflow-file-authoring-resolution`
- [x] Choose `workflow-loader` / `psi.workflow-loader.*` unless review proves a narrower or broader owner is more accurate, and record the naming decision plus rejected alternatives
- [x] Decide whether one namespace or a small internal split best fits the extracted ownership surface and record the decision, preferring discovery/parser/compiler/authoring-preparation role clarity without over-splitting
- [x] Record that the extracted component is either one coherent authored-definition loading owner or, if not, why a smaller combined discovery + ingestion extraction is still the best current boundary
- [x] Create the dedicated lower component for workflow loader ownership
- [x] Move the authoritative workflow loading/authored-definition preparation logic with minimal semantic change, including workflow-file authoring compilation helpers when they are part of load-time preparation
- [x] Preserve existing caller-visible loader behavior surfaces and their externally consumed call/output contracts unless a justified replacement is recorded, including `load-workflow-definitions`, `scan-directory`, direct parser/compiler caller contracts, directory precedence semantics, duplicate-resolution behavior, source-path attachment behavior, and load-time error/warning shaping
- [x] Prefer one small canonical lower loader API where possible, centered on `load-workflow-definitions`, and classify remaining public vars as architecturally necessary versus temporarily preserved for caller safety
- [x] If the extraction introduces clearer lower canonical loader entrypoints, record which prior mixed caller surfaces were rewired and why the new lower surface is the better boundary
- [x] Rewire higher workflow entrypoints to the new owner
- [x] Rewire higher adapter surfaces to the new owner
- [x] Rewire affected tests so lower loader behavior proofs point at the new component, moving lower parser/compiler/loader/authoring-helper unit proofs where practical while allowing higher integration proofs to remain with higher orchestration owners
- [x] Verify workflow registry ownership remains limited to definition storage/query concerns and is not recombined with loader ownership
- [x] Verify workflow runtime ownership remains limited to execution/progression concerns and is not recombined with loader ownership
- [x] Verify workflow step session-config and workflow step materialization remain separate lower owners and are not recombined with loader ownership
- [x] Decide whether previous mixed workflow-loading owners disappear entirely or remain only as tiny temporary forwarding seams, and record justification if retained; explicitly decide the fate of `workflow-file-authoring-resolution`, with final state being removal after rewiring
- [x] Remove any temporary forwarding seams before task completion unless a blocking reason is recorded; final state: all forwarding seams removed
- [x] Record how the extracted loader component hands off to workflow registry/runtime consumers and whether the downstream handoff artifact is raw authored definitions, normalized definitions, canonical prepared definitions, or a load-result envelope including metadata/context; prefer canonical prepared definitions plus loader-owned metadata/diagnostics, and record that registration remains outside loader authoritative ownership
- [x] Record whether any mixed load-register-run boundary awkwardness remains
- [x] Record whether the resulting loader -> registry/runtime dependency shape is acceptably tree-like or still preserves graph edges for later cleanup
- [x] Verify workflow behavior remains unchanged
- [x] Record the reviewed current surfaces, final boundary, name, loader responsibility shape, public surface, responsibility inventory, registry/runtime boundary status including the downstream handoff artifact, transitional namespace status, and any residual dependency debt in `implementation.md`

Verification:
- [x] `clojure -M:test --focus psi.workflow-loader.core-test --focus psi.workflow-loader.parser-test --focus psi.workflow-loader.compiler-test --focus psi.workflow-loader.compiler-target-authoring-test --focus psi.workflow-loader.authoring-session-test --focus extensions.workflow-loader-test --focus extensions.workflow-loader-delegate-test --focus psi.agent-session.workflow-loader-async-path-test --focus psi.agent-session.workflow-loader-tui-repro-test --focus psi.agent-session.workflow-migration-validation-test`
- [x] `clojure -M:lint --lint components/workflow-loader components/agent-session extensions/workflow-loader deps.edn tests.edn`

Residual debt:
- [x] No retained forwarding seams remain

Follow-up shaping:
- [x] Shape `psi.workflow-loader.core/load-workflow-definitions` by extracting tiny pure helpers for parse-error shaping, validation-error shaping, and final result assembly so the top-level function carries less mixed assembly flow
- [x] Review `psi.workflow-loader.authoring-session` and `psi.workflow-loader.authoring-preload` for a smallest shared lower helper around source-map validation / prior-step resolution / nested error wrapping, but only extract it if the result is simpler than the current local duplication
- [x] Audit the `psi.workflow-loader.*` public surface against real non-test consumers and reduce helper publics where possible without weakening lower proofs or reintroducing mixed ownership
  - kept the current remaining public vars because they are exercised either by real higher consumers or by intentional lower proofs; no further narrowing was justified within this task
