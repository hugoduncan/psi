# 180 — Steps

- [ ] **Introduce `resolve-tool-defs`**
  - Add `psi.tool-registry.defs/resolve-tool-defs` — `(resolve-tool-defs tool-source tool-ids) → [tool-def-map ...]`
  - Filters tool-source by `:name` membership in tool-ids, preserves tool-ids ordering, returns `[]` for empty input.
  - Unit test: nominal, empty tool-ids, unknown ids filtered out, ordering preserved.

- [ ] **Migrate session-state read sites**
  - `prompt_request.clj:279` — derive `:turn/active-tools` as `(set (:tool-ids session-data))`.
  - `prompt_handlers.clj:45-55` — derive tool-defs via `resolve-tool-defs` from `:tool-ids` + tool-source from runtime agent data.
  - `session_mutations.clj:512` (`:session/add-tool`) — derive current defs from `:tool-ids` via `resolve-tool-defs`; append new tool; persist only `:tool-ids`.
  - `prompt_request.clj:284` — derive filtered tool-defs from `:tool-ids` via `resolve-tool-defs`.
  - `psi_tool.clj:515` — derive builtins from `:tool-ids` via `resolve-tool-defs`.
  - `psi_tool_scheduler.clj:78` — replace `:tool-defs` with `:tool-ids` in `session-config-supported-keys`.
  - `dispatch_handlers/scheduler.clj:59` — accept `:tool-ids` (strings); derive tool-defs via `resolve-tool-defs` before dispatching `:session/set-active-tools` with `:tool-maps`.
  - `scheduler_runtime.clj:44` — use `(count (:tool-ids session-config))`.
  - `session_runtime.clj:38` — derive tool list from `:tool-ids` via `resolve-tool-defs`.
  - `workflow_step_session_config/core.clj:166,196` — read parent `:tool-ids`; derive tool-defs via `resolve-tool-defs`; continue to output `:tool-defs` in step-config map.
  - All existing tests must pass after each sub-migration.

- [ ] **Migrate dispatch event/contract fields**
  - `child_session_contract.clj:15` — change schema: `[:tool-defs {:optional true} ...]` → `[:tool-ids {:optional true} [:maybe [:vector :string]]]`.
  - `context.clj:134` — destructure and pass `:tool-ids` instead of `:tool-defs`.
  - `session_lifecycle.clj:126` — accept `:tool-ids` from event; pass to child state builder.
  - `mutations/session.clj:89` — pass `:tool-ids` instead of `:tool-defs` in dispatch event.
  - `workflow_judge.clj:61` — pass `:tool-ids []` instead of `:tool-defs []`.
  - `auto_session_name.clj:224` — pass `:tool-ids []` instead of `:tool-defs []`.
  - `child_session_state.clj` — accept `:tool-ids` from parent/event; derive resolved tool-defs via `resolve-tool-defs` for child state.
  - All existing tests must pass.

- [ ] **Remove fields from schema, lifecycle, and helpers**
  - `session_state/model.clj` — remove `:tool-defs` from schema and `initial-session` defaults. (`:active-tools` is not in schema or `initial-session` — it is only injected at runtime by `tool-authority-fields`, removed below.)
  - `session_state/init.clj` — remove `:tool-defs` from lifecycle `select-keys` (new/resume/fork).
  - `tool_registry/defs.clj` — remove `tool-authority-fields` entirely (this removes the runtime injection of both `:active-tools` and `:tool-defs`).
  - `prompt_request.clj:279` — migrate `:active-tools` read site: derive `(set (:tool-ids session-data))` instead of `(:active-tools session-data)`.
  - `session_mutations.clj` `:session/set-active-tools` handler — replace `(tool-defs/tool-authority-fields ...)` + `merge` with direct `(assoc % :tool-ids (mapv :name normalized))`.
  - `:session/add-tool` handler — same: direct `assoc :tool-ids`.
  - Update/remove tests that assert on `:tool-defs`/`:active-tools` presence in session state.
  - Final full test run — all tests green.
