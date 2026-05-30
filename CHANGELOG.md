# Changelog

All notable user-visible changes to psi are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Version scheme: `MAJOR.MINOR.PATCH` where PATCH = `git rev-list HEAD --count` at release time.

## [Unreleased]

### Added
- Provider retry/backoff history is now queryable through the live graph, including retry counts, retried provider request summaries, attempt error classification, delay source, resume timing, and rate-limit metadata.
- OpenAI OAuth-backed ChatGPT/Codex sessions now support provider-native structured outputs for streaming requests using Responses-style `text.format` JSON Schema, while remaining distinct from Chat Completions `response_format`.
- New `review-task-design` workflow: reviews `design.md` only for ambiguities and inconsistencies, loops until no actionable feedback remains. Invokable via `/delegate review-task-design`.
- New `create-task-plan` workflow: given a stable `design.md`, creates `plan.md` and `steps.md` for a Munera task in a single pass. Invokable via `/delegate create-task-plan`.
- `.md` single-step workflow bodies now support `{{input}}` and `{{original}}` template variables with automatic wiring — no frontmatter declaration needed. `{{input}}` expands to the workflow input text; `{{original}}` expands to the carried original request context.
- `.md` single-step workflows now support a `vars:` frontmatter key (EDN string) for declaring custom variable bindings with `:from :workflow-input` (plus optional `:path`) or `:from :workflow-original` sources.
- Unknown `{{varname}}` tokens in `.md` workflow bodies that are neither standard (`input`, `original`) nor declared in `vars:` produce a compile-time error at workflow load, catching typos and missing declarations before runtime.

### Changed
- `review-task-design` and `review-task-plan` now run ambiguity/inconsistency follow-up steps only when that reviewer reports `PASS_STATUS: ACTIONABLE_FEEDBACK`; `PASS_STATUS: REVIEW_COMPLETE` skips the no-op follow-up while still continuing the remaining review cycle. Plan-review follow-ups now write to `steps.md` instead of `design-steps.md`.
- `review-implementation` workflow renamed to `review-task-implementation`; the old name is no longer available. Update any saved `/delegate review-implementation` invocations to `/delegate review-task-implementation`.
- `review-task-until-clear` workflow renamed to `review-task-plan` and narrowed to `plan.md`/`steps.md` review only (design review is now handled by `review-task-design`). The old name is no longer available.
- `review-task-implementation` now includes a `review-task-docs` step that reviews user-facing documentation (`README.md`, `doc/`, changelog) as part of the implementation review chain.
- Workflow runs now automatically retain only the newest retained terminal runs per originating session, defaulting to `1` kept run via `[:config :completed-workflow-run-retention-count]`; older retained terminal runs are removed from workflow listing/introspection along with their linked workflow-owned child-session trees.

### Fixed
- AI provider request retries now happen at the prepared provider-request boundary for transient request/connection failures, preserving visible active backoff status, per-attempt provider telemetry, structured retry-exhausted/disabled/cancelled/non-retryable outcomes, retry-header delay handling, and streaming retry isolation without rerunning local tools.
- `review-step` now routes review completion deterministically from the review actor's `PASS_STATUS:` line, so `PASS_STATUS: REVIEW_COMPLETE` stops without running no-op follow-up work and actionable feedback loops back through deterministic `follow-up` routing instead of an LLM/session status step.
- OpenAI OAuth-backed `gpt-5.5` sessions now route through the ChatGPT/Codex transport, matching Codex account access instead of failing against the platform chat-completions quota path.

## [0.1.2137] - 2026-05-21

### Fixed
- Project test runs no longer leak temporary persisted session artifacts into the default `~/.psi/agent/sessions/` store; non-persistence is now explicit at shared test/runtime seams and persistence tests use isolated temporary session roots.

## [0.1.2123] - 2026-05-19

### Fixed
- Released `psi --tui` startup now resolves its shipped runtime dependency closure from jar-owned release metadata packaged at `psi/release-deps.edn`, so release-tag `:jar` policy no longer misses TUI runtime dependencies such as `charm.clj`.
- Released psi packaging now carries authoritative jar-owned runtime dependency metadata plus the shipped psi-owned runtime source/resource trees, and release smoke coverage proves isolated install/package startup through the artifact-shaped launcher path.
- Unreleased tmux TUI smoke now prefers the current worktree launcher over a stale installed `psi` on `PATH`, preventing local smoke runs from silently proving an older installed release instead of the checkout under test.

## [0.1.2119] - 2026-05-18

### Fixed
- Released `psi` launcher packaging now includes the full stamped-runtime component surface and required runtime dependencies, so installed `bbin` releases can start and print `--help` successfully under release-tag `:jar` policy.

## [0.1.2115] - 2026-05-18

### Fixed
- Released `psi` launcher installs now resolve `psi/github` and `psi/edit-clj` correctly under `:jar` policy, preventing startup failure from missing psi-owned source-policy defaults.
- `psi --help` now prints non-interactive CLI usage and exits successfully instead of starting an interactive session.

## [0.1.2104] - 2026-05-18

### Added
- The `Release` GitHub Actions workflow now supports manual `workflow_dispatch` dry runs with `ref`, `publish`, and `release_version` inputs so release build validation can run without publishing to Clojars or creating a GitHub Release.
- Release verification now smoke-tests the installed `bbin` launcher entrypoint for published artifacts, not just direct `psi.main` startup.

### Fixed
- Released `psi` launcher installs now resolve `psi/github` and `psi/edit-clj` in `:jar` policy correctly; their psi-owned catalog entries now include Maven defaults instead of failing at startup with `Psi-owned catalog entry is missing source policy defaults`.
- Release smoke verification now inspects the actual built library jar path derived from the stamped version resource, instead of assuming `target/psi-unreleased.jar`.

## [0.1.2099] - 2026-05-18

### Fixed
- Build-artifact smoke tests now share an explicit in-process build lock during CI/release verification, preventing parallel test execution from deleting `target/psi-unreleased.jar` while another smoke test is inspecting it.

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
[Unreleased]: https://github.com/hugoduncan/psi/compare/v0.1.2137...HEAD
[0.1.2137]: https://github.com/hugoduncan/psi/compare/v0.1.2123...v0.1.2137
[0.1.2123]: https://github.com/hugoduncan/psi/compare/v0.1.2119...v0.1.2123
[0.1.2119]: https://github.com/hugoduncan/psi/compare/v0.1.2115...v0.1.2119
[0.1.2115]: https://github.com/hugoduncan/psi/compare/v0.1.2109...v0.1.2115
[0.1.2109]: https://github.com/hugoduncan/psi/compare/v0.1.2104...v0.1.2109
[0.1.2104]: https://github.com/hugoduncan/psi/compare/v0.1.2099...v0.1.2104
[0.1.2099]: https://github.com/hugoduncan/psi/compare/v0.1.2095...v0.1.2099
[0.1.2095]: https://github.com/hugoduncan/psi/compare/v0.1.2091...v0.1.2095
[0.1.2091]: https://github.com/hugoduncan/psi/compare/v0.1.2088...v0.1.2091
[0.1.2088]: https://github.com/hugoduncan/psi/compare/v0.1.2067...v0.1.2088
[0.1.2067]: https://github.com/hugoduncan/psi/compare/v0.1.2049...v0.1.2067
[0.1.2049]: https://github.com/hugoduncan/psi/compare/v0.1.2034...v0.1.2049
[0.1.2034]: https://github.com/hugoduncan/psi/compare/v0.1.2026...v0.1.2034
[0.1.2026]: https://github.com/hugoduncan/psi/compare/v0.1.2021...v0.1.2026
