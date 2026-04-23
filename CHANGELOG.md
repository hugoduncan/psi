# Changelog

## 2026-04-23

- λ Δ Fixed `/work-on --base <branch>` branch tracking when creating a new worktree branch:
  - fresh `git worktree add -b` creation now uses `--no-track`
  - requested base refs such as `origin/master` still determine the new branch start point
  - newly created `/work-on` branches no longer auto-track the selected base branch
- ✓ Verification:
  - `clojure -M:test --focus psi.history.git-test --focus extensions.work-on-test`

## 2026-04-19

- λ Δ Added direct project nREPL support as a first-class worktree-bound runtime capability:
  - added managed project nREPL lifecycle with canonical worktree targeting
  - supports both psi-started acquisition via configured command vector and attach acquisition via explicit endpoint or `.nrepl-port`
  - establishes one managed nREPL client session per worktree instance in the first slice
  - added single-flight eval and interrupt against the active eval op id
  - exposed project nREPL projection state through EQL, including lifecycle, endpoint, capability flags, last-eval, and last-interrupt
  - added shared `/project-repl` command surface for status/start/attach/stop/eval/interrupt
  - documented project nREPL usage and clarified its distinction from psi runtime nREPL
- ✓ Verification:
  - targeted config/runtime/started/client/attach/eval/resolver/command/observability tests for the project nREPL slices

## 2026-04-18

- λ Δ Fixed extension slash-command session targeting after `/new`:
  - extension command handlers now execute with the active dispatch session bound as the implicit extension session
  - `make-extension-runtime-fns` now prefers the dynamically active extension session for implicit `:query` / `:mutate` calls while preserving explicit `:query-session` / `:mutate-session` targeting
  - this fixes cases where extension commands such as `/work-on` appeared to silently do nothing from a fresh `/new` session because they were still operating on the original extension-load session
- ✓ Verification:
  - `clojure -M:test --focus psi.agent-session.extension-targeting-runtime-test`
  - `clojure -M:test --focus extensions.work-on-test`

## 2026-04-13

- λ Δ Fixed child-session runtime allocation for extension-driven agent execution:
  - `psi.extension/create-child-session` now allocates real child runtime handles (`agent-ctx` + started session statechart) instead of leaving child sessions with placeholder statechart ids
  - fixed `agent` / `agent-chain` sub-agent execution path so child sessions remain runnable even when the parent session is busy or streaming
  - added regression coverage for child-session mutation creation and child-session execution while the parent session is `:streaming`
- ✓ Verification:
  - targeted `psi.agent-session.child-session-mutation-test`
  - targeted `extensions.agent-test`
  - targeted `extensions.agent-chain-test`

## 2026-04-09

- λ Δ Hardened prompt-completion projections against keyword sentinel values:
  - footer projection now treats non-string branch/session/provider values as absent instead of seqing them
  - footer status projection now ignores non-collection `:psi.ui/statuses` values (for example unresolved keyword sentinels)
  - session-summary projection now treats non-string model ids/providers as absent and ignores non-collection pending-message queues
  - journal reads now ignore non-seq journal values so prompt completion does not crash when deriving the last assistant message
  - added regression coverage for keyword-sentinel footer/session-summary inputs and RPC prompt completion so turns no longer fail with `Don't know how to create ISeq from: clojure.lang.Keyword`
- ✓ Verification:
  - targeted `psi.app-runtime.footer-test`
  - targeted `psi.app-runtime.session-summary-test`
  - targeted `psi.agent-session.persistence-test`
  - targeted `psi.rpc-test` regression covering prompt completion + `footer/updated`

## 2026-04-08

- λ Δ Converged more shared-session prompt entrypoints onto the dispatch-visible prompt lifecycle:
  - app-runtime CLI and TUI prompt submission now use the shared prompt lifecycle instead of direct `run-agent-loop-in!`
  - extension run-fn prompt submission now uses the shared prompt lifecycle
  - startup prompts now use the shared prompt lifecycle on the default runtime path while retaining injected `:run-loop-fn` compatibility
  - `psi.extension/run-agent-loop-in-session` now routes through `prompt-in!` instead of direct executor entry
  - prepared-request introspection now exposes explicit prompt layers for base system prompt, developer metadata layer, and prompt contributions
  - intentionally isolated workflow/ephemeral runtimes remain owned by their isolated workflow runtimes by decision, rather than being forced into shared-session lifecycle semantics
- ✓ Verification:
  - changed Clojure files pass `cljfmt`
  - changed Clojure files pass `clj-kondo`

## 2026-04-05

- λ Δ Live session displays now project a derived session display name without persisting a real session rename:
  - added derived `:psi.agent-session/session-display-name` from explicit session name or compact last user message
  - TUI footer now shows the derived display name for the active live session
  - TUI `/tree` live session rows now prefer the derived/display-name field too
  - RPC footer/session update payloads now include the derived display name for live frontend rendering
  - Emacs live session labels now prefer `display-name` / `session-display-name` before falling back to persisted name or session id prefix
  - persisted resume/session lists remain unchanged; this only affects live session display surfaces
- ✓ Verification:
  - targeted unit tests for message text helpers, TUI footer, and RPC payload projection

## 2026-04-03

- λ Δ Made tool execution boundary dispatch-owned end-to-end:
  - added explicit dispatch-owned tool phases:
    - `:session/tool-execute-prepared`
    - `:session/tool-record-result`
  - `:session/tool-run` now composes those phases instead of bouncing back through a runtime `:tool-run` effect callback
  - parallel tool batches now execute through dispatch-owned execute phases and record final `toolResult` messages through dispatch-owned record phases
  - deterministic final tool result recording order remains preserved even when execution completes out of order
  - removed `:runtime/tool-run` effect indirection and corresponding ctx callback wiring
- ✓ Verification:
  - `bb clojure:test:unit --focus psi.agent-session.tool-execution-test --focus psi.agent-session.dispatch-test`

## 2026-03-31

- λ Δ Increased default streaming stall timeouts from 120s to 600s (10 minutes):
  - backend LLM idle timeout default is now `600000` ms
  - session default config now carries `:llm-stream-idle-timeout-ms 600000`
  - Emacs watchdog default `psi-emacs-stream-timeout-seconds` is now `600`
  - docs updated for CLI, configuration, and Emacs UI timeout defaults

- λ Δ Removed RPC dynamic request session routing:
  - deleted `psi.rpc.transport/*request-session-id*`
  - request handler now resolves target `session-id` once and passes it explicitly through RPC handlers
  - RPC event payload helpers no longer depend on ambient request session state
  - handshake bootstrap context now reuses canonical `context/updated` payload shaping
  - removed `psi.rpc.events/focused-session-id`
- ✓ Verification:
  - targeted RPC namespace tests: 46 tests / 408 assertions / 0 failures / 0 errors

## 2026-03-28

- λ Δ Completed the Emacs UI region-identity migration for transcript/projection/input tracking:
  - projection identity is now region-registry-backed; `projection-range` compatibility state was removed
  - assistant/thinking range fields are now explicit cache-only compatibility markers over region identity
  - input separator marker is now explicit cache-only compatibility state over region identity
  - added regression coverage for assistant-span and tool-row recovery when legacy marker caches are missing
  - blocked empty non-slash compose sends locally to avoid backend `request/invalid-params` noise
- ✓ Verification:
  - `bb emacs:test` (349 tests, 0 unexpected)

## 2026-03-26

- λ Δ Removed `ui-state-view-in` compatibility anomaly from `agent-session` and moved extension UI state writes onto dispatch-owned events.
- λ Δ Added dispatch UI mutation handlers for widgets, widget specs, status, notifications, dialog resolution/cancel, renderer registration, and tools-expanded toggle.
- λ Δ Routed UI mutation callsites through dispatch:
  - Pathom UI mutations (`psi.ui/set-widget-spec`, `psi.ui/clear-widget-spec`)
  - RPC dialog resolve/cancel accessors
  - extension runtime `:ui` context write methods
- λ Δ Added pure(ish) ui-state reducers in `components/ui-state/src/psi/ui/state.clj` and switched dispatch handlers to reducer-based updates against canonical `[:ui :extension-ui]` state.
- λ Δ Executed Phase 4A: TUI/extension/rpc integration now uses canonical `:ui-state` path atom-view (`ss/atom-view-in ctx (ss/state-path :ui-state)`) instead of `ss/ui-state-view-in`.
- ✓ Verification: `bb clojure:test:unit` (935 tests, 5582 assertions, 0 failures).

## 2026-03-22

- λ Δ Sharpened tool-history resolver docs so canonical consumers default to lifecycle read models:
  - Marked `:psi.agent-session/tool-call-history` and `:psi.agent-session/tool-call-history-count` as transcript-derived compatibility projections.
  - Updated resolver guidance to prefer `:psi.agent-session/tool-lifecycle-summaries` and `:psi.agent-session/executed-tool-count` for canonical executed-tool consumers.

- λ Δ Aligned `plan_state_learning` with the shared workflow display convention:
  - Added `:psl/display` projection using the shared display-map shape.
  - Added `/psl` command listing active PSL workflows through `extensions.workflow-display/text-lines`.
  - Added PSL widget (`⊕ PSL`) with workflow display lines, ui-type-aware placement, and refresh on create/complete/error.
  - Documented the PSL public display surface, `/psl`, and widget in `doc/extensions.md`.
  - Added focused extension coverage for the PSL public display projection, `/psl` list, widget registration, placement, and display line rendering.
  - Verification: `bb clojure:test:extensions`

## 2026-03-18

- λ Δ Anthropic OAuth re-auth flow was stabilized:
  - Switched default Anthropic OAuth app config to current Claude Code values (`client_id a473d7bb-17ac-43a7-abc0-a1343d7c2805`, scopes `user:inference user:file_upload`).
  - Added env overrides for Anthropic OAuth config (`PSI_ANTHROPIC_OAUTH_*`) and persisted login metadata so refresh uses the same app config that issued the token.
  - Added refresh fallback for legacy credentials (older client-id) to avoid forced relogin breakage.
  - Improved auth-code parsing to accept `code=...#state=...` and made state-mismatch guidance explicit.
- λ Δ Anthropic 400 diagnostics were de-noised by removing the temporary Sonnet/Opus-specific OAuth hint.
- ✓ Smoke check:
  - `claude-sonnet-4-6` and `claude-opus-4-6` both returned 200 via `/v1/messages` with the refreshed OAuth token.

## 2026-03-17

- λ Δ Unified assistant content text extraction across runtime + RPC + TUI for both canonical block vectors and structured content maps:
  - Added `psi.agent-session.message-text` helpers and wired them into:
    - `components/agent-session/src/psi/agent_session/main.clj`
    - `components/agent-session/src/psi/agent_session/rpc.clj`
    - `components/tui/src/psi/tui/app.clj`
  - Added focused coverage in `components/agent-session/test/psi/agent_session/message_text_test.clj`.

- λ Δ Emacs transcript handling now avoids blank `ψ:` finalize rows and surfaces top-level assistant errors:
  - `assistant/message` with empty content now falls back to `:error-message` when present.
  - Truly empty finalizations no longer clobber streamed text or inject blank assistant lines.
  - Replay path now also renders assistant top-level error messages.
  - Updated Emacs frontend tests; the large `psi-test.el` suite was later split into focused files including `psi-buffer-lifecycle-test.el`, `psi-dispatch-test.el`, `psi-streaming-transcript-test.el`, `psi-tool-output-mode-test.el`, `psi-extension-ui-test.el`, `psi-capf-test.el`, and `psi-session-tree-test.el`.

- λ Δ Added RPC transport tracing configuration with both CLI and graph control:
  - New CLI flag: `--rpc-trace-file <path>` (rpc-edn mode).
  - RPC transport now emits inbound/outbound trace payloads (`:dir`, `:raw`, parsed `:frame`, optional `:parse-error`).
  - Runtime trace config is now queryable/mutable via graph:
    - resolver attrs: `:psi.agent-session/rpc-trace-enabled`, `:psi.agent-session/rpc-trace-file`
    - mutation: `psi.extension/set-rpc-trace`
  - Runtime state path added: `:rpc-trace` (`[:runtime :rpc-trace]`).
  - CLI docs updated in `doc/cli.md`.

- λ Δ Improved validation failure diagnostics and fixed canonical user-content normalization:
  - `psi.ai.schemas/validate!` now includes failing path, error type, compact bad-value snippet, and a targeted hint for legacy canonical `{:type ...}` blocks.
  - Canonical user text blocks are now normalized to schema-valid `:kind` content in `psi.ai.conversation/add-user-message`.
  - OpenAI and Anthropic provider user-message transforms now handle structured `{:kind :structured :blocks [...]}` content consistently.
  - This removes the opaque startup/runtime error shape: `Validation failed: {:messages [{:content ["invalid dispatch value"]}]}`.

- λ Δ Anthropic request/transport error handling is now more actionable and resilient:
  - `build-request` now fails fast on missing Anthropic API key with explicit guidance.
  - HTTP non-2xx responses are now handled via `:throw-exceptions false` path so provider error bodies are parsed when available.
  - Added preflight Anthropic request-body validation (Malli, OpenAPI-derived request/message/tool unions) with path-aware diagnostics to catch malformed payloads before transport.
  - Anthropic system prompt shaping now prefers plain string form when cache controls are absent (block form remains for prompt-caching).
  - 400 responses without body now emit actionable fallback text instead of bare `Error (status 400)`.
  - For status 400, provider now applies a one-shot compatibility retry by stripping optional request features that commonly trip silent 400s:
    - prompt-caching beta + `cache_control` fields
    - interleaved-thinking beta + `thinking` request field
    - all remaining `anthropic-beta` values (final compatibility fallback for non-OAuth auth flows)
  - OAuth Anthropic initial request headers include `claude-code-20250219`, `oauth-2025-04-20`, `context-management-2025-06-27`, and `prompt-caching-scope-2026-01-05`; OAuth retries keep required OAuth betas.
  - Generic Anthropic 400 diagnostics now include compact request context (`model`, beta header, message/tool counts, auth mode) and an explicit OAuth hint when bearer auth is in use.
  - If compatibility retry succeeds, turn proceeds normally; if it still fails, final error remains surfaced with request diagnostics.

- ✓ Verification:
  - `clojure -M:test --focus psi.agent-session.main-test/rpc-trace-file-from-args-test --focus psi.agent-session.main-test/run-rpc-session-enables-rpc-trace-config-test --focus psi.agent-session.rpc-test/run-stdio-loop-trace-fn-captures-inbound-and-outbound-test --focus psi.agent-session.core-test/rpc-trace-mutation-and-resolver-test --focus psi.agent-session.message-text-test/content-text-parts-supports-canonical-and-structured-shapes-test --focus psi.agent-session.message-text-test/content-display-text-includes-error-blocks-test`
  - `bb emacs:test`
  - `bb emacs:e2e`

## 2026-03-16

- λ Δ Added Anthropic prompt caching wiring:
  - session cache breakpoint policy (`:system`, `:tools`) now projects through executor-built conversations into Anthropic `cache_control` request fields.
  - conversations now support `:system-prompt-blocks` so provider request shaping can attach prompt metadata without losing the flat `:system-prompt` view.
  - Anthropic request building now prefers block-form system prompts when present and emits `cache_control` only for supported directives (`{:type :ephemeral}`).
- λ Δ Fixed executor conversation reconstruction to omit `:cache-control` when unset, avoiding invalid `nil` values in schema-validated conversation shapes.
- ✓ Verification:
  - `bb clojure:test:unit`
  - `bb test`

- λ Δ Emacs slash-command routing is now run-state independent:
  - Slash-prefixed compose input now routes to backend `command` in both idle and streaming states.
  - Non-slash streaming input still routes to `prompt_while_streaming` with steer/queue behavior.
  - Emacs slash dispatch helpers/tests/docs were renamed away from `idle-slash-*` vocabulary.
  - `/tree` keeps its backend fallback path when no local context snapshot exists.
  - Verification:
    - `allium check spec/emacs-frontend.allium spec/prompt-slash-commands.allium`
    - `bb emacs:test`

- λ Δ Renamed the worktree completion command from `/work-merge` to `/work-done` and removed `/work-merge`.
- λ Δ `/work-done` now preserves linear history by checking fast-forwardability onto the cached default branch, auto-rebasing via a forked sync agent when needed, then fast-forward merging and cleaning up the worktree/branch.
- λ Δ Added default-branch query surfaces:
  - history resolver `:git.branch/default-branch`
  - direct root attr `:git.branch/default-branch`
- λ Δ Synced worktree docs/spec/tests to the `/work-done` workflow.
- λ Δ `/work-on` worktree placement is now derived from the relationship between the main checkout path and the current worktree path.
  - When the current worktree is an immediate child of the main checkout, `/work-on` places the new worktree inside the main checkout directory.
  - Otherwise, `/work-on` places the new worktree in the parent directory of the main checkout.
  - This fixes the nested-linked layout where the main checkout is the project directory and linked worktrees live under it.
- λ Δ Removed redundant `:psi.agent-session/git-*` worktree/default-branch bridge attrs; runtime consumers now query `:git.worktree/*` and `:git.branch/default-branch` directly from session root.
- ✓ Verification:
  - `bb clojure:test:extensions --focus extensions.work-on-test`
  - `bb clojure:test:unit`
  - `bb lint`

## 2026-03-13

- λ Δ Added tmux-backed TUI integration harness baseline:
  - Added new spec: `spec/tui-tmux-integration-harness.allium`.
    - Captures harness lifecycle (preflight/start/send/wait/assert/cleanup).
    - Includes one baseline scenario: launch TUI → `/help` marker assertion → `/quit` exit assertion.
  - Added integration test: `components/tui/test/psi/tui/tmux_integration_harness_test.clj`.
    - `^:integration` test uses detached tmux session with unique session names.
    - Readiness assertion waits for prompt marker (`刀:`/`Type a message`).
    - Help assertion checks stable marker: `(anything else is sent to the agent)`.
    - Quit assertion checks pane process transition away from `java`.
    - Always performs best-effort tmux session cleanup and captures pane snapshot on failure.
  - Extracted reusable tmux harness helpers to:
    - `components/tui/test/psi/tui/test_harness/tmux.clj`
    - includes shared lifecycle helpers (`start-session!`, `send-line!`, `capture-pane`,
      waits/assert helpers) and a reusable baseline scenario runner.
  - Verification:
    - `clj-kondo --lint components/tui/test/psi/tui/tmux_integration_harness_test.clj`
    - `clojure -M:test --focus psi.tui.tmux-integration-harness-test --skip-meta foo`


- λ Δ TUI live multi-session surface (`/tree`) landed:
  - Added `/tree` command to central dispatcher with two modes:
    - `/tree` opens a session picker.
    - `/tree <session-id|prefix>` performs direct session switch.
  - Runtime gating added via `:supports-session-tree?`:
    - TUI enables `/tree`.
    - Console/RPC return deterministic guidance text.
  - TUI selector now supports `:session-selector-mode` (`:resume|:tree`):
    - `:resume` uses persisted session listing.
    - `:tree` uses live host snapshot attrs (`:psi.agent-session/host-active-session-id`, `:psi.agent-session/host-sessions`).
  - Added TUI switch callback path (`switch-session-fn!`) wired to `session/ensure-session-loaded-in!` + transcript/tool rehydrate.
  - Added/updated tests across commands + TUI + runtime + RPC for `/tree` behavior and gating.
  - Verification:
    - `clojure -M:test --focus psi.agent-session.commands-test --focus psi.tui.app-test --focus psi.agent-session.main-test --focus psi.agent-session.rpc-test`
    - 166 tests, 736 assertions, 0 failures.

- λ Δ TUI `/tree` hierarchy polish shipped:
  - Host session picker now orders rows by parent/child tree structure (not raw host insertion order).
  - Tree rows now use explicit glyphs: root marker `●`, branch connectors `├─` / `└─`, and carry-through `│` for nested siblings.
  - Right-side status cells are now column-aligned (`[active] [stream]`) to keep mixed-state rows visually stable.
  - Added focused TUI regression coverage to lock hierarchy order, connector rendering, and status-column alignment.
  - Verification:
    - `clojure -M:test --focus psi.tui.app-test`
    - 77 tests, 210 assertions, 0 failures.

- λ Δ Multi-session route-lock isolation hardened for exclusive session lifecycle ops:
  - Added explicit exclusive route-lock op class in RPC: `new_session`, `switch_session`, `fork`.
  - When `:enforce-session-route-lock?` is enabled and a prompt is in-flight, these lifecycle ops now fail fast with canonical `request/session-routing-conflict` (including inflight/target session ids), even for same-session targets.
  - Refactored route-lock path into reusable helpers (`valid-target-session-id!`, `maybe-assert-route-lock!`) and applied guard at dispatch boundary for exclusive ops.
  - Added RPC regression test: `exclusive ops are rejected while prompt is in-flight when lock enforcement is enabled`.
  - Verification:
    - `clojure -M:test --focus psi.agent-session.rpc-test` (35 tests, 336 assertions, 0 failures)
    - `clojure -M:test --focus psi.agent-session.core-test/send-workflow-event-track-background-job-gated-test --focus psi.agent-session.rpc-test/rpc-prompt-passes-resolved-api-key-to-agent-loop-test` (2 tests, 7 assertions, 0 failures)

- λ Δ Multi-session persistence locking + session decision convergence:
  - Updated session specs with elicited decisions:
    - `spec/session-core.allium`: fork default prompt inheritance is explicit.
    - `spec/session-forking.allium`: soft isolation, merge-back-as-separate-capability, optional fork budgets.
    - `spec/session-persistence.allium`: cross-process file locking required.
  - Implemented cross-process locking in session persistence write path (`components/agent-session/src/psi/agent_session/persistence.clj`):
    - added exclusive sidecar lock strategy (`<session-file>.lock`) with bounded retry for all writes (`write-header!`, `flush-journal!`, append path).
    - lock acquisition failure now throws explicit `ExceptionInfo` with lock/session context.
  - Added persistence regression coverage (`components/agent-session/test/psi/agent_session/persistence_test.clj`):
    - `session-file-locking-test` verifies write fails while lock is externally held.
  - Verification:
    - `clojure -M:test --focus psi.agent-session.persistence-test` (12 tests, 75 assertions, 0 failures)
    - `clojure -M:test --focus psi.agent-session.core-test` (32 tests, 272 assertions, 0 failures)

## 2026-03-12

- λ Δ Added optional agent session-forking capability:
  - `agent` tool `action=create` now accepts `fork_session` (boolean, default `false`)
  - when `fork_session=true`, agent starts with invoking session conversation history (`user`/`assistant`/`toolResult` messages)
  - validation added: `fork_session` must be boolean and is rejected for non-`create` actions
- Δ Added slash command fork flag support: `/sub [--fork|-f] [@agent] <task>`
- Δ Updated agent extension spec (`spec/agent-extension.allium`) with fork-session args/rules and surface guidance.
- Δ Updated `META.md` to record agent optional fork inheritance in session semantics.

## 2026-03-14

- λ Δ Worktree session follow-up converged:
  - `/work-on` now creates a distinct host peer session instead of rebinding the active session in place
  - `/work-merge` now auto-switches back to an existing main-worktree session when present, or creates one when absent
- Δ Added first-class extension session lifecycle mutations:
  - `psi.extension/create-session`
  - `psi.extension/switch-session`
- Δ Extension API now exposes:
  - `createSession(options)`
  - `switchSession(session_id)`
- Δ Migrated `extensions/src/extensions/work_on.clj` off direct session-core var resolution to the extension session lifecycle surface.
- Δ Synced Allium surfaces for the new session lifecycle API:
  - `spec/session-core.allium`
  - `spec/extension-system.allium`
  - `spec/work-on-extension.allium`
- Δ Added focused coverage for extension API session lifecycle helpers and work-on session switching/creation paths.
- ✓ Verification: `clojure -M:test --focus psi.agent-session.extensions-test --focus extensions.work-on-test --focus psi.agent-session.core-test` → 64 tests, 405 assertions, 0 failures.

## 2026-03-06

- λ Δ Added `spec/git-worktrees.allium` and Step 11a acceptance checklist in the active planning tracker.
- λ Δ Implemented git worktree query surface:
  - history attrs: `:git.worktree/list`, `:git.worktree/current`, `:git.worktree/count`, `:git.worktree/inside-repo?`
  - direct session-root attrs: `:git.worktree/list`, `:git.worktree/current`, `:git.worktree/count`
- Δ Added `/worktree` slash command and `/status` worktree identity surfacing.
- Δ Added graph/introspection coverage tests for worktree resolvers/attrs.
- Δ Added worktree parse-failure telemetry marker (`git.worktree.parse_failed`) with degradation-to-empty behavior and tests.
- Δ Hardened tests to avoid writing repo `.psi/project.edn` by using temp cwd in agent-session/introspection test contexts.
