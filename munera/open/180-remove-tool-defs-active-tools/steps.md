# 180 — Steps

- [x] **Introduce `resolve-tool-defs`**
  - Add `psi.tool-registry.defs/resolve-tool-defs` — `(resolve-tool-defs tool-source tool-ids) → [tool-def-map ...]`
  - Filters tool-source by `:name` membership in tool-ids, preserves tool-ids ordering, returns `[]` for empty input.
  - Unit test: nominal, empty tool-ids, unknown ids filtered out, ordering preserved.
  - Committed: `6ffb9b4d`

- [x] **Migrate session-state read sites**
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
  - Committed: `9e4a39ac`

- [x] **Migrate dispatch event/contract fields**
  - `child_session_contract.clj:15` — change schema: `[:tool-defs {:optional true} ...]` → `[:tool-ids {:optional true} [:maybe [:vector :string]]]`.
  - `context.clj:134` — destructure and pass `:tool-ids` instead of `:tool-defs`.
  - `session_lifecycle.clj:126` — accept `:tool-ids` from event; pass to child state builder.
  - `mutations/session.clj:89` — pass `:tool-ids` instead of `:tool-defs` in dispatch event.
  - `workflow_judge.clj:61` — pass `:tool-ids []` instead of `:tool-defs []`.
  - `auto_session_name.clj:224` — pass `:tool-ids []` instead of `:tool-defs []`.
  - `child_session_state.clj` — accept `:tool-ids` from parent/event; derive resolved tool-defs via `resolve-tool-defs` for child state. Remove `:tool-defs tool-defs` from the `child-session-base-state*` data map construction (line 98) — after `tool-authority-fields` removal, this key would be `nil`, violating AC 1.
  - Committed: `06e1450a`

- [x] **Remove fields from schema, lifecycle, and helpers**
  - `session_state/model.clj` — remove `:tool-defs` from schema and `initial-session` defaults.
  - `session_state/init.clj` — remove `:tool-defs` from lifecycle `select-keys` (new/resume/fork).
  - `tool_registry/defs.clj` — remove `tool-authority-fields` entirely.
  - `session_mutations.clj` `:session/set-active-tools` handler — replace `(tool-defs/tool-authority-fields ...)` + `merge` with direct `(assoc % :tool-ids (mapv :name normalized))`.
  - `:session/add-tool` handler — same: direct `assoc :tool-ids`.
  - Update/remove tests that assert on `:tool-defs`/`:active-tools` presence in session state.
  - Committed: `700de860`

- [x] **Fix test failures from migration**
  - `dispatch_effects.clj` — store full canonical tool maps in agent-core (not agent-core-projected) so `:lambda-description` is preserved for prompt rendering.
  - `session_runtime.clj` — use `normalize-tool-defs` (preserves `:label` for agent-core schema) instead of `agent-core-tools` projection.
  - `attempts.clj` — preserve nil tool-ids when step config has no tools, so child session inherits parent tool-ids via fallback.
  - Child session mutation tests — dispatch `:session/set-active-tools` on parent before creating child sessions.
  - Committed: `283a6299`
  - Full `bb test` green.
