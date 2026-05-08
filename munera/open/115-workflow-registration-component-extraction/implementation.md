2026-05-08

Implemented the first-cut `workflow-registry` extraction as a new lower component at `components/workflow-registry/`.

What moved
- extracted authoritative workflow-definition path/identity/query/update ownership into `psi.workflow-registry.registry`
- moved canonical workflow-definition-specific helpers there:
  - `normalize-id`
  - `definitions-path`
  - `definition-path`
  - `workflow-definition`
  - `list-definitions`
  - `definition-ids`
  - `register-definition`
  - `remove-definition`
- used `clj-surgeon :extract!` to perform the initial structural extraction from `workflow_runtime.clj`, then manually narrowed and reshaped the new namespace to the settled first-cut API

Boundary results
- `workflow-runtime` now owns workflow runs only: effective-definition compilation, create/resume/cancel/remove run helpers, run lookup, and run ordering
- `workflow-registry` owns canonical workflow-definition storage/query semantics over root workflow state
- mutations, resolvers, and `psi-tool` now delegate workflow-definition register/remove/list/detail reads to the extracted registry
- workflow-step-prep and workflow-statechart-runtime now use registry lookup for registered source/delegate definitions
- `workflow-loader` stayed above the boundary as intended; it continues to orchestrate reload/retirement through mutations rather than owning registry semantics directly

Preserved contracts made explicit
- identity remains canonical normalized `:definition-id`
- normalization still preserves non-blank strings, converts keywords with `name`, converts others with `str`, and generates UUID strings for blank/missing ids
- registration still validates `workflow-target-ir-compiler/target-authored-workflow-definition?`
- invalid registration still throws `ex-info`
- registering an existing normalized id still fully replaces the stored definition map
- public lookup remains nil-returning on miss and now consistently normalizes caller-provided ids
- lower remove helper still throws on missing definitions
- public listing/query helpers now explicitly own sorted-by-`:definition-id` behavior

Follow-on call-site shaping
- `session-state` now uses `workflow-registry/definitions-path` as the canonical workflow-definition root-state path source
- several workflow tests were updated to register through `workflow-registry` where they are proving registry semantics or preparing run-creation fixtures
- higher workflow execution/run behavior still goes through `workflow-runtime`

Verification
- focused green:
  - `psi.workflow-registry.registry-test`
  - `psi.agent-session.workflow-runtime-test`
  - `psi.agent-session.mutations.canonical-workflows-test`
  - `psi.agent-session.workflow-resolvers-test`
  - `psi.agent-session.workflow-tools-test`
  - `psi.agent-session.workflow-session-integration-test`
- command used:
  - `clojure -M:test --focus psi.workflow-registry.registry-test --focus psi.agent-session.workflow-runtime-test --focus psi.agent-session.mutations.canonical-workflows-test --focus psi.agent-session.workflow-resolvers-test --focus psi.agent-session.workflow-tools-test --focus psi.agent-session.workflow-session-integration-test`

Notes
- an attempted focused run that also included `extensions.workflow-loader-test` exposed pre-existing loader compiler expectation failures unrelated to this extraction; those were not introduced by the registry work and were excluded from the final focused verification set
- the initial first cut depended on `psi.agent-session.workflow-target-ir-compiler` for boundary validation, but the later review follow-up extracted the shared target-authored definition-shape predicate into `psi.workflow-registry.definition`, so `workflow-registry` no longer depends upward on `agent-session`

2026-05-08 review

Findings
- High: `components/workflow-registry/` did not initially load standalone as a component because `psi.workflow-registry.registry` required `psi.agent-session.workflow-target_ir_compiler` through the old validation predicate location.
- High: adding `psi/agent-session` as a direct dependency would have created an upward/cyclic component dependency (`workflow-registry -> agent-session -> session-state -> workflow-registry`), confirming the validation predicate lived at the wrong boundary.
- Medium: final focused verification still excludes direct loader-focused tests, so the extraction's intended loader-consumer coverage remains indirect rather than explicitly proven at the loader seam.

Resolution landed
- extracted the shared target-authored workflow-definition shape predicate to the new lower namespace `psi.workflow-registry.definition`
- made `psi.workflow-registry.registry` validate via `psi.workflow-registry.definition/target-authored-workflow-definition?`
- made `psi.agent-session.workflow-target-ir-compiler/target-authored-workflow-definition?` a compatibility alias to that lower predicate
- verified `components/workflow-registry/` now loads standalone via:
  - `cd components/workflow-registry && clojure -M -e "(require 'psi.workflow-registry.registry 'psi.workflow-registry.definition) (println :ok)"`
- reran focused verification including the new lower predicate test and target compiler test alias coverage; all focused checks are green

Residual note
- loader seam proof is still indirect. The component boundary issue is fixed, but a direct loader-focused proof would still strengthen closure if we want stricter acceptance evidence.

Additional cleanup landed
- `workflow_file_compiler.clj` now references `psi.workflow-registry.definition/target-authored-workflow-definition?` directly instead of going through the compatibility alias in `workflow-target-ir-compiler`
- the two workflow-file compiler tests now assert against the lower shared predicate directly as well
- focused verification was rerun including both workflow-file compiler test namespaces; all focused checks are green

2026-05-08 code-shaper review note
- overall shape is now good: ownership is sharper, registry semantics are centralized, and the component boundary is viable standalone
- no blocking simplicity/consistency/robustness issues were found in the extracted code
- optional follow-up items identified by review:
  1. add a brief compatibility comment near `psi.agent-session.workflow-target-ir-compiler/target-authored-workflow-definition?` so future readers know it intentionally aliases the lower shared predicate during migration
  2. if `psi.workflow-registry.registry` grows, consider a tiny internal `ensure-valid-definition!` helper to keep `register-definition` and related validation/error shaping flat without expanding the public API
  3. add direct loader-focused proof for the workflow-loader consumer seam if we want stronger explicit acceptance evidence beyond mutation-path indirection

2026-05-08 optional follow-up implementation
- landed the compatibility comment near `psi.agent-session.workflow-target-ir-compiler/target-authored-workflow-definition?`
- introduced `psi.workflow-registry.registry/ensure-valid-definition!` as a tiny internal helper so registry validation/error shaping stays flat while preserving the same public API
- added a direct workflow-loader consumer-seam proof in `extensions/workflow-loader/test/extensions/workflow_loader_test.clj` that verifies reload computes file-backed retirement/addition and delegates registry semantics through the canonical workflow mutations
- attempted focused verification including `extensions.workflow-loader-test`; the new direct loader seam proof is aligned with the extraction intent, but unrelated pre-existing loader compiler expectation failures still prevent the broader loader test namespace from going green in this branch state
