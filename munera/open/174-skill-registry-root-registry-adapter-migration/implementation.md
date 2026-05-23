# Implementation

Task created to plan a root-registry migration for `skill-registry`.

Initial design decision was helper-level alignment only, but the user clarified that the desired direction is a real storage move: root-registry should become authoritative for registered session skills.

The user then further clarified that the migration must remove legacy session `:skills` projection storage. Compatibility should be read-time/API projection from root-registry and one-way hydration from legacy/input seeds only, not synchronized duplicate storage.

Current target:

- move canonical session skill storage to root-registry
- keep skills session-scoped, not global across sessions
- preserve public task `173` behavior: exact lookup, duplicate-ignore/first-write-wins, `:added?` / `:changed?`, and canonical exact skill-name ordering
- treat any input/persisted session `:skills` vector as a one-way hydration seed only
- remove `:skills` from runtime/persisted session data after hydration/create/set/register paths complete
- migrate all higher read/projection seams away from raw `:skills` reads where root-registry data is authoritative

Important design pressure:

Many code paths currently seed, copy, or read `:skills` directly: session defaults, child sessions, scheduler sessions, prompt refresh, prompt request lookup, discovery/session resolvers, commands, TUI, workflow step session config, and tests. The implementation must inventory these seams before changing storage so task `168`'s stale-projection failure pattern does not repeat. Because legacy projection storage is being removed rather than synchronized, every remaining raw `:skills` access must be either eliminated or explicitly limited to a pre-hydration seed read.
