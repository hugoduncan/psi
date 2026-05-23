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
