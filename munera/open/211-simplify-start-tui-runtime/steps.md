# Steps — Simplify `psi.app-runtime/start-tui-runtime!`

## Slice 0 — Orientation and safety-net assessment

- [x] Read `components/app-runtime/src/psi/app_runtime.clj` around `start-tui-runtime!` and note each observable responsibility in the current call order.
- [x] Read `components/app-runtime/test/psi/app_runtime_test.clj` and list the tests that directly or transitively exercise `start-tui-runtime!`.
- [x] Map existing tests to Phase 0 behaviour areas: TUI provider install/clear, exceptional cleanup, startup/session-root options, persisted current-session file, command journaling, `/new` focus targeting, callback wiring, and nullable deterministic execution mode.
- [x] Record in `implementation.md` whether existing coverage is sufficient or which characterization gaps must be filled before production refactoring.

## Slice 1 — Characterization net, if needed

- [x] Add minimal characterization test(s) for any Phase 0 gap identified in Slice 0, asserting observable state or outputs rather than interactions.
- [x] Run the focused app-runtime test namespace with the unmodified production code and confirm all existing and new characterization tests pass.
- [x] Record the green pre-refactor characterization command and result in `implementation.md`.

## Slice 2 — Local lifecycle/data-shape refactor

- [x] Identify the minimal coherent helper or local data-shape boundaries to extract from `start-tui-runtime!` without changing architecture or broadening the blast radius.
- [x] Refactor runtime bootstrap bindings so `ctx`, `oauth-ctx`, `cwd`, `startup-rehydrate`, `session-id`, and `tui-focus*` remain explicit and ordered.
- [x] Refactor TUI provider installation and frontend startup so `ui-capabilities/clear-provider!` remains protected by a direct `finally` around `tui-start-fn!`.
- [x] Refactor session-navigation and command option assembly while preserving `/new` behaviour that reads `@tui-focus*` and ignores the source callback argument.
- [x] Refactor TUI wiring dependency and option assembly while preserving all keys consumed by `tui-wiring/build-tui-opts` and the TUI start function.
- [x] Run `clj-paren-repair components/app-runtime/src/psi/app_runtime.clj` after source edits.

## Slice 3 — Behaviour verification

- [x] Run the focused app-runtime tests after the refactor and fix any behaviour regression without weakening expectations.
- [x] Run `bb clojure:test:unit` or the smallest project-approved affected unit suite that includes app-runtime coverage.
- [x] Run `bb lint` and fix any lint introduced by the refactor.
- [x] Run `bb commit-check:file-lengths` and fix any file-length regression.
- [x] Record verification commands and outcomes in `implementation.md`.

## Slice 4 — Burden and architecture gates

- [x] Run `bb gordian local --json > /tmp/after-local.json` from the worktree root.
- [x] Compare `/tmp/after-local.json` with `munera/open/211-simplify-start-tui-runtime/before-local.json` using the A2 logical-key reconciliation rule and confirm metric-derived touched-set total burden strictly decreases.
- [x] Confirm A4 target burden decreases by re-identifying the after target with logical key `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)`.
- [x] Run `bb gordian gate --baseline munera/open/211-simplify-start-tui-runtime/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0` from the worktree root and confirm exit code `0`.
- [x] Record the A2, A3, and A4 results in `implementation.md`.

## Slice 5 — Final review and blast-radius documentation

- [x] Review `git diff` and ensure changes are limited to `start-tui-runtime!`, minimal surrounding helpers, tests added for characterization, and task artifacts.
- [x] Document in `implementation.md` why every touched helper outside the original target body is within the design's blast radius.
- [x] Ensure `steps.md` is fully checked for completed implementation work and set the task implementation status in `implementation.md`.
- [x] Commit the implementation and task artifact updates with a symbolized Munera commit message.

## Review follow-ups

- [x] TT1 Add a public-path characterization test for `start-tui-runtime!` with nullable deterministic execution mode enabled, exercising the captured TUI `run-agent-fn` without redefining the turn executor and asserting the assistant response echoes the user text.
- [x] TT2 Add a public-path characterization test for `start-tui-runtime!` forwarding `bootstrap-runtime-session!` `:startup-rehydrate` into TUI opts as `:initial-messages`, `:initial-tool-calls`, and `:initial-tool-order`, while continuing to assert the isolated session-root `:current-session-file` surface.
- [x] TT3 Add a public-path characterization test for `start-tui-runtime!` TUI navigation/action callback wiring: capture the TUI opts, lock `:frontend-action-handler-fn!`, `:resume-fn!`, `:switch-session-fn!`, and `:fork-session-fn!` as callable public opts, and exercise at least one navigation callback or frontend session action through those opts to prove focus updates and a `:context-updated` event is emitted without stubbing the navigation helpers.
- [x] TT4 Add a public-path characterization test for `start-tui-runtime!` startup `:cwd` forwarding: drive startup with an isolated non-default cwd and assert the resolved cwd reaches both `bootstrap-runtime-session!` opts and the TUI opts map as `:cwd`, so dropping either `startup-opts :cwd` or the bootstrap/TUI forwarding path fails.
- [x] TT5 Add a public-path characterization test for `start-tui-runtime!` memory runtime option forwarding: start TUI with distinctive `memory-runtime-opts` and assert those options reach the real bootstrap/memory sync path (for example via nullable `memory-runtime/sync-memory-layer!` output tracking), so dropping `:memory-runtime-opts memory-runtime-opts` from the TUI bootstrap call fails.
- [x] TT6 Add a public-path characterization test for `start-tui-runtime!` runtime configuration forwarding: start TUI with a distinctive `session-config` and `startup-opts :thinking-level-override`, then assert the created TUI runtime context/session reflects both values, so dropping `:session-config session-config` or `:thinking-level-override (:thinking-level-override startup-opts)` from `create-runtime-session-context` fails through the public startup path.
- [x] TT7 Add a public-path characterization test for `start-tui-runtime!` TUI active-input callback wiring: capture the TUI opts, exercise the real `:on-interrupt-fn!` and `:on-queue-input-fn!` against focused session state (streaming and/or queued input as needed), and assert observable returned messages, queued text, or session state so omitting or miswiring either callback fails without stubbing `tui-wiring` or session callback helpers.
- [x] TT8 Add a public-path characterization test for `start-tui-runtime!` TUI UI projection, dispatch, footer, and session-selector option wiring: capture the TUI opts, exercise the real `:ui-read-fn`, `:ui-dispatch-fn`, `:footer-model-fn`, and `:session-selector-fn` against real session/UI state, and assert observable snapshots/actions/footer or selector data so omitting or miswiring those callbacks fails without stubbing `tui-wiring` or lower-level option assembly.
- [x] TT9 Add a public-path characterization test for `start-tui-runtime!` TUI pending-login handoff: start TUI with a nullable OAuth context/provider, drive `/login` through the captured `:dispatch-fn`, assert pending login state/fall-through behaviour, then submit an auth code through captured `run-agent-fn` and assert queued success output, cleared pending-login state, and stored/captured OAuth credential/code so removing or miswiring the `:pending-login` bridge between dispatch and run-agent fails.
- [x] TT10 Add a public-path characterization test for `start-tui-runtime!` frontend action and remaining navigation callback semantics: drive captured `:frontend-action-handler-fn!` with a real `:select-session` action result and/or direct `:fork-session-fn!`/`:resume-fn!` inputs against real session state, then assert focus, returned rehydrate state, emitted `:context-updated` events, and session/model state so a callable-but-miswired handler, fork, or resume callback fails rather than only `:switch-session-fn!` being covered.
- [x] TT11 Add a public-path characterization test for the non-streaming branch of `start-tui-runtime!` `:on-queue-input-fn!`: exercise the captured callback while the focused session is idle/non-streaming and assert the queued follow-up message text plus `"Queued follow-up message."`, so a regression that always uses the streaming steering branch remains covered through public TUI startup opts.
- [x] TT12 Add a public-path characterization test for `start-tui-runtime!` TUI command option assembly forwarding `:supports-session-tree? true`: drive captured `:dispatch-fn` with `/tree` through real public startup opts and assert it returns the TUI tree-open/tree navigation surface rather than the CLI-only unsupported message, so dropping or falseing the TUI session-tree command option fails.
- [x] TT13 Add a public-path characterization test for initial context session tree widget forwarding: create enough real context/session state for `tui-session-nav/current-context-widget` to return a visible widget during `start-tui-runtime!`, capture TUI opts, and assert `:initial-context-session-tree-widget` contains the expected session-tree lines/actions so omitting or miswiring `:current-context-widget`/`:initial-context-session-tree-widget` fails through public startup opts.
- [x] IR1 Reconcile the TT11 production behaviour change with task 211 scope and final acceptance: either justify/update the task artifacts for the `tui_wiring.clj` idle follow-up fix as an allowed user-visible defect fix in the local blast radius (or move/revert/split it), then rerun A2/A3/A4 Gordian acceptance from current HEAD and record the final results.
- [x] IR2 Tighten TUI provider lifetime protection: ensure `ui-capabilities/install-provider!` happens only after TUI callbacks/options are built, or wrap the whole post-install region in `try`/`finally`, so a synchronous failure during callback/option assembly cannot leave an attached TUI provider; add/adjust focused coverage for an option-assembly failure and re-run affected tests.
- [x] TT14 Add a public-path characterization test for `start-tui-runtime!` frontend model-selection action wiring: drive captured `:frontend-action-handler-fn!` with a real `:select-model` submitted action result, assert the runtime session model changes and the returned success/error text is observable, so dropping or miswiring `:resolve-model-by-provider+id` in TUI wiring fails through public startup rather than only lower-level helper tests.
- [x] TT15 Add a public-path characterization test for TUI frontend-action command dispatch through `start-tui-runtime!`: drive captured `:dispatch-fn` with `/model`, `/thinking`, and `/resume` (with at least one persisted/resumable session as needed), assert each returns the expected `:frontend-action` with the corresponding `:ui/action-id` and meaningful items/options, so replacing `tui-frontend-actions/command-result` with plain command dispatch or dropping these TUI-only command branches fails through public startup.
