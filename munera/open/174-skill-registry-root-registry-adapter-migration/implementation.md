# Implementation

Task created to plan a root-registry migration for `skill-registry`.

Initial design decision was helper-level alignment only, but the user clarified that the desired direction is a real storage move: root-registry should become authoritative for registered session skills.

The user then further clarified that the migration must remove legacy session `:skills` projection storage. Compatibility should be read-time/API projection from root-registry and one-way hydration from legacy/input seeds only, not synchronized duplicate storage.

The user then further clarified the desired ownership boundary:

- registry owns skill definitions
- session owns which skills it includes, but only by reference

The user then clarified that we do not need a legacy hydration path.

Current target:

- move canonical skill definitions to root-registry
- keep session ownership of membership via `:skill-ids`
- preserve public task `173` behavior: exact lookup, duplicate-ignore/first-write-wins, `:added?` / `:changed?`, and canonical exact skill-name ordering
- remove embedded `:skills` from runtime/persisted session data
- migrate all higher read/projection seams away from raw `:skills` reads onto session `:skill-ids` plus registry lookup
- do not retain a legacy embedded-`:skills` hydration compatibility path in this task

Important design pressure:

Many code paths currently seed, copy, or read embedded `:skills` directly: session defaults, child sessions, scheduler sessions, prompt refresh, prompt request lookup, discovery/session resolvers, commands, TUI, workflow step session config, and tests. The implementation must inventory these seams before changing storage so task `168`'s stale-projection failure pattern does not repeat. Because embedded `:skills` storage is being removed rather than synchronized, every remaining raw `:skills` access must be either eliminated or replaced with `:skill-ids` + registry lookup, and child-session plus related inheritance paths must become `:skill-ids`-driven.
