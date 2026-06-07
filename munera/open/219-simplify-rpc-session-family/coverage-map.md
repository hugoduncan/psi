# 219 — Coverage Map

Authoritative coverage/gap record for the `psi.rpc.session` architecture simplification. Filled during Slice 2 before production refactoring; rechecked during Slice 5.

## Verification command

```bash
bb clojure:test:scry --dir components/rpc/test \
  --namespace psi.rpc-command-results-test \
  --namespace psi.rpc-prompt-command-test \
  --namespace psi.rpc-prompt-test \
  --namespace psi.rpc-session-navigation-test \
  --namespace psi.rpc-events-test \
  --namespace psi.rpc-invariants-test \
  --namespace psi.rpc-ops-test \
  --namespace psi.rpc-test
```

Latest characterization pass in this map: green, 59 tests / 469 assertions.

## Source-area coverage

### `components/rpc/src/psi/rpc/session/command_pickers.clj`

Covered behaviours:

- `/model` command emits a `ui/frontend-action-requested` event whose `:ui/action` is the adapter-neutral `:select-model` picker, with backend-owned provider/id ordering and model item values.
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` (`/model command emits a frontend action request with model picker payload`)
- `/thinking` command emits a `ui/frontend-action-requested` event whose `:ui/action` is the adapter-neutral `:select-thinking-level` picker with current thinking-level ordering.
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` (`/thinking command emits a frontend action request with thinking picker payload`)
- Submitted `select-model` frontend result updates canonical session model and emits current RPC command/session/footer outputs.
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` (`submitted select-model frontend action updates model and emits command/session snapshots`)
- Submitted `select-thinking-level` frontend result updates canonical thinking level and emits current RPC command/session/footer outputs.
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` (`submitted select-thinking-level frontend action updates thinking and emits command/session snapshots`)

### `components/rpc/src/psi/rpc/session/command_results.clj`

Covered behaviours:

- Extension command output precedence: returned string, returned `{:message ...}`, stdout, blank output suppression, and deterministic handler-error text.
  - `psi.rpc-command-results-test/extension-command-output-test`
- Command op `:extension-cmd` emits no placeholder for blank output and emits text command-result for returned/stdout output.
  - `psi.rpc-command-results-test/handle-command-result-extension-command-test`
- Legacy prompt path suppresses blank extension command placeholders.
  - `psi.rpc-command-results-test/handle-prompt-command-result-extension-command-test`
- Prompt-path slash command result mappings for text, extension command, login start/manual/callback, quit, resume, remember success/block/fallback.
  - `psi.rpc-prompt-command-test/rpc-prompt-handle-command-result-types-test`
- Command op template fallback and unknown command-result text.
  - `psi.rpc-prompt-command-test/rpc-command-op-template-fallback-test`

### `components/rpc/src/psi/rpc/session/command_resume.clj`

Covered behaviours:

- `/resume <path>` emits canonical `session/resumed` and `session/rehydrated` events with journal-derived messages.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
- `/tree <session-id>` reuses session matching/rehydration and emits context update for the active session.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
- Switch/get-messages derive transcript from canonical ctx journal when agent messages drift.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`

### `components/rpc/src/psi/rpc/session/command_tree.clj`

Covered behaviours:

- `/tree` emits a frontend selector payload with backend-owned session/fork-point order.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
- `/tree <session-id>` emits canonical resume/rehydrate/context events for an existing context session.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`

Additional characterization added in this pass:

- `/tree <active-session>` reports `Already active session: <sid>` as text and does not emit rehydration.
  - `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test`
- `/tree <missing>` reports `Session not found in context: <arg>` as text and does not emit rehydration.
  - `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test`
- `/tree name <session-id> <name>` mutates canonical session name state and reports the exact rename text.
  - `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test`
- `/tree <unique-session-id-prefix>` switches to the matched session and emits canonical resume/rehydrate/context events.
  - `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test`

Disposition: ambiguous prefix behaviour remains accepted existing behaviour by source inspection of `command-resume/maybe-match-selector-session`: zero or multiple prefix matches produce the same not-found text path as other unmatched selectors; the missing-session characterization locks the externally observable unmatched-selector result.

### `components/rpc/src/psi/rpc/session/commands.clj`

Covered behaviours:

- Slash dispatch gate: command result suppresses agent loop; nil command dispatch runs agent loop.
  - `psi.rpc-prompt-command-test/rpc-prompt-slash-dispatch-gate-test`
- Command op prompt-template fallback routes through canonical prompt semantics and journals submitted slash text.
  - `psi.rpc-prompt-command-test/rpc-command-op-template-fallback-test`
- Command `/new` emits canonical resume/rehydrate, command-result, footer update, and makes the new session active for later extension commands.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
  - `psi.rpc-session-navigation-test/rpc-extension-command-after-new-emits-assistant-message-for-new-session-test`
- Callback-backed command-op `/new` threads runtime `:on-new-session!` into slash resolution and emits callback-created rehydration, startup transcript, tool metadata/order, RPC focus movement, accepted command response, and `new_session` command-result output through the shared helper.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test` (`command /new with callback emits callback rehydration and command result`)
- Picker command routing for `/model` and `/thinking` is covered by the new characterization test in `psi.rpc-test`.
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test`

### `components/rpc/src/psi/rpc/session/emit.clj`

Covered behaviours:

- Navigation result emission sets active focus and emits resume/rehydrate/session/footer/context outputs for new/switch/fork and frontend-action navigation.
  - `psi.rpc-test/rpc-fork-emits-context-updated-test`
  - `psi.rpc-test/rpc-new-session-emits-context-updated-test`
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test`
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
- Assistant message/text and command-result payload shapes are exercised through prompt, command-result, extension-command, and picker/frontend-action tests.
  - `psi.rpc-prompt-test/rpc-prompt-streams-events-and-interleaves-test`
  - `psi.rpc-command-results-test/handle-command-result-extension-command-test`
  - `psi.rpc-prompt-command-test/rpc-prompt-handle-command-result-types-test`

### `components/rpc/src/psi/rpc/session/frontend_actions.clj`

Covered behaviours:

- `frontend_action_result` `select-session` submitted values switch to an existing session or fork from a fork-point and emit canonical navigation events.
  - `psi.rpc-test/rpc-fork-emits-context-updated-test`
- `frontend_action_result` `select-model` and `select-thinking-level` submitted values update canonical session state and emit command/session/footer snapshots.
  - `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test`

Additional characterization added in this pass:

- `frontend_action_result` cancelled emits a text command-result (`Cancelled <action-name>.`), returns `{:accepted true}`, and emits no session/footer snapshots.
  - `psi.rpc-test/rpc-frontend-action-cancelled-and-failed-result-test`
- `frontend_action_result` failed emits an error command-result with the frontend error message, returns `{:accepted true}`, and emits no session/footer snapshots.
  - `psi.rpc-test/rpc-frontend-action-cancelled-and-failed-result-test`

### `components/rpc/src/psi/rpc/session/navigation.clj`

Covered behaviours:

- `new_session` emits `session/resumed`, `session/rehydrated`, response data, and `context/updated` from app-runtime navigation results.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
  - `psi.rpc-test/rpc-new-session-emits-context-updated-test`
- `switch_session` and `fork` emit canonical navigation events and journal-derived rehydration.
  - `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`
  - `psi.rpc-test/rpc-fork-emits-context-updated-test`

### `components/rpc/src/psi/rpc/session/projections.clj`

Covered behaviours:

- Subscribing to UI topics emits initial canonical extension UI snapshots and backend-owned widget/status ordering.
  - `psi.rpc-ops-test/rpc-subscribe-ui-topics-emits-initial-widget-snapshot-test`
  - `psi.rpc-ops-test/rpc-subscribe-ui-topics-emits-canonical-widget-and-status-order-test`
  - `psi.rpc-ops-test/rpc-subscribe-ui-topics-emits-initial-notification-snapshot-test`
- UI widget, notification, status, and dialog updates stream from event-driven projection delivery without prompt polling.
  - `psi.rpc-ops-test/rpc-event-driven-ui-projection-streams-widget-updates-without-prompt-test`
  - `psi.rpc-ops-test/rpc-event-driven-ui-projection-streams-notifications-without-prompt-test`
  - `psi.rpc-ops-test/rpc-event-driven-ui-projection-streams-status-and-dialog-without-prompt-test`
- Projection listener unregisters when projection topics are removed.
  - `psi.rpc-ops-test/session-request-handler-query-eql-and-op-mapping-test`

### `components/rpc/src/psi/rpc/session/prompt.clj`

Covered behaviours:

- Prompt worker uses explicit `:session-id` routing.
  - `psi.rpc-prompt-command-test/rpc-prompt-honors-explicit-session-id-test`
- Prompt slash dispatch gates agent loop and journals slash commands; plain text prompts are journaled on the agent-loop path.
  - `psi.rpc-prompt-command-test/rpc-prompt-slash-dispatch-gate-test`
  - `psi.rpc-prompt-command-test/rpc-prompt-slash-command-journaled-test`
  - `psi.rpc-prompt-command-test/rpc-prompt-plain-text-journaled-test`
- Prompt-op `/new` slash-command path bypasses the agent loop and emits externally observable rehydration/focus/snapshot outputs after the shared command-helper refactor.
  - `psi.rpc-prompt-command-test/rpc-prompt-new-slash-command-rehydrates-without-agent-loop-test`
- Callback-backed prompt-op `/new` emits callback-supplied startup transcript and tool metadata through the shared command rehydration helper.
  - `psi.rpc-prompt-command-test/rpc-prompt-new-slash-command-uses-callback-rehydrate-payload-test`
- Non-command prompt request preparation expands skill input and forwards runtime-resolved API keys.
  - `psi.rpc-prompt-command-test/rpc-prompt-expands-skill-input-during-request-preparation-test`
  - `psi.rpc-prompt-command-test/rpc-prompt-passes-resolved-api-key-to-agent-loop-test`
- Prompt routes through dispatch-visible prompt lifecycle, not mutable executor state.
  - `psi.rpc-invariants-test/rpc-prompt-uses-dispatch-lifecycle-invariant-test`

### `components/rpc/src/psi/rpc/session/streams.clj`

Covered behaviours:

- Prompt stream interleaves accepted response with assistant/tool/session/footer events and monotonically increasing event seq values.
  - `psi.rpc-prompt-test/rpc-prompt-streams-events-and-interleaves-test`
- Footer updates tolerate sentinel values and provider retry activation/change/clear publishes visible footer refreshes.
  - `psi.rpc-prompt-test/rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test`
  - `psi.rpc-prompt-test/rpc-prompt-provider-retry-state-publishes-footer-updated-test`
- Thinking deltas after tool-start begin a fresh segment; OpenAI Codex tool events include final args.
  - `psi.rpc-prompt-test/rpc-thinking-delta-after-tool-start-begins-fresh-segment-test`
  - `psi.rpc-prompt-test/rpc-openai-codex-prompt-emits-tool-events-with-final-args-test`

## Behaviour coverage

### Command dispatch and command results

- `covered-by`: dispatch gate, prompt result mapping, extension command output, template fallback, unknown command text, default and callback-backed `/new` command activation, and command snapshots are covered by `psi.rpc-prompt-command-test`, `psi.rpc-command-results-test`, and `psi.rpc-session-navigation-test` vars listed above.

### Picker/model/thinking/frontend-action behaviours

- `added-test`: `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` now characterizes `/model`, `/thinking`, submitted `select-model`, and submitted `select-thinking-level` through externally observable RPC frames and canonical session/footer outputs.

### Command tree/resume/session rehydration/navigation

- `covered-by`: `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test` and `psi.rpc-test/rpc-fork-emits-context-updated-test` cover new, resume, switch, fork, selector order, and journal-derived rehydration.
- `added-test`: `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test` characterizes `/tree` already-active, missing-session, rename, and unique-prefix switch outputs/state/events. Ambiguous-prefix handling is accepted existing coverage via the same unmatched-selector result path locked by the missing-session case.

### Prompt/stream behaviours

- `covered-by`: prompt command tests plus prompt stream tests cover slash handling, event mapping, assistant message emission, retry/footer refresh, thinking/tool events, and prompt lifecycle invariants.

### Projection/emit behaviours

- `covered-by`: RPC ops/projection tests cover initial snapshots, event-driven invalidation delivery, listener unregister, canonical UI projection delegation, and context/footer/session payload shapes.

## Gaps and disposition

- `added-test` — Picker/model/thinking/frontend-action RPC gap: before this pass, picker selection semantics were covered mostly outside the pinned RPC suite or only through app-runtime/TUI tests. Added `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` to assert RPC `/model` and `/thinking` event outputs plus submitted frontend-action results using state/output assertions.
- `added-test` — Command-tree edge cases: added `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test` for `/tree` already-active, missing-session, rename, and unique-prefix switch behaviours. Ambiguous-prefix handling remains accepted existing source-path coverage because multiple prefix matches intentionally fall through to the same unmatched-selector text path as the characterized missing-session case.
- `added-test` — Frontend-action cancelled/failed RPC result payloads: added `psi.rpc-test/rpc-frontend-action-cancelled-and-failed-result-test` for accepted responses, command-result payloads, and no session/footer snapshot emission on cancelled/failed outcomes.
- `added-test` — Prompt-op `/new` shared-helper regression gap: added `psi.rpc-prompt-command-test/rpc-prompt-new-slash-command-rehydrates-without-agent-loop-test` for accepted prompt response, no agent-loop invocation, `session/resumed`, `session/rehydrated`, RPC focus movement, prompt-path assistant confirmation, and session/footer snapshots.
- `added-test` — Callback-backed prompt-op `/new` shared-helper regression gap: added `psi.rpc-prompt-command-test/rpc-prompt-new-slash-command-uses-callback-rehydrate-payload-test` for callback source-session id, no agent-loop invocation, callback-created new-session rehydration, startup transcript messages, tool-calls/tool-order metadata, RPC focus movement, and prompt-path confirmation.
- `added-test` — Callback-backed command-op `/new` shared-helper regression gap: added callback-backed command-op coverage in `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test` for callback source-session id, accepted command response, callback-created `session/resumed` / `session/rehydrated` payloads, callback startup transcript messages, tool metadata/order, RPC focus movement, and `new_session` command-result output.
