# Changelog

All notable user-visible changes to psi are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Version scheme: `MAJOR.MINOR.PATCH` where PATCH = `git rev-list HEAD --count` at release time.

## [Unreleased]

### Added
- New `extract-task-knowledge` workflow: autonomously mines a completed Munera task's artifacts and task-scoped git history for project-general mementum memories or knowledge pages, applies conservative mementum gates and dedupe recall, treats zero extraction as a successful outcome, and commits qualifying mementum writes without requesting human approval. Invokable via `/delegate extract-task-knowledge {NNN-slug}`.
- Scry (`org.hugoduncan/scry`) is now psi's default Clojure test-runner path. The `bb clojure:test:unit`, `bb clojure:test:extensions`, and `bb clojure:test:integration` tasks run the configured Kaocha suites through Scry's Kaocha adapter first, falling back to the previous raw Kaocha runner only when Scry cannot complete the run. Use `bb clojure:test:scry --namespace <test-ns>` (or the Scry REPL API from the project nREPL) for focused machine-readable `clojure.test` inspection instead of scraping terminal output.
- New `:agent-session :project-nrepl :start-readiness-timeout-ms` config key controls how long managed started-mode project nREPL acquisition waits for the launched process to write its `.nrepl-port` before failing. Optional integer milliseconds in the range `[1000 600000]` (default `120000`), with the usual `system < user < project` precedence. The effective resolved timeout is surfaced as the `:readiness-timeout-ms` instance status field (via `/project-repl status`), observable even on a readiness failure.
- New `reduce-incidental-complexity` workflow: autonomously simplifies one aspect of the system per run by targeting *incidental* complexity (high `gordian local` comprehension burden against low/moderate cyclomatic complexity). It selects the single highest `gap = lcc-total / max(cc, 1)` unit via the new `incidental-complexity-finder` skill, opens a constrained behaviour-preserving refactor task in the invoking session's current worktree, and drives target-present runs through explicit design/plan, characterization-test-net, diff-gate, simplification, and review phases. Stops early with no task when nothing qualifies; does not call `work-on`, switch worktrees, push, or open a PR. Invokable via `/delegate reduce-incidental-complexity`.
- New `task-lifecycle` workflow: runs a Munera task through its full design → plan → implement → review lifecycle by chaining `review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, and `review-task-implementation` in order. Invokable via `/delegate task-lifecycle`.
- `review-task-design` (invokable via `/delegate review-task-design`) now includes an architectural-fit review aspect that runs first, before the ambiguity and inconsistency aspects. It checks the task design's fit with the current architecture (consulting the in-context architecture sources) and loops on actionable feedback like the other aspects.
- New `/reload-prompts` command and `psi.extension/reload-prompts` mutation (visible to `psi-tool` via `action: "mutate"`) re-discover prompt templates from disk for the session's worktree (`~/.psi/agent/prompts` and `<worktree>/.psi/prompts`) and replace the session's registered templates, so editing, adding, or deleting a prompt `.md` takes effect without restarting the session.
- Registered deterministic operations can now be invoked directly outside a workflow run through two new surfaces sharing one mechanism: the `/operations` (list) and `/operation <id> {edn-args}` (invoke) slash commands, and a psi-tool `operation` action with `op: list|invoke`. Listing returns each operation's id and description sorted by id; invocation runs the operation with EDN-map `args` (default `{}`) through the existing runtime boundary and renders every top-level result key (each value `pr-str`'d, truncated to 2000 chars). Side-effecting operations are invokable; unknown ids and malformed results are surfaced as clear errors.

### Changed
- `task-lifecycle` now ends with a trailing `extract-task-knowledge` stage after `review-task-implementation`, so completed lifecycle runs attempt conservative mementum knowledge extraction and return a final summary that preserves the implementation-review/lifecycle outcome plus the extraction result.
- `reduce-incidental-complexity`'s generated Phase-1 burden-reduction acceptance is now a sound per-unit relocation-guard ceiling instead of the previously-emitted net-sum gate. The old A2 demanded the *sum* of `lcc-total` across the metric-derived touched set strictly decrease (`sum after < sum before`) — provably unsatisfiable for a genuine decomplecting extraction (splitting one tangle into N seams is sub-additive, so the sum can rise even as every piece is simpler), which blocked legitimate simplifications. A2 now requires every new or below-ceiling physical after-row `u` to satisfy `after(u) < B`, where `B := before(target)` is read from the committed `before-local.json` baseline; the target unit's own reduction is governed by the existing A5 check. This rejects merely relocating a tangle into a new seam or a sibling while admitting genuine decomplecting work.
- `reduce-incidental-complexity` now exposes its generated task lifecycle as explicit workflow phases and enforces a pre-simplification characterization-test-net gate before implementation. Target-present runs review the generated design/plan, record a task-local `characterization-baseline.edn`, iterate coverage review/fix until nominal/edge/boundary behavior is sufficiently characterized and green, then run a committed-plus-uncommitted diff gate that allows only tests, task artifacts, docs, and explicitly justified minimal testability seams before simplification. No-target runs still stop directly after selection with no task or downstream gate steps.
- Built-in slash commands now appear consistently in both the TUI and Emacs autocomplete: the backend is the single authoritative source of the built-in command surface (names + descriptions), exposed via the `:psi.agent-session/builtin-command-specs` graph attribute, and both UIs consume it the same way they already consume extension commands. Previously the built-in command lists were hardcoded separately in the TUI and Emacs and had drifted, so commands such as `/reload-models`, `/reload-prompts`, `/reload-extension-installs`, `/speed`, `/effort`, and `/project-repl` were missing from one or both autocompletes; they now show up automatically. The Emacs `psi-emacs-slash-command-specs` `defcustom` is now a user override/supplement (default trimmed to the Emacs-only `/skill:` affordance) — backend built-in descriptions win on any name collision.
- Extension `tool_result` events fired on the interactive/batch tool execution path now carry the parsed tool arguments under `:input`, matching the data-driven plan path; previously `:input` was present only on the plan path, so a handler reading it silently received `nil` on the interactive path.
- Extension `tool_result` handler overrides now coerce `:content` to normalized content-blocks and `:is-error` to a strict boolean — the same coercions applied to the inbound payload; previously an override's raw `:content`/`:is-error` values were copied onto the result unchanged, bypassing normalization.
- The review workflows (`review-task-design`, `review-task-plan`, and the `review-step` loop behind `review-task-implementation`) now share two profile follow-up steps instead of five near-identical per-aspect follow-up prompts. Follow-up behaviour is unchanged for design and plan reviews; the implementation-review (`review-step`) follow-up now explicitly executes only the items the immediately preceding review pass added, leaving any pre-existing unchecked items untouched across loop iterations.

### Fixed
- Completed workflow-run retention no longer deletes the run you just delegated (and its sessions) when that single delegation is a multi-step workflow. A `:delegate` workflow step creates a nested sub-run that shares the originating session's `:parent-session-id`; retention was counting that internal sub-run as a second competing delegation, so with the default retention count of `1` the top-level run (or the sub-run) and its workflow-owned child sessions were evicted as soon as the delegation finished. Retention now counts only top-level delegated runs — nested `:delegate` sub-runs (tagged with `:delegating-run-id`) belong to their delegating parent run and are retained or removed transitively with it, never competing for the per-session retention budget.
- Active provider retry backoff now publishes `footer/updated` while retry state is active, when visible retry metadata changes, and again when it clears, so Emacs receives the existing app-runtime retry footer text (for example `retry in Ns`) without requiring a manual refresh.
- TUI queued follow-up input while a session is idle is now actually stored as a follow-up message. Previously the public queue callback reported "Queued follow-up message." but routed through a streaming-only helper, so non-streaming follow-up text could be dropped.
- `psi-tool project-repl op=start` (and `/project-repl start`) no longer fails with a `:started-readiness` timeout for real, slow-booting start commands. The hard-coded 5 s readiness timeout was raised to a configurable 120 s default (see `:start-readiness-timeout-ms` above), so a cold `clojure -M …` JVM + classpath build + nREPL/cider middleware load completes before the deadline.
- Managed started-mode project nREPL acquisition no longer latches onto a stale `<worktree>/.nrepl-port` left by a prior or unrelated process (which silently connected to the wrong endpoint). Psi now deletes any pre-existing `.nrepl-port` before launching and only accepts a port file written at or after the launch instant, so start connects to the process it launched. A timeout reached while only a stale port is present is reported distinctly (`:phase :started-stale-port`).
- A started-mode project nREPL command that launches but never becomes ready (a hung or slow-booting process that does not write a usable `.nrepl-port`) is now destroyed when the readiness wait times out, instead of being left running as an orphaned child process. Previously the launched process was only reaped on a successful start, so a readiness/stale-port failure leaked it — a leak whose window widened with the raised 120 s default timeout.
- Delegated workflows now inherit their default session details (model, prompt-mode, tools, skills, thinking-level, speed-mode, effort-override) from a snapshot captured when the workflow run is created, instead of re-reading the live parent session on every step. Changing the invoking session's model (or the user/project default) after a workflow has started no longer retroactively alters the still-running workflow's subsequent steps. A nested/delegated sub-workflow inherits the delegating step's *effective* config (run snapshot plus that step's overrides) captured at sub-delegation creation, so a step that overrides the model and then delegates propagates the overridden model down. Steps that specify their own explicit overrides are unaffected; the captured snapshot is part of the run's replayable canonical state. The inherited `thinking-level` now follows the same precedence as the inherited model — it ranks above a workflow file's `thinking-level` default (step override → inherited → workflow-file default → off), so a workflow-file `thinking-level` no longer masks the inherited parent value (previously it did, the lone field where the static default outranked the inherited one).
- Killing an Emacs psi buffer while a widget-projection mutation is in flight no longer leaves orphaned, non-deterministically-firing, or cross-buffer-colliding watchdog timers. The mutation watchdog timers are now held in buffer-local `psi-emacs-state` (like the existing notification timers) and are cancelled when the buffer is torn down or its transcript is reset; the mutation timeout and RPC-response callbacks now act on the originating buffer and are no-ops once that buffer is dead.
- Tool invocations now appear in the `:tools` map in `.psi/metrics.edn`; previously the map was always empty because the `psi/metrics` extension's `on-tool-call`/`on-tool-result` handlers were never fired on the interactive tool execution path.

## [0.1.2166] - 2026-06-01

### Added
- Provider retry/backoff history is now queryable through the live graph, including retry counts, retried provider request summaries, attempt error classification, delay source, resume timing, and rate-limit metadata.
- Claude Opus 4.8 (`claude-opus-4-8`) is now available in the Anthropic model catalog with adaptive thinking, native JSON Schema structured output, and mid-conversation system-message capability metadata.
- New `/speed` command and persisted `:speed-mode` config select a provider throughput tier (`:fast` maps to Anthropic `speed: "fast"` and OpenAI chat-completions `service_tier: "flex"`).
- New `/effort` command and persisted `:effort-override` config control provider reasoning effort independently of `/thinking`; Anthropic adaptive `:xhigh` now sends `"highest"` while OpenAI transports cap to provider-supported `"high"`.
- Extensions can inject mid-conversation system instructions through the `:inject-mid-system-message` extension API helper / `psi.extension/inject-mid-system-message`, gated by the queryable `:psi.agent-session/model-supports-mid-system-messages` capability.
- Extensions can now query runtime UI capability/action attrs under `:psi.ui/...`, including a discoverable make-visible action descriptor for Emacs and explicit unsupported/headless states for other UI modes.
- OpenAI OAuth-backed ChatGPT/Codex sessions now support provider-native structured outputs for streaming requests using Responses-style `text.format` JSON Schema, while remaining distinct from Chat Completions `response_format`.
- New `review-task-design` workflow: reviews `design.md` only for ambiguities and inconsistencies, loops until no actionable feedback remains. Invokable via `/delegate review-task-design`.
- New `create-task-plan` workflow: given a stable `design.md`, creates `plan.md` and `steps.md` for a Munera task in a single pass. Invokable via `/delegate create-task-plan`.
- `.md` single-step workflow bodies now support `{{input}}` and `{{original}}` template variables with automatic wiring — no frontmatter declaration needed. `{{input}}` expands to the workflow input text; `{{original}}` expands to the carried original request context.
- `.md` single-step workflows now support a `vars:` frontmatter key (EDN string) for declaring custom variable bindings with `:from :workflow-input` (plus optional `:path`) or `:from :workflow-original` sources.
- Unknown `{{varname}}` tokens in `.md` workflow bodies that are neither standard (`input`, `original`) nor declared in `vars:` produce a compile-time error at workflow load, catching typos and missing declarations before runtime.

### Changed
- `bb release --dry-run` now only dispatches the non-publishing GitHub `Release` workflow dry run for the current HEAD and latest release version label, with no local changelog stamping, version-resource edits, commits, tags, or pushes.
- `review-task-design` and `review-task-plan` now run ambiguity/inconsistency follow-up steps only when that reviewer reports `PASS_STATUS: ACTIONABLE_FEEDBACK`; `PASS_STATUS: REVIEW_COMPLETE` skips the no-op follow-up while still continuing the remaining review cycle. Plan-review follow-ups now write to `steps.md` instead of `design-steps.md`.
- `review-implementation` workflow renamed to `review-task-implementation`; the old name is no longer available. Update any saved `/delegate review-implementation` invocations to `/delegate review-task-implementation`.
- `review-task-until-clear` workflow renamed to `review-task-plan` and narrowed to `plan.md`/`steps.md` review only (design review is now handled by `review-task-design`). The old name is no longer available.
- `review-task-implementation` now includes a `review-task-docs` step that reviews user-facing documentation (`README.md`, `doc/`, changelog) as part of the implementation review chain.
- Workflow runs now automatically retain only the newest retained terminal runs per originating session, defaulting to `1` kept run via `[:config :completed-workflow-run-retention-count]`; older retained terminal runs are removed from workflow listing/introspection along with their linked workflow-owned child-session trees.

### Fixed
- `delegate` tool results that return plain text now remain visible at the caller tool-result boundary, so unknown workflow errors and empty delegate lists no longer look like silent successes.
- `delegate list` now shows active same-session delegated workflow runs from the delegate background-job visibility surface, keeps workflow status separate from delegate attempt status, and `delegate remove` cleans up active delegate background jobs before deleting their canonical run.
- AI provider request retries now happen at the prepared provider-request boundary for transient request/connection failures, preserving visible active backoff status, per-attempt provider telemetry, structured retry-exhausted/disabled/cancelled/non-retryable outcomes, retry-header delay handling, and streaming retry isolation without rerunning local tools.
- `review-task-design` and `review-task-plan` now use deterministic routing for their final clarity-status step, avoiding split-brain LLM judge decisions that could loop until iteration exhaustion after reviewers already reported completion.
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
[Unreleased]: https://github.com/hugoduncan/psi/compare/v0.1.2166...HEAD
[0.1.2166]: https://github.com/hugoduncan/psi/compare/v0.1.2137...v0.1.2166
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
