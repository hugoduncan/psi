# Changelog

All notable user-visible changes to psi are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Version scheme: `MAJOR.MINOR.PATCH` where PATCH = `git rev-list HEAD --count` at release time.

## [Unreleased]

### Added
- TUI slash-command autocomplete with visible completion menu.
- `gh-issue-work-on` prompt for directly working on a GitHub issue in a worktree.
- Prompt-template slash fallback: `/template-name` resolves against `.psi/prompts/`.
- Direct project nREPL support: managed lifecycle, eval, interrupt, and EQL projection per worktree.
- `psi-tool` workflow ops: `list-definitions`, `create-run`, `execute-run`, `read-run`, `list-runs`, `resume-run`, `cancel-run`.
- Launcher-owned extension basis: extensions resolve relative to the launcher root, not the process cwd.
- Agent tool `:skill` parameter: child sessions can be seeded with a skill prelude before execution.
- Background-job widget refresh and notification rendering in TUI (parity with Emacs UI).
- Frontend-action cancel feedback visible in transcript (Escape → "Cancelled select-model.").
- Canonical `psi.agent-session/worktree-path` public session attribute.

### Changed
- Session directory is now authoritative via `:worktree-path`; legacy `:cwd` alias removed.
- Footer, header, and session-tree ownership moved to backend projections; adapters render only.
- Tool definitions are now structured data (`:tool-defs`) rather than serialised strings.
- RPC session navigation unified through `psi.rpc.session.emit/emit-navigation-result!`.
- Context snapshot and session-tree widget payload unified on `context/updated`.
- Capability-graph derivation converged on `psi.graph.analysis`.

### Fixed
- Extension slash-command session targeting after `/new` (commands no longer silently no-op).
- `/work-on --base <branch>` no longer auto-tracks the base branch on fresh worktree creation.
- Tool output rows no longer inherit the assistant prefix face in Emacs.
- Prompt-turn git-head sync no longer duplicated across app-runtime and RPC wrappers.
