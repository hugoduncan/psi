# Changelog

All notable user-visible changes to psi are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Version scheme: `MAJOR.MINOR.PATCH` where PATCH = `git rev-list HEAD --count` at release time.

## [Unreleased]

## [0.1.2095] - 2026-05-18

### Fixed
- CI now serializes build-artifact smoke tests that share `target/psi-unreleased.jar`, preventing release/test races where one smoke test rebuilt `target/` while another was inspecting the jar.

## [0.1.2091] - 2026-05-18

### Fixed
- Library jar packaging now derives its bundled runtime source/resource paths from the authoritative `:psi` launcher alias, preventing installed `psi` releases from omitting extracted runtime components such as `state-kernel`.

## [0.1.2088] - 2026-05-18

### Added
- Workflow session steps now accept an optional `:temperature` field (range `[0.0, 2.0]`). When set, the value is threaded through to the AI provider request. When absent, the provider default applies. Applies to both `:type :session` steps and `:type :llm` judge specs.
- `psi-tool` now supports `action: "mutate"` for invoking registered runtime mutations with structured success/error reports.
- The live graph now exposes explicit session-surface attrs: `:psi.runtime-session/active-id`, `:psi.runtime-session/list`, `:psi.runtime-session/count`, `:psi.persisted-session/list`, and `:psi.persisted-session/list-all`.
- The live graph now exposes `:psi.agent-session/context-session-summaries`, a compact session inventory for operational selection, alongside the explicit runtime-session root attrs.
- New `edit-clj` extension and tool: structural Clojure/EDN form replacement by S-expression equality, preserving surrounding file formatting and supporting optional line-range filtering.

### Fixed
- `psi-tool` mutation execution now preserves an explicitly supplied business `:session-id` for session-scoped mutations like `psi.extension/close-session`, instead of silently retargeting them to the invoking session.
- Workflow IR compilation errors now identify the failing step by name and index, state the violated constraint in plain language, and enumerate all errors — replacing the single opaque "Workflow definition does not compile to execution-valid canonical IR" message.

## [0.1.2067] - 2026-05-12

### Added
- logprobs extension with `logprobs/perplexity` deterministic operation: calculates perplexity of the most recent logprob-bearing reply for a session, available to workflows via invoke steps.
- `gh-issue-refine` workflow: the `discover` step is now a deterministic `:invoke` step backed by the new `psi/github` extension, replacing a non-deterministic AI builder-delegate step. Issue selection is fully determined by the `gh` CLI and selection rules — no AI sampling occurs during issue discovery.
- Major improvements to workflows - branching, deterministic steps, session controls

### Fixed
- Custom model providers whose selected session model stores `:provider` as a string now resolve provider-scoped auth, request options, and runtime model lookup consistently instead of falling back to built-in provider auth behavior.
- Local `:openai-completions` models now project `/thinking off` into `chat_template_kwargs.enable_thinking=false`, allowing local OpenAI-compatible servers to disable hidden reasoning through the existing thinking-level control.

## [0.1.2049] - 2026-05-02

### Added
- Extensions can close sessions via `close-session` and `close-session-tree` mutations.
- Helper sessions from `auto-session-name` are now automatically closed after use.
- Model API HTTP requests now honor standard proxy environment variables (`HTTPS_PROXY`, `HTTP_PROXY`, `ALL_PROXY`); see [Configuration](doc/configuration.md) and [Custom providers](doc/custom-providers.md).

### Fixed
- Auto session naming now falls back across ranked helper models when a preferred helper model is unavailable or yields no usable title.
- Emacs: typing before RPC connects no longer has a newline injected mid-draft when the footer first updates.
- Emacs: footer now updates correctly after connect (was filtered due to missing session-id in payload).
- Emacs: footer content no longer appears inside submitted prompts on longer sessions (root cause: re-entrant projection upsert triggered by undo-outer-limit on large buffers).
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
[Unreleased]: https://github.com/hugoduncan/psi/compare/v0.1.2095...HEAD
[0.1.2095]: https://github.com/hugoduncan/psi/compare/v0.1.2091...v0.1.2095
[0.1.2091]: https://github.com/hugoduncan/psi/compare/v0.1.2088...v0.1.2091
[0.1.2088]: https://github.com/hugoduncan/psi/compare/v0.1.2067...v0.1.2088
[0.1.2067]: https://github.com/hugoduncan/psi/compare/v0.1.2049...v0.1.2067
[0.1.2049]: https://github.com/hugoduncan/psi/compare/v0.1.2034...v0.1.2049
[0.1.2034]: https://github.com/hugoduncan/psi/compare/v0.1.2026...v0.1.2034
[0.1.2026]: https://github.com/hugoduncan/psi/compare/v0.1.2021...v0.1.2026
