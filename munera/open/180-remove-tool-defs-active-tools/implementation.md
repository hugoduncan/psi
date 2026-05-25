- 2026-05-25 ψ ambiguity review: found five actionable ambiguities.

  1. **Base tool derivation gap**: The design says consumers should "derive from `:tool-ids` + registry lookup" but also says "Registering base tools in tool-registry" is out of scope. Base tools (`read`, `bash`, `edit`, `write`, `psi-tool`) are assembled in `app_runtime.clj` and live only in the runtime agent `data-atom` — they are not in the tool-registry. If `:tool-defs` is removed from session state, consumers like `prompt_handlers.clj:45`, `session_runtime.clj:38`, `system_prompt.clj`, and `psi_tool.clj:515` have no source for base tool definition maps. The design must either (a) bring base tool registration into scope as a prerequisite, (b) define an alternative derivation source for base tools (e.g. runtime agent data), or (c) clarify that `:tool-defs` removal is blocked until base tools are in the registry.

  2. **No derivation API specified**: Multiple consumers need to resolve `:tool-ids` → tool definition maps on demand. The design says "derive from `:tool-ids`" 10+ times but never specifies what function/API performs this derivation, which component owns it, or what its signature/inputs are. Without a named derivation function, each consumer will independently implement ad-hoc lookup logic — recreating the consistency problem that `tool-authority-fields` was introduced to solve in task 179.

  3. **Missing consumer: `auto_session_name.clj:224`**: The extension passes `:tool-defs []` when creating child sessions via `psi.extension/create-child-session`. This is not listed in the consumer table. It's a real consumer that needs migration (either to `:tool-ids []` or removal if the create-child contract changes).

  4. **Event/contract vs session-state confusion in consumer table**: Several entries conflate `:tool-defs` as a dispatch event field with `:tool-defs` as a session-state field. Specifically: `session_lifecycle.clj:126` destructures `:tool-defs` from the `:session/create-child` dispatch event, `mutations/session.clj:89` passes `:tool-defs` as a dispatch event param, and `workflow_judge.clj:61` passes `:tool-defs []` in a dispatch/creation map. These are event/contract fields flowing through `child_session_contract.clj`, not session-state reads. The migration must change the dispatch event contract and `child_session_contract.clj` schema, not just remove session-state reads. The design's migration path for `session_lifecycle.clj:126` says "Remove — `:tool-ids` suffices" which is incorrect — the handler needs to accept the new field name and pass it through.

  5. **`tool-authority-fields` helper fate unclear**: Task 179 introduced `tool-authority-fields` as the single derivation site returning `{:tool-ids :active-tools :tool-defs}`. The design says "Update `tool-authority-fields` to return only `{:tool-ids ids}`" but doesn't address the callers (`set-active-tools`, `add-tool`, `derive-child-prompt-state`) which currently `merge` the full result into session state. If the helper only returns `{:tool-ids ids}`, those callers no longer persist `:tool-defs` — but they also no longer set `:active-tools`. The design must clarify whether the helper is removed entirely (callers just `assoc :tool-ids` directly) or reshaped, and whether the callers' `merge` pattern changes.

- 2026-05-25 ψ ambiguity follow-up: resolved all five ambiguities in design.md.

  1. **Base tool derivation gap → resolved**: Added "Base tool derivation" subsection to Constraints. Runtime agent `data-atom` (which holds merged base+extension tools after startup) is the derivation source. Base tool registration in tool-registry remains out of scope.

  2. **No derivation API → resolved**: Added "On-demand tool-def derivation API" subsection to Constraints. Specified `psi.tool-registry.defs/resolve-tool-defs` — pure function `(resolve-tool-defs tool-source tool-ids) → [tool-def-map ...]`. Caller provides tool-source (from runtime agent data). Added to In Scope.

  3. **Missing consumer → resolved**: Added `auto_session_name.clj:224` to the dispatch event/contract consumer table with migration path: pass `:tool-ids []` instead of `:tool-defs []`.

  4. **Event/contract vs session-state → resolved**: Split consumer table into two sections: "session-state read sites" and "dispatch event/contract fields". Reclassified `session_lifecycle.clj:126`, `mutations/session.clj:89`, `workflow_judge.clj:61`, `child_session_contract.clj:15` as event/contract consumers. Updated migration paths to describe event contract changes. Added `:session/create-child` event contract and `child_session_contract.clj` schema to acceptance criteria.

  5. **`tool-authority-fields` fate → resolved**: Added "Design decisions" section. Decision: remove `tool-authority-fields` entirely. Callers `assoc :tool-ids` directly instead of `merge`-ing the helper result. Updated In Scope and acceptance criteria accordingly.

- 2026-05-25 ψ inconsistency review: found four actionable inconsistencies.

  1. **`context.clj:134` misclassified as session-state read**: Listed in the "session-state read sites" table but is actually a dispatch event/contract field pass-through. `context.clj` destructures `:tool-defs` from the validated `child_session_contract` request (line 117) and passes it into the `:session/create-child` dispatch event (line 134). It never reads session state. Should be in the "dispatch event/contract fields" table with migration path: "Pass `:tool-ids` instead of `:tool-defs`; upstream callers provide `:tool-ids`". The current migration path ("Derive from `:tool-ids` via `resolve-tool-defs`") is wrong — this function has no access to tool-source.

  2. **`attempts.clj:70` and `statechart_runtime.clj:90` misclassified as session-state reads**: Both are config/contract pass-throughs in the workflow step-config chain: `workflow_step_session_config` → step-config map → `statechart_runtime.clj` → `attempts.clj` → child-session contract. They receive `:tool-defs` from step-config, not from session state, and have no access to tool-source or session state. Migration path "Derive from `:tool-ids` via `resolve-tool-defs`" is wrong — they just forward what they receive. Should be either (a) moved to the dispatch event/contract table with migration path "Pass `:tool-ids` from step-config", or (b) removed from the consumer table entirely if `workflow_step_session_config` continues to output `:tool-defs` in step-config (a local data structure, not session state).

  3. **Step-config output field unspecified**: `workflow_step_session_config/core.clj:196` outputs `:tool-defs` in the step-config map. The design says to "Derive from `:tool-ids` via `resolve-tool-defs`" for this consumer, but doesn't specify what the step-config *outputs* after migration. If it outputs derived `:tool-defs` (maps), then downstream `statechart_runtime.clj:90` and `attempts.clj:70` don't need migration and shouldn't be in the consumer table. If it outputs `:tool-ids` (strings), then the whole downstream chain must change, and the child-session contract boundary must resolve tool-defs. The current design is internally inconsistent because it applies "derive via `resolve-tool-defs`" uniformly to all three, but the chain only needs one derivation point.

  4. **`system_prompt.clj:391-392` is not a consumer**: Listed as a session-state read site, but these lines are parameter documentation in the `build-system-prompt` function's docstring. The function already accepts `:tool-defs` as an input parameter — it doesn't read session state. Only the *callers* (e.g. `prompt_handlers.clj:55`) need to change what they pass. This entry should be removed from the consumer table — the callers are already listed separately.

- 2026-05-25 ψ inconsistency follow-up: resolved all four inconsistencies in design.md.

  1. **`context.clj:134` reclassified → resolved**: Moved from session-state read sites table to dispatch event/contract fields table. Migration path: "Pass `:tool-ids` instead of `:tool-defs`; upstream callers provide `:tool-ids`".

  2. **`attempts.clj:70` and `statechart_runtime.clj:90` removed → resolved**: These are pass-throughs of step-config `:tool-defs` (a local data structure), not session-state reads. Removed from consumer table. Documented in "Removed from consumer table" design decision.

  3. **Step-config output field decided → resolved**: `workflow_step_session_config/core.clj:196` outputs `:tool-defs` (derived full maps) in step-config. After migration, it reads parent `:tool-ids` and derives via `resolve-tool-defs`. Step-config continues to output `:tool-defs` — downstream pass-throughs unchanged. Added "Step-config output" design decision. Updated `workflow_step_session_config/core.clj:166,196` migration path in session-state table.

  4. **`system_prompt.clj:391-392` removed → resolved**: Removed from consumer table — it's a docstring, not a session-state read. Documented in "Removed from consumer table" design decision.

- 2026-05-25 ψ ambiguity review (plan/steps pass): found three actionable ambiguities in the design/plan/steps.

  1. **`app_runtime.clj:364,468` misclassified as session-state read sites**: Both lines are in prompt build-opts construction (`startup-base-prompt-opts` and `adopt-startup-plan-into-session!`). They build a local opts map with `:tool-defs` as a parameter to `build-system-prompt` — they never read `:tool-defs` from session state. `refreshed-tool-defs` is a local variable at both sites. This is the same category as the already-removed `system_prompt.clj:391-392`. The consumer table entry and steps.md migration path "Derive at boundary" are misleading. Should be removed from the consumer table or reclassified as "Removed from consumer table" with rationale.

  2. **`:active-tools` removal step is partially impossible**: Steps.md says "Remove `:tool-defs` and `:active-tools` from schema and `initial-session` defaults." But `:active-tools` does not exist in the session schema (`model.clj`) or in `initial-session` defaults — only `:default-active-tools` exists in config (a separate concern). `:active-tools` is injected at runtime solely by `tool-authority-fields`. The step as written cannot be executed for `:active-tools`. The design/steps should clarify that `:active-tools` removal means: (a) removing it from `tool-authority-fields` (which is being removed entirely), and (b) updating the `prompt_request.clj:279` read site — not removing it from schema/initial-session where it doesn't exist.

  3. **Scheduler session-config contract change is user-facing but not acknowledged**: `psi_tool_scheduler.clj:78` has `:tool-defs` in `session-config-supported-keys` — a whitelist for the psi-tool scheduler create API. The scheduler handler (`scheduler.clj:58`) destructures `:tool-defs` from session-config and normalizes them before dispatching `:session/set-active-tools`. Changing this to `:tool-ids` changes the AI-facing scheduler API contract: callers would provide string tool names instead of full tool-def maps. The design/plan/steps say "Use `:tool-ids`" without acknowledging this is a user-facing API change, or specifying whether the scheduler handler should accept `:tool-ids` (strings) and derive tool-defs internally, or whether existing persisted schedules with `:tool-defs` in their config need migration/compatibility handling.

- 2026-05-25 ψ ambiguity follow-up (plan/steps pass): resolved all three ambiguities.

  1. **`app_runtime.clj:364,468` reclassified → resolved**: Removed from session-state read sites consumer table. Added to "Removed from consumer table" design decision with rationale: prompt build-opts construction sites using local `refreshed-tool-defs` variables, not session-state reads. Removed from steps.md migration list.

  2. **`:active-tools` removal step corrected → resolved**: Updated steps.md to clarify `:active-tools` is not in schema or `initial-session` — it is only injected at runtime by `tool-authority-fields`. Removal surface is: (a) `tool-authority-fields` removal (already scoped), (b) `prompt_request.clj:279` read-site migration. Added explicit `prompt_request.clj:279` migration step.

  3. **Scheduler session-config contract → resolved**: Added "Scheduler session-config" design decision to design.md. Decision: scheduler accepts `:tool-ids` (strings), handler derives tool-defs internally via `resolve-tool-defs`. Persisted schedules with `:tool-defs` will need recreation (acceptable). Added `dispatch_handlers/scheduler.clj:59` to consumer table with migration path. Updated steps.md and plan.md accordingly.

- 2026-05-25 ψ inconsistency review (design/plan/steps cross-consistency): found two actionable inconsistencies.

  1. **`child_session_state.clj` write site at line 98 not addressed**: The consumer table and steps.md address the *read* of `:tool-defs` from parent session state (line 36 fallback) and the `tool-authority-fields` call (line 68, covered by removal decision). But `child-session-base-state*` at line 98 explicitly writes `:tool-defs tool-defs` into the child session data map. After `tool-authority-fields` removal, `derive-child-prompt-state` no longer returns `:tool-defs`, so the destructuring at line 72 yields `nil`, and line 98 writes `:tool-defs nil` — violating AC 1 ("No session state map contains `:tool-defs`"). The consumer table entry and steps.md step 3 for `child_session_state.clj` must explicitly include removing the `:tool-defs` write from the child session data map at line 98.

  2. **`prompt_request.clj:279` duplicated across steps 2 and 4**: Steps.md step 2 lists `prompt_request.clj:279` for deriving `:turn/active-tools` as `(set (:tool-ids session-data))`. Step 4 lists the same line again with the same migration: "derive `(set (:tool-ids session-data))` instead of `(:active-tools session-data)`". This is the same change at the same line appearing in two different steps. Should appear in only one step to avoid confusion about execution order.

- 2026-05-25 ψ inconsistency follow-up (design/plan/steps): resolved both inconsistencies.

  1. **`child_session_state.clj` write site → resolved**: Updated consumer table migration path to explicitly mention removing `:tool-defs tool-defs` from the `child-session-base-state*` data map. Updated steps.md step 3 to include the removal with rationale (would be `nil` after `tool-authority-fields` removal, violating AC 1).

  2. **`prompt_request.clj:279` deduplication → resolved**: Removed the duplicate entry from step 4 ("Remove fields from schema, lifecycle, and helpers"). The migration lives in step 2 ("Migrate session-state read sites") where it belongs — it's a session-state read site, not a schema/lifecycle concern.

- 2026-05-25 ψ plan/steps implementation review: two cleanup items found.

  1. **Stale `:tool-defs` in test fixtures**: 8+ `valid-child-session` mock helpers across workflow test files (`workflow_execution_test_support.clj:76`, `workflow_execution_terminal_contract_test.clj:27`, `workflow_invoke_runtime_test.clj:59`, `workflow_resolvers_test.clj:55`, `workflow_attempts_test.clj:34,66`, `workflow_delegate_example_execution_test.clj:28`, `workflow_runtime/terminal_contract_execution_test.clj:27`, `workflow_runtime/ir_runtime_adoption_test.clj:52`) contain `:tool-defs []` in mock session data. Real session state no longer contains this key. Harmless (extra map key) but misleading for future readers and inconsistent with AC 1's intent.

  2. **Dead code: `agent-core-tools`/`agent-core-tool`**: After `dispatch_effects.clj` was changed to pass full tool maps (`vec (:tool-maps effect)`) instead of projected maps (`agent-core-tools`), the `agent-core-tools` function in `tool_registry/defs.clj` and its re-export in `agent_session/tool_defs.clj` are no longer called from production code. Only one test (`tool_defs_test.clj:11`) references the re-export for identity checking.

  Plan/steps quality: steps accurately describe what was done; all ACs met; all tests green. Plan.md has one stale reference (mentions `tool-authority-fields` as co-located, but it was removed) — cosmetic only.

- 2026-05-25 ψ implementation: all four steps completed and all tests green.

  Implementation commits:
  1. `6ffb9b4d` — introduce `resolve-tool-defs` derivation API with unit tests
  2. `9e4a39ac` — migrate session-state read sites to `resolve-tool-defs`
  3. `06e1450a` — migrate dispatch event/contract fields from `:tool-defs` to `:tool-ids`
  4. `700de860` — remove `:tool-defs` from schema/lifecycle, remove `tool-authority-fields`
  5. `283a6299` — fix test failures from migration

  Key deviations from initial design:
  - **Agent-core tool storage**: removed `agent-core-tools` projection from `dispatch_effects.clj` and replaced `agent-core-tools` with `normalize-tool-defs` in `session_runtime.clj`. Full canonical tool maps (including `:lambda-description`) are now stored in agent-core's data-atom. This is necessary because the agent-core data-atom is the tool-source for `resolve-tool-defs`, and prompt rendering needs `:lambda-description`. The `agent-core-tools` projection was stripping this metadata.
  - **Nil vs empty tool-ids in `attempts.clj`**: changed `(mapv :name (or tool-defs []))` to `(when tool-defs (mapv :name tool-defs))` to preserve nil when the step config has no tools. This allows `child_session_state.clj` to fall back to the parent's `:tool-ids` via `(or tool-ids (:tool-ids parent-sd))`.
  - **Test setup**: child session mutation tests now dispatch `:session/set-active-tools` on the parent session before creating child sessions, so the parent's agent-core has tools available for `resolve-tool-defs`.

- 2026-05-25 ψ test review (task-test-review skill):

  **Overall**: Tests are well-formed, cover all design behaviours, and use real implementations (no mocks/stubs for core logic). All ACs are tested. One minor gap noted.

  1. **Minor gap — scheduler delivery with tool-ids**: `scheduler_handlers_test.clj` creates a `:kind :session` schedule with `:tool-ids ["read"]` and verifies `:tool-count 1` in the summary, but the delivery test that exercises `scheduled-session-config-dispatches` → `resolve-tool-defs` → `:session/set-active-tools` uses a simple config without `:tool-ids`. The composed path works (each piece is individually tested), but there's no single test that exercises the full scheduler-session-delivery-with-tool-ids flow end-to-end.

  **Verdict**: No critical issues. The minor gap is acceptable given the compositional testing strategy — each unit (`resolve-tool-defs`, `set-active-tools` handler, scheduler create/deliver) is well-tested independently.

- 2026-05-25 ψ cleanup: completed both review follow-up items.

  1. **Stale `:tool-defs` in test fixtures → resolved**: Removed `:tool-defs []` from 6 mock session data helpers. Left `:tool-defs []` in `workflow_resolvers_test.clj:55` and `workflow_attempts_test.clj:34,66` — these are step-config inputs to `create-step-attempt-session!` which still accepts `:tool-defs` from step-config by design (the function converts to `:tool-ids` internally at line 70).

  2. **Dead `agent-core-tools` code → resolved**: Removed `agent-core-tool` and `agent-core-tools` from `tool_registry/defs.clj`, their re-exports from `agent_session/tool_defs.clj`, identity tests from `tool_defs_test.clj`, and `agent-core-tool-projection-test` from `defs_test.clj`. All tests green.

- 2026-05-25 ψ test-shaper review: tests well-shaped overall — one minor gap.

  **Overall**: Tests exhibit clarity (single-concern, explicit AAA), signal (behavior-focused assertions on state/effects, not implementation), robustness (deterministic, real impls via atoms), and economy (no redundant tests). Coverage spans: derivation API unit tests, handler mutations, child session state, lifecycle paths, contract validation, workflow step-config, scheduler, and full integration (child_session_mutation_test).

  1. **Minor gap — nil tool-source in `resolve-tool-defs`**: The function handles nil tool-source gracefully (returns `[]`), and this is a real runtime path (agent-ctx not yet initialized → `agent-tool-source-in` returns nil). The test covers empty `[]` but not nil. Adding one assertion makes the nil contract explicit and guards against future regression.

  **Verdict**: No critical issues. One minor defensive-coverage gap worth closing.

- 2026-05-25 ψ code-shaper review (plan/steps): no actionable issues.

  Assessed `resolve-tool-defs`, all 12 call sites, `child_session_state.clj`, `prompt_handlers.clj`, `session_mutations.clj`, `scheduler.clj`, `attempts.clj`, `statechart_runtime.clj`, `workflow_step_session_config/core.clj`, `psi_tool.clj`, `session_lifecycle.clj`, `context.clj`, `prompt_request.clj`, `child_session_contract.clj`, and session-state schema/init.

  **Simplicity**: `resolve-tool-defs` is pure, single-responsibility. Each consumer follows a uniform pattern: acquire tool-source → resolve → use. No unnecessary indirection remains.

  **Consistency**: 12 call sites all use `(tool-defs/resolve-tool-defs tool-source (:tool-ids sd))`. Tool-source acquisition is consistent via `agent-tool-source-in`. The one divergent site (`psi_tool.clj:refresh-live-tool-defs!`) is intentionally different (dev reload rebuilds from scratch) and commented.

  **Robustness**: Nil/empty inputs handled gracefully. Contract boundary at `child_session_contract.clj` enforces `[:vector :string]`. Step-config → contract conversion in `attempts.clj` preserves nil semantics for parent fallback. Schema validates without removed fields.

  **Noted (non-actionable)**: `child_session_state.clj:parent-tool-source` duplicates `agent-tool-source-in` logic — justified by operating on `root-state` snapshot (pure state transformation) rather than `ctx`. The step-config `:tool-defs` → contract `:tool-ids` naming seam is a documented design decision.
