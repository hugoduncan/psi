# Implementation

Task created to plan a root-registry migration for `skill-registry`.

Initial design decision was helper-level alignment only, but the user clarified that the desired direction is a real storage move: root-registry should become authoritative for registered session skills.

Current target:

- move canonical session skill storage to root-registry
- keep skills session-scoped, not global across sessions
- preserve public task `173` behavior: exact lookup, duplicate-ignore/first-write-wins, `:added?` / `:changed?`, and canonical exact skill-name ordering
- treat any remaining session `:skills` vector as compatibility projection or legacy hydration seed, not authoritative storage after migration
- migrate all higher read/projection seams away from stale raw `:skills` reads where root-registry data is authoritative

Important design pressure:

Many code paths currently seed, copy, or read `:skills` directly: session defaults, child sessions, scheduler sessions, prompt refresh, prompt request lookup, discovery/session resolvers, commands, TUI, workflow step session config, and tests. The implementation must inventory these seams before changing storage so task `168`'s stale-projection failure pattern does not repeat.

2026-05-22 ambiguity review:
- Found two new actionable ambiguities before implementation: the root-registry-backed skill adapter boundary/API ownership is not explicit, and hydration timing/ownership across session lifecycle paths is underspecified given current pure session-state initialization seams.


2026-05-22 ambiguity follow-up execution:
- Completed both newly added design follow-up items.
- Decided the root-registry adapter belongs in `components/skill-registry` as skill-domain root-state APIs over `psi/root-registry`, while `agent-session` owns calling those APIs at lifecycle/dispatch seams and `session-state` remains a pure session-map initializer.
- Clarified adapter API expectations: ensure storage, hydrate, all/find/names/count, register insert/no-op translation, set/replace, and compatibility projection sync.
- Clarified hydration is synchronous inside the same root-state update for new/resume/fork/child lifecycle handlers, not a follow-up effect; scheduler/workflow-created sessions use those handlers, and explicit later `:session/set-skills` performs authoritative replacement.
- No blockers.
