# Changelog

All notable user-visible changes to psi are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Version scheme: `MAJOR.MINOR.PATCH` where PATCH = `git rev-list HEAD --count` at release time.

## [Unreleased]

### Added
- Extensions can close sessions via `close-session` and `close-session-tree` mutations.
- Helper sessions from `auto-session-name` are now automatically closed after use.

### Fixed
- Emacs: typing before RPC connects no longer has a newline injected mid-draft when the footer first updates.
- Emacs: footer now updates correctly after connect (was filtered due to missing session-id in payload).
- Emacs: footer content no longer appears inside submitted prompts on longer sessions.
- Emacs: re-focusing the psi window when transport is ready no longer incorrectly resets the footer to "connecting..." (was causing prompt submission to not clear input or add to chat).
- Emacs: footer/updated events from the external event loop now carry session-id so they pass the session-match guard.
- TUI startup banner model line now reflects the canonical current session model.
- Custom Anthropic-compatible model providers now use the selected provider's configured auth and base URL instead of requiring built-in Anthropic credentials.

## [0.1.2034] - 2026-04-29

### Added
- `M-r` searches prompt input history via completing-read (see [Emacs UI](doc/emacs-ui.md)).
- Session-first workflow authoring now supports explicit step input/reference sources, projections, and preload context (see [Workflow docs](doc/extensions.md)).

### Fixed
- TUI text surfaces now use an explicit width policy so narrow terminals wrap startup banner metadata, transcript user/thinking text, and expanded tool body text predictably, while compact tool headers truncate intentionally (see [TUI width policy](doc/tui-text-width-policy.md)).

## [0.1.2026] - 2026-04-27

### Added
- Initial Version

### Changed

### Fixed

<!-- Comparison links -->
[Unreleased]: https://github.com/hugoduncan/psi/compare/v0.1.2034...HEAD
[0.1.2034]: https://github.com/hugoduncan/psi/compare/v0.1.2026...v0.1.2034
[0.1.2026]: https://github.com/hugoduncan/psi/compare/v0.1.2021...v0.1.2026
