2026-05-07

Implemented workflow authored-definition loading extraction into a dedicated lower component.

## Current surface review

Reviewed as possible loader owners:
- moved to the extracted loader component:
  - `psi.agent-session.workflow-file-loader`
  - `psi.agent-session.workflow-file-parser`
  - `psi.agent-session.workflow-file-compiler`
  - `psi.agent-session.workflow-file-authoring-errors`
  - `psi.agent-session.workflow-file-authoring-session`
  - `psi.agent-session.workflow-file-authoring-preload`
  - `psi.agent-session.workflow-file-authoring-routing`
- removed after direct rewiring:
  - `psi.agent-session.workflow-file-authoring-resolution`

Review conclusion:
- the authoritative loader owner is one coherent authored-definition loading boundary spanning discovery, parsing, compilation, load-result shaping, and authoring-time session/preload/routing compilation helpers used during load-time preparation
- none of the reviewed `workflow-file-*` namespaces belonged in registry or runtime ownership
- `workflow-file-authoring-resolution` was already only a façade over narrower helpers and was not the true authoritative owner

## Final component and namespace name

Chosen component name:
- `workflow-loader`

Chosen namespace family:
- `psi.workflow-loader.*`

Chosen internal split:
- `psi.workflow-loader.core`
- `psi.workflow-loader.parser`
- `psi.workflow-loader.compiler`
- `psi.workflow-loader.authoring-errors`
- `psi.workflow-loader.authoring-session`
- `psi.workflow-loader.authoring-preload`
- `psi.workflow-loader.authoring-routing`

Naming decision:
- kept `workflow-loader` rather than broader or narrower alternatives because the final owned surface remains specifically about authored-definition discovery/loading/preparation before registry or runtime use
- rejected `workflow-authoring-loader` because the component does not own authoring semantics broadly, only the load-time preparation portion
- rejected `workflow-definition-loader` because the existing repo vocabulary already centers the loading boundary around workflow files and loader behavior rather than a wider definition lifecycle owner

## Loader responsibility shape

The extracted component is one coherent authored-definition loading owner.

It owns:
- workflow file discovery/loading
- file parsing
- target-authored workflow-file compilation
- load-time error and warning shaping
- workflow-file authoring helper compilation used during loading:
  - session input binding compilation
  - session override compilation
  - session preload compilation
  - routing target resolution helpers
- workflow-file metadata attachment during compile/load
- downstream handoff of canonical prepared definitions to higher registry consumers

It does not own:
- workflow definition registration/removal in canonical state
- workflow registry storage/query semantics
- workflow runtime execution/progression/statechart semantics
- workflow judge ownership
- workflow step session-config runtime ownership
- workflow step materialization runtime ownership

## Public surface

Canonical lower public API:
- `psi.workflow-loader.core/load-workflow-definitions`

Additional intentionally public lower APIs:
- `psi.workflow-loader.core/global-workflow-dirs`
- `psi.workflow-loader.core/project-workflow-dir`
- `psi.workflow-loader.core/scan-directory`
- `psi.workflow-loader.core/scan-all-directories`
- `psi.workflow-loader.parser/parse-workflow-file`
- `psi.workflow-loader.compiler/compile-workflow-file`
- `psi.workflow-loader.compiler/compile-workflow-files`
- `psi.workflow-loader.compiler/validate-step-references`
- `psi.workflow-loader.compiler/validate-no-name-collisions`
- `psi.workflow-loader.compiler/validate-judge-routing`
- `psi.workflow-loader.authoring-session/source+projection->binding`
- `psi.workflow-loader.authoring-session/compile-step-input-bindings`
- `psi.workflow-loader.authoring-session/compile-step-session-overrides`
- `psi.workflow-loader.authoring-session/step-source-reference-map`
- `psi.workflow-loader.authoring-preload/compile-step-session-preload`
- `psi.workflow-loader.authoring-routing/routing-target->step-id-map`
- `psi.workflow-loader.authoring-routing/resolve-routing-table`

Why they remain public:
- direct lower proofs intentionally exercise parser/compiler/loader/authoring helper seams
- extension and integration callers intentionally use the canonical loader entrypoint and, in a few cases, parser/compiler seams for proof setup
- these are intentional lower APIs, not accidental leftovers

## Contract preservation

Preserved behavior/call/output contracts:
- `load-workflow-definitions` result shape
- `scan-directory` result shape
- parser and compiler direct caller contracts used by tests and extension proofs
- directory precedence semantics
- duplicate-name resolution semantics
- `:source-path` attachment behavior
- load-time error and warning shaping

No caller-visible contract redesign was introduced.

## Rewiring performed

Higher workflow entrypoints/adapters rewired to the new owner:
- `extensions.workflow-loader` now depends on `psi.workflow-loader.core`
- `extensions.workflow-loader-test` now depends on `psi.workflow-loader.core`, `psi.workflow-loader.parser`, and `psi.workflow-loader.compiler`
- `extensions.workflow-loader-delegate-test` now redefines `psi.workflow-loader.core/load-workflow-definitions`
- `psi.agent-session.workflow-loader-async-path-test`
- `psi.agent-session.workflow-loader-tui-repro-test`
- `psi.agent-session.workflow-migration-validation-test`

Lower proofs moved to the new component:
- `psi.workflow-loader.core-test`
- `psi.workflow-loader.parser-test`
- `psi.workflow-loader.compiler-test`
- `psi.workflow-loader.compiler-target-authoring-test`
- `psi.workflow-loader.authoring-session-test`

Build/test wiring added:
- new component dependency entry in root `deps.edn`
- new component dependency entry in `components/agent-session/deps.edn`
- new component dependency entry in `extensions/workflow-loader/deps.edn`
- new source/test paths in `tests.edn`
- new source/test paths in test aliases in root `deps.edn`

## Registry/runtime boundary status

Downstream handoff artifact:
- canonical prepared workflow definitions keyed by workflow name, plus loader-owned `:errors` and `:warnings`

Boundary shape:
- loader returns canonical prepared definitions and diagnostics
- `extensions.workflow-loader` remains the higher orchestration surface that immediately consumes those loaded definitions and delegates registration/removal through canonical workflow mutations
- registration remains outside loader authoritative ownership

Mixed boundary status:
- no new load-and-register ownership was introduced into the loader component
- the loader still feeds a higher orchestrator that performs registry mutations immediately, but that is an acceptable tree-like boundary: loader prepares definitions, higher orchestration registers them

## Ownership separation verification

Registry ownership remains separate:
- compiler still depends only on `psi.workflow-registry.definition` validation/contracts, not registry state ownership
- definition registration/removal remains in higher orchestration via workflow mutations

Runtime ownership remains separate:
- no runtime execution/progression code moved into the loader component
- runtime consumers remain outside and consume already loaded/registered definitions

Adjacent extracted lower owners remain separate:
- workflow step session-config ownership was not recombined with loader ownership
- workflow step materialization ownership was not recombined with loader ownership

## Transitional namespace status

Final status:
- removed old mixed `psi.agent-session.workflow-file-*` namespaces entirely after direct rewiring
- `psi.agent-session.workflow-file-authoring-resolution` does not remain

Specific `workflow-file-authoring-resolution` decision:
- removed after rewiring
- it is no longer present as either an authoritative owner or compatibility façade
- canonical owner is the `psi.workflow-loader.authoring-*` family

## Residual dependency status

Residual direct dependencies on old mixed owners do not remain.

No reviewed higher or lower caller continues to depend on the removed `psi.agent-session.workflow-file-*` namespaces.

Residual debt:
- none for forwarding seam cleanup; that cleanup is complete

## Verification

Focused tests green:
- `clojure -M:test --focus psi.workflow-loader.core-test --focus psi.workflow-loader.parser-test --focus psi.workflow-loader.compiler-test --focus psi.workflow-loader.compiler-target-authoring-test --focus psi.workflow-loader.authoring-session-test --focus extensions.workflow-loader-test --focus extensions.workflow-loader-delegate-test --focus psi.agent-session.workflow-loader-async-path-test --focus psi.agent-session.workflow-loader-tui-repro-test --focus psi.agent-session.workflow-migration-validation-test`
- result: `55 tests, 365 assertions, 0 failures`

Lint green:
- `clojure -M:lint --lint components/workflow-loader components/agent-session extensions/workflow-loader deps.edn tests.edn`
- result: `0 errors, 0 warnings`

## Final assessment

Acceptance is met for authoritative ownership:
- a dedicated lower workflow loader component now exists at `components/workflow-loader/`
- authoritative loader logic now lives under `psi.workflow-loader.*`
- higher workflow entrypoints depend downward on that component
- registry/runtime/step-materialization/step-session-config ownership remains separate
- behavior contracts were preserved

Explicit residual debt:
- none for forwarding seams; the old `psi.agent-session.workflow-file-*` namespaces were removed after direct rewiring
