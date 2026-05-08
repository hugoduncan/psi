Approach:
- treat this as a lower-component extraction, not a persistence behavior redesign
- move the canonical session-facing journal/persistence API out of `agent-session` into `psi.session-persistence.core`
- establish the settled first-cut public names explicitly in implementation: `session-journal-path`, `session-flush-state-path`, `flush-state`, `persistence-state`, `append-journal-entry-in!`, and `persist-journal-in!`
- move persistence-specific path ownership with the component instead of leaving it behind in `session-state.state`
- move canonical persistence subtree initialization helpers with the component so higher layers stop reconstructing persistence maps manually
- preserve all live lazy-flush, append-first, and persisted-session loading/listing semantics

Planned outcomes:
1. create a new `session-persistence` component with `psi.session-persistence.core` as the authoritative public owner of session-facing journal/persistence semantics
2. migrate the current `psi.agent-session.persistence` API surface into that component, ending on authoritative public names such as `append-journal-entry-in!` and `persist-journal-in!`
3. move journal/flush-state path ownership out of `psi.session-state.state` into the new component under `session-journal-path` and `session-flush-state-path`
4. move persistence subtree initialization helpers out of `session-runtime` and `child-session-state` into the new component under `flush-state` and `persistence-state`
5. update `dispatch-effects`, `psi.turn`, app-runtime, workflow/session lifecycle, and related consumers to depend downward on the new component
6. preserve behavior with focused component-local and higher-level regression proofs

Scope boundaries:
- no redesign of `session-journal.store` codec/store semantics
- no redesign of dispatch effect taxonomy
- no redesign of session lifecycle policy for when entries are appended
- no expansion into extension-install persistence or unrelated file persistence surfaces
- no broad state-root redesign beyond moving persistence-specific path ownership out of `session-state.state`

Execution strategy:
1. implement the settled first-cut component namespace/API shape around `psi.session-persistence.core`
2. create `components/session-persistence/` and land focused component-local tests for `session-journal-path`, `session-flush-state-path`, `append-journal-entry-in!`, `persist-journal-in!`, `flush-state`, and `persistence-state`
3. move persistence-specific path builders and the canonical journal append primitive from `session-state.state` into the new component
4. move the current `agent-session.persistence` implementation into the new component and update consumers from compatibility names toward the authoritative public names
5. replace manual persistence subtree construction in runtime/child-session initialization with `flush-state` / `persistence-state`
6. rerun focused persistence, turn, lifecycle, app-runtime, and workflow regressions
7. remove temporary compatibility wrappers before closure unless a remaining wrapper is explicitly justified in `implementation.md`
