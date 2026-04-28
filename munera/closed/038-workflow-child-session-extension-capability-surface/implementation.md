2026-04-22
- Task created after confirming `complexity-reduction-pr` failed because workflow-owned child sessions had no extension prompt contributions, even though `work-on` was loaded globally.
- Initial hypothesis: child-session initialization preserves prompt-component-selection/tool-defs/skills/system-prompt shaping, but drops parent `:prompt-contributions` entirely.
- Planned minimal fix: inherit parent prompt contributions into child sessions by default and preserve explicit filtering through existing `prompt-component-selection` logic.

2026-04-22
- Confirmed drop point in `dispatch_handlers/session_state.clj` `initialize-child-session-state`: child sessions carried prompt-component-selection/tool-defs/skills/cache-breakpoints but did not copy parent `:prompt-contributions`.
- Implemented minimal fix by copying parent `:prompt-contributions` into child session state during initialization.
- Added focused child-session test proving inherited prompt contributions survive `psi.extension/create-child-session` and appear in the effective system prompt.
- Added focused workflow execution test proving a workflow-created child session inherits parent extension capability prompt content (e.g. `/work-on`).
- Focused verification passed:
  - `clojure -M:test --focus psi.agent-session.child-session-mutation-test --focus psi.agent-session.workflow-execution-test`
  - `clojure -M:test --focus psi.agent-session.child-session-mutation-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.workflow-attempts-test`
- This resolves the runtime/session-surface inconsistency that caused `complexity-reduction-pr` to believe `/work-on` was unavailable despite the extension being loaded.