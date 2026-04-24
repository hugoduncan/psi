# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state
- Compatibility scaffold removal has advanced materially.
- Session directory semantics were tightened into an explicit invariant:
  - runtime sessions now require `:worktree-path`
  - runtime/tool/resolver/app-runtime code no longer falls back from session worktree to context `:cwd`
  - persisted session load/listing no longer falls back from legacy header `:cwd` to `:worktree-path`
  - canonical helper usage now goes through `session-worktree-path-in`
  - persisted headers now write `:worktree-path` as the authoritative session directory field
  - `SessionInfo` now projects `:cwd` from `:worktree-path` for compatibility instead of exposing legacy persisted `:cwd`
  - `:psi.session-info/cwd` query usage was removed from internal resolver/tests in favor of `:psi.session-info/worktree-path`
  - canonical public session attr `:psi.agent-session/worktree-path` now exists
  - legacy public alias `:psi.agent-session/cwd` has now been removed
  - internal command/footer/extension consumers and tests now query `:psi.agent-session/worktree-path`
- Adapter/UI compatibility cleanup now landed in Emacs:
  - removed footer `:stats-line` fallback parsing; footer now relies on canonical structured `:usage-parts` + `:model-text`
  - removed frontend session-tree label reconstruction; backend `:label` is now authoritative
  - removed `/tree` action payload compatibility forms; canonical action payloads are now used
  - removed targeted-event "missing session-id is ok" compatibility; session-targeted events now require canonical `:session-id` once session identity is known
  - removed remaining RPC payload camelCase/alternate key-shape fallbacks across Emacs event/projection/session-command handling
- Prompt-path compatibility cleanup now landed:
  - removed prompt-runtime timeout/abort sentinel compatibility handling; canonical internal sentinels only
  - removed prompt request runtime-model fallback scan of built-in `ai-models/all-models`; shared-session resolution now uses `model-registry` only
  - removed a few leftover prompt seam/test-hook comments and the `wait-fn` seam from prompt runtime waiting
  - moved `/skill:` and template invocation expansion canonically into request preparation
  - removed the remaining caller-local preview expansion path from app-runtime, RPC, and extension run-fn submission paths
  - prompt text memory recovery now runs from dispatch-owned prepared-request effects instead of caller-local pre-submit hooks
- Follow-on test cleanup landed so the full unit suite is green again (`1112 tests, 6425 assertions, 0 failures`):
  - extension API tests now match the richer list-services query shape and current explicit-session mutate behavior
  - git default-branch fallback test now stubs symbolic-ref + config lookup explicitly so `:fallback` is deterministic
- The adapter-convergence cleanup thread has now landed the remaining targeted ownership shifts for shared interactive semantics.
- New follow-on fix landed in Emacs tool rows: expanded tool output now renders on lines below the tool summary/status header instead of inline; tool body text now gets an explicit de-emphasized baseline face (`psi-emacs-tool-output-face`) so summary/status faces do not bleed into output; and a `ψ:` prefix overlay boundary bug was fixed so tool rows inserted at assistant/tool boundaries no longer inherit the assistant prefix face. Toggle rerenders still preserve adjacent row boundaries so rows no longer disappear.
- Tool schema convergence follow-on is now landed:
  - canonical tool definitions now live in `components/agent-session/src/psi/agent_session/tool_defs.clj`
  - built-in tools now use structured `:parameters` data instead of canonical `pr-str` strings
  - extension tool registration now normalizes tool defs at registration time
  - session state now stores canonical `:tool-defs`
  - provider conversation/tool projection now consumes canonical tool defs directly
  - agent-core tool schema now accepts structured parameters during migration
  - child-session creation/runtime paths now use `:tool-defs` instead of `:tool-schemas`
  - the `/new` regression caused by richer tool defs crossing into the stricter runtime boundary was fixed
- Focused failing-test cleanup after the convergence work also landed:
  - deferred extension prompt test now drives the session statechart explicitly instead of depending on prompt timing
  - interrupt dispatch test now drives streaming state explicitly instead of depending on prompt timing
  - service protocol mutation tests now match the current trace-opts arity
  - dispatch/LSP tests now account for injected `:dispatch-id`
  - full suite is green again (`1219 tests, 6903 assertions, 0 failures`)
- Recent completed convergence work now includes:
  - unified RPC session navigation emission through `psi.rpc.session.emit/emit-navigation-result!`
  - expanded explicit RPC session routing so more ops carry `session-id` through request handling instead of relying on adapter focus inference
  - extracted canonical context snapshot projection to `components/app-runtime/src/psi/app_runtime/context.clj`
  - extracted canonical context/session-tree public summary projection to `components/app-runtime/src/psi/app_runtime/context_summary.clj`
  - extracted canonical transcript reconstruction to `components/app-runtime/src/psi/app_runtime/messages.clj`
  - extracted canonical extension UI/status projection to `components/app-runtime/src/psi/app_runtime/projections.clj`
  - extracted canonical background-job summaries to `components/app-runtime/src/psi/app_runtime/background_jobs.clj`
  - extracted canonical background-job widget/status projections to `components/app-runtime/src/psi/app_runtime/background_job_widgets.clj`
  - installed runtime-owned background-job UI refresh via `components/app-runtime/src/psi/app_runtime/background_job_ui.clj`
  - extracted shared session-summary/header-diagnostics projection to `components/app-runtime/src/psi/app_runtime/session_summary.clj`
  - made `psi.rpc.events/context-updated-payload` delegate to app-runtime context + context-summary projections
  - made `psi.rpc.events/session-updated-payload` delegate to shared session-summary projection
  - made `psi.rpc.events/footer-updated-payload` expose structured footer stats parts (`:usage-parts`, `:model-text`) in addition to canonical lines
  - removed `app-runtime.navigation` dependence on `psi.rpc.session.message-source`
  - removed the redundant RPC message-source alias entirely
  - centralized frontend action result normalization in `app-runtime.ui_actions`
  - centralized selector submitted-value normalization in `app-runtime.ui_actions`
  - centralized model/thinking submitted-value normalization in `app-runtime.ui_actions`
  - centralized cancelled/failed frontend action result messaging in `app-runtime.ui_actions`
  - canonicalized frontend action names across adapters (`select-session`, `select-resume-session`, `select-model`, `select-thinking-level`)
  - canonicalized frontend action ids to match canonical action names
  - removed legacy action-name compatibility branches from app-runtime + Emacs
  - removed legacy payload duplication from `ui/frontend-action-requested`; `:ui/action` is now the canonical contract
  - made RPC delegate extension UI snapshots to app-runtime projections
  - made TUI consume canonical extension UI snapshots instead of raw ui-state
  - made Emacs preserve backend-owned widget/status ordering instead of re-sorting it locally
  - made Emacs footer alignment prefer structured backend footer semantics instead of reparsing `:stats-line` in the common path
  - made Emacs header model label and `/status` session-summary line consume backend-shared session summary fragments
  - made `context/updated` carry both the canonical context snapshot and the canonical backend-projected session-tree widget payload
  - removed the handshake bootstrap `context/updated` event path so handshake is transport-only again
  - removed obsolete handshake/tree-label compatibility branches that were superseded by the converged projections
  - converged capability-graph derivation on `psi.graph.analysis`; `psi.introspection.graph` is now a compatibility wrapper
  - started thinning the RPC command/frontend-action seam by moving picker selection side-effects into `psi.rpc.session.command-pickers`

## Prompt lifecycle convergence — current status
- The prepare → execute → record → finish scaffold is now fully wired end-to-end.
- `prompt-finish` drives terminal statechart completion: dispatches `:on-agent-done`, sends `:session/reset` to statechart, reconciles background jobs. Session returns to `:idle` with `is-streaming false`.
- Tests prove: submit → prepare → execute → record → finish → idle, including tool-use continuation paths.
- All planned dispatch handlers and runtime effects exist: `prompt-submit`, `prompt-prepare-request`, `prompt-record-response`, `prompt-continue`, `prompt-finish`.
- Request preparation now owns canonical prompt expansion metadata (`:prepared-request/input-expansion`) and dispatch-visible prompt memory recovery.
- Follow-on cleanup also updated local docs/comments so the old preview-expansion ownership story is no longer described as current behavior.
- Agent tool skill-prelude follow-on has now started landing:
  - `agent` tool accepts `:skill`
  - non-fork agent runs can seed child sessions with synthetic preloaded messages before execution
  - child-session creation now accepts preloaded messages + cache-breakpoint overrides
  - current prelude shape is synthetic user → assistant(skill body) → user(task) → assistant(ack)
  - child runtime initialization now preserves those preloaded messages in both runtime message state and the child journal seed

## Current work update
- Task 050 (TUI live operator-awareness parity) is now closed:
  - wired `footer-model-fn` closure from app-runtime into TUI, replacing the local `footer-data` + `footer-model-from-data` query path with a single code path
  - TUI footer now renders `session-activity-line` when multiple sessions are active (same format as Emacs)
  - proved background-job widget refresh cycle (snapshot atom swap → tick → visible change)
  - proved notification rendering lifecycle (appear → backdate → dismiss → gone)
  - proved frontend-action cancel feedback visibility (Escape → "Cancelled select-model." in transcript)
  - discovered and documented snapshot-before-dismiss tick ordering: dismiss mutates the atom but the current tick's snapshot was already read, so dismissed state is visible on the next tick
  - full suite green: 1330 unit tests, 10202 assertions; 141 extension tests, 560 assertions

- The workflow-loader convergence thread is now closed through munera tasks `029`, `030`, `031`, and `032`:
  - `029` unified workflow loading/delegation on `.psi/workflows/` + `workflow-loader`
  - `030` removed remaining post-029 legacy surface drift
  - `031` converged async delegation visibility on canonical background jobs
  - `032` structurally decomposed `extensions.workflow-loader` into:
    - `extensions.workflow-loader.text`
    - `extensions.workflow-loader.delivery`
    - `extensions.workflow-loader.orchestration`
    - a thinner top-level `extensions.workflow-loader`
  - focused workflow-loader tests stayed green throughout, and the full `:extensions` suite is now green after closure (`132 tests, 500 assertions, 0 failures`)
- 026 deterministic workflow runtime remains the main unresolved workflow thread beyond the closed loader/delegation work:
  - canonical workflow model, runtime, progression, attempts, statechart, resolvers, and `psi-tool` surfaces exist
  - `workflow_lifecycle_test.clj` proves representative `plan -> build -> review` completion and blocked/resume/new-attempt semantics
  - the old `agent-chain` discovery/config surface from `.psi/agents/agent-chain.edn` has been retired in favor of unified `.psi/workflows/*.md` loading through `workflow-loader`
  - existing extension workflow runtime in `workflows.clj` is separate and should not become the execution substrate for canonical deterministic workflow runs
  - pure chain compilation now exists in `workflow_agent_chain.clj`
  - runtime registration now exists in `workflow_agent_chain_runtime.clj`
  - compiled chain definitions preserve legacy prompt text as `:prompt-template` plus explicit bindings for workflow input, prior-step output, and original request
  - `psi-tool` workflow ops are focused on canonical workflow definitions/runs only (`list-definitions`, `create-run`, `execute-run`, `read-run`, `list-runs`, `resume-run`, `cancel-run`)
  - an execution bridge now exists in `workflow_execution.clj` for current-step attempt/session creation, prompt materialization, prompt submission, result-envelope recording, and step advancement
  - `execute-run!` now provides a first sequential loop for multi-step workflow execution to terminal/blocking status
  - blocked-status behavior and retry/resume behavior are explicitly proven in `workflow_execution_test.clj`
  - attempted `psi-tool` exposure for execution controls hit a namespace load cycle (`psi_tool -> workflow_execution -> prompt_control/core -> context/... -> psi_tool`)
  - next 026 slice should avoid forcing execution controls through the current `psi_tool` load graph without first breaking that cycle

## Suggested next step
- Active munera tasks are now:
  1. `munera/open/047-tui-feature-parity-with-emacs-ui/` (parent umbrella for TUI parity)
  2. `munera/open/021-emacs-session-tree-buffer-with-magit-sections/`
  3. `munera/open/001-post-wave-b-gordian-follow-on/`
  4. `munera/open/002-compatibility-scaffold-removal/`
  5. `munera/open/003-prompt-lifecycle-architectural-convergence/`
  6. `munera/open/004-lsp-integration-managed-services-post-tool-processing/`
  7. `munera/open/005-canonical-dispatch-pipeline-trace-observability/`
  8. `munera/open/006-agent-tool-skill-prelude-follow-on/`
- Highest-value next threads remain:
  1. **026 deterministic workflows**: break or route around the `psi-tool` execution-control load cycle cleanly
  2. **Prompt lifecycle / skill prelude**: refine cache-breakpoint shaping and decide whether prelude/source metadata should surface in introspection
  3. **Compatibility scaffold removal**: continue removing shared-session prompt-path seams and adapter/UI fallback payload compat
  4. **LSP**: decide whether debug-atom telemetry stays, and simplify overlapping live/debug tests
- Decision taken: keep extension/workflow-local ephemeral sessions owned by their isolated workflow runtimes.
- `008-model-selection-hierarchy` is now closed in `munera/closed/008-model-selection-hierarchy/`.
- Model selection hierarchy is now available as shared infrastructure and already adopted by auto-session-name; remaining work is downstream adoption/refinement rather than core resolver invention.
- Prompt/git-head-sync debugging outcome now established:
  - canonical prompt-turn sync ownership should live in `psi.agent-session.prompt-control/prompt-in!`
  - app-runtime and RPC prompt wrappers must not duplicate post-turn git-head sync once they route through `prompt-in!`
  - `psi.memory.runtime/sync-memory-layer!` must not mutate the per-cwd git-head baseline cache; only `maybe-sync-on-git-head-change!` should advance that baseline
  - live project-nREPL validation confirmed that after the fixes the app-runtime prompt path emits `git_commit_created` and reaches `extensions.commit-checks`
  - absence of visible commit-check follow-up can simply mean configured checks passed/skipped; it is no longer evidence that the event path failed
- Manifest activation shaping follow-on is now closed through task `036`:
  - `extension_runtime.clj` now uses small helpers for manifest install-plan enrichment, dependency-failure projection, finalization, startup summary projection, and reload-result merge shaping
  - startup bootstrap summary merge policy is now explicit in `app_runtime.clj` via `merge-startup-summary`
  - loader docstrings now preserve the live-registry invariant: failed file-backed and init-var-backed activation is rolled back immediately and does not remain registered
  - focused loader-seam tests now prove rollback for both failed file-backed and failed init-var-backed activation
  - targeted manifest activation coverage stayed green after shaping (`41 tests, 192 assertions, 0 failures` across registry/io/manifest/startup suites)

## Notes for future ψ
- `munera/plan.md` is now the main active-work tracker, with the active threads split into task directories under `munera/open/`.
- For auto-session-name, trust the journal-backed source-session read path over agent-core `message-history`; the latter was the wrong surface for rename inference.
- The explicit session-targeting extension API (`:query-session`, `:mutate-session`) now exists because delayed/scheduled extension handlers must not rely on ambient session scope.
- The current manual-vs-auto protection is intentionally extension-local and heuristic:
  - last auto-applied name is remembered in extension state
  - if current name diverges from that remembered value, auto overwrite is blocked
  - this is not yet first-class runtime metadata
- Background jobs should now be understood in three layers:
  - canonical summary text/maps in `app_runtime.background_jobs`
  - canonical widget/status projections in `app_runtime.background_job_widgets`
  - runtime projection into shared extension UI state in `app_runtime.background_job_ui`
- Context/session tree should now be understood in three layers:
  - canonical snapshot data in `app_runtime.context`
  - canonical public/session-tree summaries in `app_runtime.context_summary`
  - backend-projected session-tree widget payload carried on `context/updated`
- Header/status/footer ownership is now split as:
  - backend/app-runtime owns shared semantic fragments
  - adapters own only rendering/layout and transport/process/local-run-state concerns
