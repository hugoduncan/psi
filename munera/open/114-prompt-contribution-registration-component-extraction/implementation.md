2026-05-08

Implemented prompt contribution registration extraction into new lower component `components/prompt-registry/`.

What landed
- added new pure owner namespace `psi.prompt-registry.contributions`
- added thin compatibility wrapper namespace `psi.prompt-registry`
- moved canonical prompt-contribution collection semantics out of `agent-session` prompt handlers and into the extracted component:
  - identity normalization
  - canonical stored-shape normalization
  - patch merge semantics
  - register/update/unregister operations
  - contribution count/find/all helpers
  - canonical contribution ordering helper
- delegated `:session/register-prompt-contribution`, `:session/update-prompt-contribution`, and `:session/unregister-prompt-contribution` handlers to the extracted component while keeping effective-prompt rebuild and runtime prompt-update effects in `agent-session`
- made `psi.session-state.state/sorted-prompt-contributions` delegate to the extracted ordering owner
- wired root `deps.edn`, `tests.edn`, `components/agent-session/deps.edn`, and `components/session-state/deps.edn` to include `prompt-registry`
- added focused component-local tests in `components/prompt-registry/test/psi/prompt_registry/contributions_test.clj`

Settled behavior decisions
- identity remains first-cut canonical `ext-path` + `id`
- current live behavior does not reject missing identity fields; after coercion, nil values normalize to empty string because `str` on nil yields `""`; blank strings are still accepted
- this task preserves that loose contract rather than tightening to structured rejection, because the task goal was behavior-preserving extraction first
- section normalization preserves existing `str` behavior, so keyword sections become strings like `":capabilities"`
- content normalization preserves existing `str` behavior
- patchable fields remain exactly `:section`, `:content`, `:priority`, and `:enabled`
- identity fields and `:created-at` remain non-patchable
- unknown patch keys remain ignored
- register replacement preserves existing timestamp-reset semantics: replacement gets fresh `:created-at` and `:updated-at`
- update hit preserves `:created-at` and advances `:updated-at`
- ordering ownership now sits explicitly in `prompt-registry`; prompt composition/filtering still remains in `prompt-assets.system-prompt`

Count/reporting note
- live pre-extraction register handler returned a pre-update count because it counted the old session state rather than the post-operation vector
- extraction intentionally fixed this to explicit post-operation count returned by the registry result contract
- update miss/hit and unregister miss/hit continue to report the current post-operation-or-unchanged count explicitly from the lower component
- this is the only intentional behavior correction made during extraction

Verification
- `clojure -M:test --focus psi.prompt-registry.contributions-test` → green (`7 tests, 67 assertions, 0 failures`)
- `clojure -M:test --focus psi.agent-session.model-dispatch-test` → green (`8 tests, 96 assertions, 0 failures`)
- `clj-kondo --lint components/prompt-registry/src components/prompt-registry/test components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj components/session-state/src/psi/session_state/state.clj` → green (`0 errors, 0 warnings`)

Boundary after extraction
- `prompt-registry` owns pure prompt-contribution registration/query/order semantics
- `prompt-assets.system-prompt` still owns contribution filtering/application semantics during final prompt composition
- `agent-session` still owns dispatch entrypoints, session state updates, effective prompt rebuild, and runtime prompt-update effects
- prompt template registration remains outside this task

Review note — code-shaper
- implementation quality is acceptable and matches the intended extraction boundary
- no blocking design/code issues were found in review
- follow-up shaping items were identified and are now implemented:
  - removed the local `atom` from `update-contribution` and now return updated contribution detail through a purely functional reduction/result shape
  - removed unnecessary `sort-contributions` calls when only computing counts in `update-contribution`; count-only paths now use `contribution-count`
  - extracted a shared prompt-contribution identity match helper to reduce drift risk across `find`, `register`, `update`, and `unregister`
  - tightened the `normalize-identity` docstring so it states the actual first-cut contract directly: string coercion with nil accepted and normalized to `""`
- focused review verification after implementation also stayed green:
  - `clojure -M:test --focus psi.agent-session.prompt-lifecycle-test` → green (`17 tests, 82 assertions, 0 failures`)
  - `clojure -M:test --focus psi.agent-session.query-graph-tools-test` → green (`4 tests, 23 assertions, 0 failures`)
