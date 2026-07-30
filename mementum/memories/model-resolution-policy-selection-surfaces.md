🔁 A change to runtime model-resolution policy (e.g. marking an id unsupported for a given auth context) must be enforced coherently across EVERY model-selection surface, or the policy leaks through whichever surface you missed. Task 245 needed ~10 implementation-review passes because each surface was a separate leak.

The full surface set to update together:
1. `/model provider id` command (agent-session `commands.clj`)
2. RPC `set_model` op (`ops.clj`) — rejects with `request/unsupported-model` error frame
3. RPC picker `frontend_action_result` select-model (`command_pickers.clj`) — `unsupported_model` command-result (distinct wire shape from set_model)
4. TUI picker `handle-action-result :select-model` (`tui_frontend_actions.clj`)
5. `cycle_model` forward AND backward (`session_settings.clj`) — filter unsupported/unresolvable scoped candidates
6. Turn-runtime preflight for persisted/startup-selected models (`core.clj`) — shape a preflight assistant error before provider execution

`model_registry.clj` is the policy join point between user-visible catalog ids and OAuth transport/backend ids; resolve to an explicit `:runtime/unsupported?` model map rather than throwing or silently falling through. Consolidate the shared pieces (`persistable-model`, `model-set-message`, `unsupported-runtime-model-message`) at the lowest shared component so per-surface `:reasoning`/message drift can't creep in. (task 245)
