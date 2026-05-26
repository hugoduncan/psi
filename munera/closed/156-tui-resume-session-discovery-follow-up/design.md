# 156 — TUI resume session discovery follow-up

## Intent
Investigate and fix the live TUI `/resume` path so persisted sessions for the current worktree are discoverable and selectable reliably in the tmux harness and normal interactive use.

## Context
While stabilizing release smoke gating, the scripted tmux streaming scenario was repaired, but the live tmux rehydration scenario had to be quarantined. The remaining issue is not transcript formatting itself; unit tests still cover transcript rehydration semantics. The unresolved behavior is that a persisted session fixture exists on disk for the target worktree and is discoverable in-process, yet the live TUI `/resume` selector reports `(no sessions found)` in the tmux harness. Canonicalizing the tmpdir removed one path-mismatch cause, but the live discovery path remains unreliable.

## Scope
- Diagnose the live `/resume` discovery path end-to-end for the TUI runtime, including the effective worktree path, session-dir resolution, persisted-session listing, and selector population.
- Identify why the live tmux-launched TUI can fail to surface persisted sessions that are present on disk for the selected worktree.
- Restore a reliable live rehydration tmux scenario once the underlying issue is fixed.
- Remove the quarantine/skip from the live tmux rehydration scenario only when the repaired behavior is stable.

## Out of scope
- General transcript rendering changes unrelated to `/resume` discovery.
- New session-selector UX features beyond what is required to make existing resume discovery reliable.
- Broader persistence format redesigns unless the root cause proves they are necessary.

## Acceptance
1. Given a persisted session file for the current worktree, the live TUI `/resume` selector surfaces that session in the tmux harness.
2. Selecting the surfaced session restores the transcript reliably enough for the live tmux rehydration scenario to assert the expected thinking/text content.
3. The quarantined tmux rehydration scenario is re-enabled and passes locally and in CI when appropriate.
4. Any path-normalization, home-directory, launcher-root, or session-discovery invariants needed by the fix are made explicit in tests.
