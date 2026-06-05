# Implementation Notes

2026-06-04 architecture-fit review: design fits the current app-runtime/TUI architecture. It keeps shared session/navigation/UI-domain semantics in `app-runtime`, treats TUI-specific work as callback/options wiring for the TUI entrypoint rather than terminal rendering, preserves the provider install/clear lifetime, and constrains any helper extraction to the target unit's local blast radius. It also respects the current partial-dispatch migration by requiring behaviour preservation instead of broad boundary movement. No new actionable architectural misfit found; no `design-steps.md` follow-up was created.

PASS_STATUS: REVIEW_COMPLETE

2026-06-04 ambiguity review: found one new actionable ambiguity (B1). A2/A4 use the selector's `(ns, var, arity, line)` key and pin the target to line `603`, while the allowed refactor may insert/extract local helpers and move the `defn`. That makes the after metric row potentially missing or shifted, and A2 does not define added/deleted-unit handling for the metric-derived touched set. Added a `design-steps.md` follow-up to specify the executable comparison rule before planning/refactoring.

PASS_STATUS: ACTIONABLE_FEEDBACK


2026-06-04 ambiguity follow-up B1 executed: clarified the burden-comparison identity rules in `design.md`. A2 now reconciles before/after Gordian rows by unique logical key `(file, ns, var, arity)` when that key is unique on both sides, falls back to the selector full key `(ns, var, arity, line)` for ambiguous/unpaired rows, and counts added/deleted units with zero on the missing side. A4 no longer requires preserving line `603`; that line is baseline selector provenance, while the after target is the unique `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)` row. If the after target is missing or duplicated, A4 fails.

PASS_STATUS: REVIEW_COMPLETE

2026-06-04 inconsistency review: no new actionable inconsistency found. Checked `design.md` internally and against the referenced target code, existing sibling tests, `before-local.json`, `META.md`, and `doc/architecture.md`. The burden-key clarification from B1 keeps A2/A4 executable, the coverage hint matches the sibling test surface, and the blast-radius/architecture constraints are mutually consistent with app-runtime ownership and TUI adapter boundaries. No `design-steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 planning from stable design: created `plan.md` and `steps.md`. Design was complete enough to plan; no unresolved blocking ambiguity found. Noted the resolved B1 metric-key clarification as non-blocking planning context. Plan slices are safety-net assessment, characterization-if-needed, local lifecycle/data-shape refactor, behaviour verification, Gordian burden/architecture gates, and final blast-radius review.

PASS_STATUS: PLAN_COMPLETE

2026-06-05 plan/steps ambiguity review: no new actionable ambiguity found. Re-read `plan.md`, `steps.md`, `design.md`, this implementation log, `components/app-runtime/src/psi/app_runtime.clj` around `start-tui-runtime!`, `components/app-runtime/src/psi/app_runtime/tui_wiring.clj`, and the direct sibling app-runtime tests. The execution order is singular (safety-net assessment → characterize gaps if any → local refactor → tests/lint → Gordian gates → blast-radius note), the resolved B1 burden-key rule is referenced where needed, and the potentially under-covered callback/nullable/current-session behaviours are deliberately handled by the Phase 0 coverage-map gate rather than left as undefined implementation choices. No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 plan/steps inconsistency review: no new actionable inconsistency found. Checked `plan.md` and `steps.md` against `design.md`, `design-steps.md`, prior implementation notes, `start-tui-runtime!`, `tui_wiring.clj`, and the sibling app-runtime tests. Slice order, Phase 0 gating, provider lifetime, `/new` focus targeting, nullable execution-mode coverage, callback/wiring assembly, A2/A4 burden-key reconciliation, and A3 gate command all align across task artifacts. No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 implementation pass: completed the behaviour-preserving refactor of `psi.app-runtime/start-tui-runtime!`.

Phase 0 safety net assessment:
- Existing direct app-runtime tests cover the named observable behaviours sufficiently: provider install/clear before normal return and exceptional frontend throw; persisted current-session file and TUI session-root startup; command journaling; `/new` focus targeting followed by extension command execution in the new active session; TUI prompt execution through the prompt lifecycle; and nullable deterministic execution-mode installation/shape through private helper tests.
- No new characterization tests were required. Focused pre-refactor characterization command was `bb clojure:test:scry --namespace psi.app-runtime-test` → 31 tests, 119 assertions, 0 failures/errors.

Refactor:
- Kept all changes inside the original target function body.
- Split the bootstrap `let` from the callback/options assembly `let`, making the runtime bootstrap state (`ctx`, `oauth-ctx`, `cwd`, `startup-rehydrate`, `session-id`, `tui-focus*`) explicit before provider installation and nREPL-visible `session-state` publication.
- Preserved the direct provider lifetime shape: `ui-capabilities/install-provider!` occurs after bootstrap and `ui-capabilities/clear-provider!` remains in the `finally` around the single `tui-start-fn!` call.
- Preserved `/new` semantics by leaving `:on-new-session!` reading `@tui-focus*` and ignoring its callback argument.
- No helper outside the original target body was touched, so A5 blast radius is exactly the target unit.

Verification:
- `clj-paren-repair components/app-runtime/src/psi/app_runtime.clj` → success. During this formatting/repair step the tool briefly malformed an unrelated existing multiline `resolve-model` string form; restored before verification and commit.
- `bb clojure:test:scry --namespace psi.app-runtime-test` → 31 tests, 119 assertions, 0 failures/errors.
- `bb clojure:test:unit` → exit 0.
- `bb lint` → 0 errors, 0 warnings (one pre-existing info in `workflow_delegate_review_step_live_test.clj`).
- `bb commit-check:file-lengths` → exit 0.

Gordian acceptance:
- `bb gordian local --json > /tmp/after-local.json` captured the after metric.
- A2 reconciliation touched exactly one logical unit: `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)`. Sum before `7.031652915638373`; sum after `6.9363427358340495`; delta `-0.09531017980432388`, so net burden strictly decreased.
- A4 target burden decreased: baseline `(psi.app-runtime, start-tui-runtime!, 5, 603)` `lcc-total 7.031652915638373`; after unique logical target line `603` `lcc-total 6.9363427358340495`.
- A3 `bb gordian gate --baseline munera/open/211-simplify-start-tui-runtime/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0` → PASS, exit 0.

PASS_STATUS: IMPLEMENTATION_COMPLETE

2026-06-05 implementation review: no new actionable implementation issue found. Reviewed the task artifacts, implementation diff, `start-tui-runtime!`, `tui_wiring.clj`, and direct app-runtime tests. The change is behaviour-preserving and local: bootstrap remains ordered, nullable execution-mode installation remains before bootstrap, provider install/clear semantics match the pre-existing lifetime shape with `clear-provider!` in the `finally` around `tui-start-fn!`, `/new` still reads `@tui-focus*`, and wiring/options keys are preserved. Re-ran `bb clojure:test:scry --namespace psi.app-runtime-test` (31/119 green), `bb lint` (0 errors/0 warnings; one pre-existing info), and `bb commit-check:file-lengths` (green). No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT1). The design/plan name nullable deterministic execution-mode installation as `start-tui-runtime!` behaviour, but the current tests only pin `maybe-install-nullable-execution-mode` directly plus normal TUI prompt flow with the executor redefined. A regression that removes or skips the `start-tui-runtime!` call to `maybe-install-nullable-execution-mode` would leave those helper tests green. Added an unchecked `steps.md` follow-up to characterize the public TUI runtime path under deterministic nullable mode and assert the echoed assistant output/state, without asserting helper interactions.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT1 executed: added `start-tui-runtime-uses-nullable-deterministic-mode-on-public-tui-path-test` in `components/app-runtime/test/psi/app_runtime_test.clj`. The test drives the public `start-tui-runtime!` TUI path with nullable deterministic mode enabled, captures the provided `run-agent-fn`, runs a prompt through a real queue, and asserts the assistant response echoes the user text. It deliberately does not redefine `psi.turn-runtime.core/execute-prepared-request!`, so a regression that skips `maybe-install-nullable-execution-mode` in `start-tui-runtime!` fails through the public path. Also asserts event-log response recording and persisted user/assistant text state. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 124 assertions, 0 failures/errors; `bb lint` → 0 errors/0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT2). The design/steps name startup/session-root options and startup rehydrate as public `start-tui-runtime!` TUI startup behaviour, but existing public-path tests assert only the persisted `:current-session-file` and callable callbacks; none assert that `bootstrap-runtime-session!`'s `:startup-rehydrate` payload is forwarded into TUI opts as `:initial-messages`, `:initial-tool-calls`, and `:initial-tool-order`. A regression dropping or replacing the startup rehydrate when assembling TUI opts would leave the current tests green while losing resume-visible transcript/tool state at startup. Added an unchecked `steps.md` follow-up to characterize that public `start-tui-runtime!` option surface.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT2 executed: strengthened the public `start-tui-runtime!` option-surface characterization by replacing `start-tui-runtime-passes-current-session-file-test` with `start-tui-runtime-passes-current-session-file-and-startup-rehydrate-test`. The test still drives the real public TUI startup path with an isolated persisted `:session-root` and asserts `:current-session-file`, callable `:dispatch-fn`, callable `:on-interrupt-fn!`, TUI `:ui-type`, and the persisted file path under the temp root. It now stubs only `bootstrap-runtime-session!` to return a concrete `:startup-rehydrate` payload, then asserts the frontend opts receive the same transcript/tool state as `:initial-messages`, `:initial-tool-calls`, and `:initial-tool-order`. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 125 assertions, 0 failures/errors; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0, with the edited test file below the 800-line guard at 799 lines.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT3). The design/plan name TUI callback wiring and session focus/navigation as public `start-tui-runtime!` behaviour. Current public-path tests now cover provider lifetime, command dispatch, `/new`, prompt execution, nullable deterministic mode, current-session file, and startup rehydrate, but they still only assert `:dispatch-fn`/`:on-interrupt-fn!` as option callbacks. No test locks the `:frontend-action-handler-fn!`, `:resume-fn!`, `:switch-session-fn!`, or `:fork-session-fn!` option surface, nor proves a public navigation callback updates TUI focus and emits a `:context-updated` event. A regression dropping or miswiring those navigation callbacks would leave the current tests green while breaking TUI session selection/resume/fork behaviour. Added an unchecked `steps.md` follow-up to characterize that public callback surface.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT3 executed: strengthened the public `start-tui-runtime!` option-surface characterization by extending the startup option test into `start-tui-runtime-passes-current-session-file-startup-rehydrate-and-nav-callbacks-test`. The test captures real TUI opts from the public startup path, locks `:frontend-action-handler-fn!`, `:resume-fn!`, `:switch-session-fn!`, and `:fork-session-fn!` as callable public callbacks alongside the already-covered dispatch/interrupt callbacks, then exercises the real `:switch-session-fn!` over a second top-level session created in the runtime context. It asserts switch returns resume state, updates the focus observed by `:query-fn`, and emits a `:context-updated` event with the switched session id on the TUI event queue. No navigation helpers are stubbed. To keep the edited component test file within the 800-line guard, removed stale `;; moved to psi.main` comments and a decorative section separator. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 127 assertions, 0 failures/errors; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0, with the edited test file at 800 lines.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT4). The design/plan name startup/session-root options and runtime bootstrap context creation as public `start-tui-runtime!` behaviour. Current public-path tests pin `:session-root`/`:current-session-file`, startup rehydrate forwarding, navigation callbacks, and nullable deterministic mode, but no test drives startup with a non-default `startup-opts :cwd` and proves that resolved cwd reaches both `bootstrap-runtime-session!` opts and the TUI `:cwd` option. A regression dropping `(:cwd startup-opts)`, omitting `:cwd cwd` from bootstrap, or omitting TUI `:cwd` forwarding would leave the current tests green while changing startup worktree semantics. Added an unchecked `steps.md` follow-up to characterize that public cwd-forwarding surface.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT4 executed: strengthened the public `start-tui-runtime!` option-surface characterization by extending the startup option test into `start-tui-runtime-passes-current-session-file-startup-rehydrate-nav-callbacks-and-cwd-test`. The test now starts with an isolated non-default `:cwd` (the temp `session-root` path), captures the real `bootstrap-runtime-session!` opts through the existing narrow stub, and asserts the same resolved cwd reaches both `bootstrap-runtime-session!` as `:cwd` and the frontend TUI opts as `:cwd`. This makes regressions that drop `startup-opts :cwd`, omit bootstrap cwd forwarding, or omit TUI cwd forwarding fail through the public startup path. Also removed a stale reader-commented require to keep the component test file at the 800-line guard. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0, with the edited test file at 800 lines.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT5). The public `start-tui-runtime!` arity accepts `memory-runtime-opts` and must pass them through bootstrap into memory sync, but current public-path tests capture only `:cwd` in `bootstrap-runtime-session!` opts; the separate bootstrap memory-runtime test does not fail if `start-tui-runtime!` drops its `memory-runtime-opts` argument. A regression deleting `:memory-runtime-opts memory-runtime-opts` from the TUI bootstrap call would leave the current suite green while changing TUI startup memory configuration. Added an unchecked `steps.md` follow-up to characterize this through the public TUI path, preferably with `memory-runtime/sync-memory-layer!` nullable output tracking rather than stubbing the bootstrap helper.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT5 executed: added `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-forwards-memory-runtime-opts-to-bootstrap-sync-test`. The test drives the public `start-tui-runtime!` path with distinctive `memory-runtime-opts`, does not stub `bootstrap-runtime-session!`, and uses nullable output tracking on `memory-runtime/sync-memory-layer!` to assert the same options reach the real bootstrap/memory sync path plus resolved `:cwd`. This makes deleting `:memory-runtime-opts memory-runtime-opts` from the TUI bootstrap call fail through public startup. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 1 test, 3 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb clojure:test:unit` → exit 0; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT6). The design describes `start-tui-runtime!` as receiving resolved runtime config and the implementation forwards both `session-config` and `startup-opts :thinking-level-override` into `create-runtime-session-context`, but current public-path TUI startup tests do not assert either value affects the created runtime context/session. A regression dropping `:session-config session-config` or `:thinking-level-override (:thinking-level-override startup-opts)` from the TUI context creation call would leave the current public-path TUI tests green while changing runtime configuration/default thinking-level semantics. Added an unchecked `steps.md` follow-up to characterize this through `start-tui-runtime!` rather than only lower-level context/bootstrap helpers.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT6 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-forwards-session-config-and-thinking-override-to-context-test`. The test drives the public `start-tui-runtime!` path with a distinctive `session-config` and `startup-opts :thinking-level-override`, does not stub `create-runtime-session-context`, and asserts the created runtime context contains the config overrides while the created TUI session records the overridden thinking level. The model stub is reasoning-capable so `:high` is not clamped away, making removal of either forwarding key fail through public startup. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 2 tests, 6 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb clojure:test:unit` → exit 0; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 implementation review: no new actionable implementation issue found after the TT1–TT6 public-path coverage follow-ups. Re-read task artifacts, `start-tui-runtime!`, `tui_wiring.clj`, and the direct TUI startup tests. The implementation remains local to the target runtime assembly path, preserves provider install/clear lifetime, `/new` focus semantics, runtime/bootstrap option forwarding, nullable deterministic mode installation, and TUI callback/option surfaces. Re-verified `bb clojure:test:scry --namespace psi.app-runtime-test` (32/128 green), `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` (2/6 green), `bb lint` (0 errors/0 warnings; one pre-existing info), and `bb commit-check:file-lengths` (green). No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT7). Public-path tests now cover provider lifetime, command dispatch, `/new`, prompt execution, nullable deterministic mode, current-session file, startup rehydrate, navigation switch callback, cwd forwarding, memory-runtime opts, and runtime config/thinking override forwarding, but they still only assert `:on-interrupt-fn!` is callable and do not assert `:on-queue-input-fn!` at all. A regression omitting or miswiring those TUI active-input callbacks would leave the current tests green while breaking Escape/streaming queue behaviour. Added an unchecked `steps.md` follow-up to characterize the callbacks through public `start-tui-runtime!` opts with real session state, not callback-helper stubs.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT7 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-wires-active-input-callbacks-to-focused-session-test`. The test drives the public `start-tui-runtime!` path, captures TUI opts, and exercises the real `:on-queue-input-fn!` and `:on-interrupt-fn!` against the focused session state without stubbing `tui-wiring` or session callback helpers. It puts the focused session into streaming with a pending tool call so queueing is accepted rather than stranded-stream recovery, asserts the queue callback returns `"Queued steering message."` and stores the steering text on the real session, then asserts interrupt returns the queued text plus `"Interrupted active work."`, moves the session back to `:idle`, and clears queued text. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 3 tests, 13 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT8). Public-path tests now cover provider lifetime, command dispatch, `/new`, prompt execution, nullable deterministic mode, current-session file, startup rehydrate, navigation/action callbacks, cwd forwarding, memory-runtime opts, runtime config/thinking override forwarding, and active-input callbacks. They still do not lock the TUI UI projection/dispatch and chrome option surface: `:ui-read-fn`, `:ui-dispatch-fn`, `:footer-model-fn`, and `:session-selector-fn`. A regression dropping or miswiring those callbacks would leave the current public startup tests green while breaking extension UI snapshots/actions, footer rendering, or the session selector. Added an unchecked `steps.md` follow-up to characterize those callbacks through public `start-tui-runtime!` opts and real session/UI state.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT8 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-wires-ui-projection-dispatch-footer-and-selector-test`. The test drives the public `start-tui-runtime!` path, captures TUI opts, and exercises the real `:ui-dispatch-fn`, `:ui-read-fn`, `:footer-model-fn`, and `:session-selector-fn` against real runtime session/UI state without stubbing `tui-wiring` or lower-level option assembly. It dispatches extension UI widget/status events through the captured dispatch callback, asserts the UI snapshot exposes the widget/status, asserts the footer sees the status and model chrome, and asserts the session selector action includes the focused session, a second real top-level session, and active-session metadata. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 4 tests, 23 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable test coverage issue (TT9). Public-path tests now cover provider lifetime, command dispatch, `/new`, prompt execution, nullable deterministic mode, current-session file, startup rehydrate, navigation/action callbacks, cwd forwarding, memory-runtime opts, runtime config/thinking override forwarding, active-input callbacks, and UI projection/chrome callbacks. They still do not cover the TUI OAuth pending-login handoff: `:dispatch-fn` stores `:pending-login` on a `/login` result without a callback server, later non-command input should fall through, and `run-agent-fn` should complete OAuth, clear pending state, and queue the login result. A regression dropping or miswiring that bridge would leave current public startup tests green while breaking auth-code entry in the TUI. Added an unchecked `steps.md` follow-up to characterize the flow through public `start-tui-runtime!` callbacks and a nullable OAuth provider.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT9 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-completes-pending-login-from-auth-code-input-test`. The test drives public `start-tui-runtime!` with a nullable OAuth context/provider, captures TUI opts and `run-agent-fn`, sends `/login` through the real captured `:dispatch-fn`, and asserts the login-start URL plus `:pending-login` bridge state. It then verifies the dispatch callback falls through while login is pending, submits an auth code through captured `run-agent-fn`, and asserts the queued success assistant output, cleared pending-login state, captured trimmed auth code/login-state, and stored OAuth credential via `oauth/get-api-key`. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 5 tests, 32 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0; `bb clojure:test:unit` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found two new actionable coverage gaps (TT10, TT11). Current public-path tests exercise direct switch navigation and streaming queue input, but only callability-lock `:frontend-action-handler-fn!`/`:resume-fn!`/`:fork-session-fn!` and do not cover the idle follow-up branch of `:on-queue-input-fn!`. Added unchecked `steps.md` follow-ups to characterize those through public `start-tui-runtime!` opts and real session state. Verified focused TUI startup 5/32 green and app-runtime 32/128 green.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-ups TT10/TT11 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with two public TUI startup characterization tests. TT10 drives the captured `:frontend-action-handler-fn!` with a real `:select-session` fork action result, then drives direct `:fork-session-fn!` and `:resume-fn!` against real session/journal state; it asserts returned transcript rehydrate state, focus observed through `:query-fn`, emitted `:context-updated` events, and resumed model state. TT11 drives captured `:on-queue-input-fn!` while the focused session is idle/non-streaming and asserts both the returned `"Queued follow-up message."` and the stored follow-up text. TT11 exposed a real defect: the non-streaming callback branch returned the follow-up message but called `queue-while-streaming-in!`, which intentionally does not enqueue when the session is not streaming, so idle follow-up text was dropped. Fixed the root cause in `psi.app-runtime.tui-wiring/build-tui-opts` by routing the idle branch through `session/follow-up-in!` while preserving the streaming steering branch. Added a CHANGELOG Fixed entry because this is user-visible TUI behaviour. Verification: `clj-paren-repair components/app-runtime/src/psi/app_runtime/tui_wiring.clj components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 7 tests, 53 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb clojure:test:unit` → exit 0; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable coverage gap (TT12). Public-path tests now exercise most `start-tui-runtime!` callback/option surfaces, including command dispatch and navigation, but no test proves the TUI-specific command option `:supports-session-tree? true` reaches command dispatch. `/tree` has a CLI-vs-TUI branch: if the option is missing/false, dispatch returns the unsupported TUI-only message. A regression dropping or falseing that option in `cmd-opts` would leave current public startup tests green while breaking the TUI session tree command. Added an unchecked `steps.md` follow-up to drive captured `:dispatch-fn` with `/tree` through public startup opts and assert the TUI tree surface.

Verification during review: `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 7/53 green; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32/128 green; `bb lint` → 0 errors/0 warnings (one pre-existing info).

PASS_STATUS: ACTIONABLE_FEEDBACK


2026-06-05 test-review follow-up TT12 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-enables-session-tree-command-through-tui-dispatch-test`. The test drives public `start-tui-runtime!`, captures real TUI opts, sends `/tree` through the captured `:dispatch-fn`, and asserts the TUI command surface returns `{:type :tree-open}` rather than the CLI-only unsupported message. This makes dropping or falseing `:supports-session-tree? true` in the TUI command option assembly fail through public startup. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 8 tests, 56 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb clojure:test:unit` → exit 0; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable coverage gap (TT13). Public-path tests cover startup rehydrate, cwd/config/memory forwarding, active-input, OAuth, navigation callbacks, UI projection/chrome, and the `/tree` command option, but none prove the initial context session tree widget assembled by `tui-session-nav/current-context-widget` is forwarded into TUI opts as `:initial-context-session-tree-widget`. `build-tui-opts` has this option and the TUI init consumes it, but deleting `:current-context-widget` from `start-tui-runtime!` or omitting `:initial-context-session-tree-widget` in `build-tui-opts` would leave current app-runtime public startup tests green while losing the startup-visible tree widget. Added an unchecked `steps.md` follow-up to characterize this with real context/session state through public `start-tui-runtime!` opts.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT13 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-forwards-initial-context-session-tree-widget-test`. The test drives public `start-tui-runtime!`, uses a narrow bootstrap wrapper to create a second real top-level session before TUI opts are built, captures frontend opts, and asserts `:initial-context-session-tree-widget` matches `tui-session-nav/current-context-widget` for the focused session. It also asserts the visible session-tree widget identity (`psi-session`/`session-tree`/`left`), a current-session line with runtime state, and a sibling-session `/tree <id>` action line, so dropping `:current-context-widget` from `start-tui-runtime!` or omitting `:initial-context-session-tree-widget` in TUI option assembly fails through public startup. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` success; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 9 tests, 62 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb clojure:test:unit` → exit 0; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 implementation review: found one new actionable implementation issue (IR1). TT11 introduced a user-visible production behaviour fix in `components/app-runtime/src/psi/app_runtime/tui_wiring.clj` (idle `:on-queue-input-fn!` now calls `session/follow-up-in!`) after the original A2/A3/A4 Gordian acceptance was recorded. That change is valuable, but the task design is a behaviour-preserving `start-tui-runtime!` refactor and the final acceptance/blast-radius notes have not been reconciled against the current HEAD. Added an unchecked `steps.md` follow-up to either justify/update the task scope/artifacts for the defect fix or split/revert it, then rerun and record final A2/A3/A4 gates.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 implementation follow-up IR1 executed: reconciled the TT11 production behaviour change with the task scope and reran the final Gordian acceptance from current HEAD. Decision: keep the `tui_wiring.clj` idle follow-up fix in this task rather than revert or split it. Although the primary task is a behaviour-preserving refactor of `start-tui-runtime!`, TT11's public-path characterization exposed a pre-existing user-visible defect in the adjacent TUI option helper that implements the callback surface assembled by `start-tui-runtime!`: idle `:on-queue-input-fn!` returned `"Queued follow-up message."` while routing through the streaming-only helper and dropping the follow-up text. The fix is minimal, covered by the new public startup test, documented in CHANGELOG, and remains inside the local TUI startup wiring blast radius; `design.md` and `plan.md` now explicitly record this one accepted exception to behaviour preservation.

Final Gordian acceptance from current HEAD:
- `bb gordian local --json > /tmp/after-local.json` → captured after metrics.
- A2 logical-key reconciliation touched exactly one unit, `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)`: before sum `7.031652915638373`, after sum `6.9363427358340495`, delta `-0.09531017980432388`; net burden strictly decreased.
- A4 target burden decreased for the unique after logical target `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)`: before `7.031652915638373`; after line `603` `6.9363427358340495`.
- A3 `bb gordian gate --baseline munera/open/211-simplify-start-tui-runtime/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0` → PASS, exit 0.

PASS_STATUS: REVIEW_COMPLETE


2026-06-05 implementation review: found one new actionable implementation issue (IR2). `start-tui-runtime!` installs the TUI UI provider before constructing navigation callbacks and `tui-opts`, but `ui-capabilities/clear-provider!` is only protected by the `finally` around `tui-start-fn!`. A synchronous failure during post-install option assembly, such as `current-context-widget` or `build-tui-opts`, would leave the attached TUI provider observable from the runtime context despite the provider-lifetime goal. Existing exceptional cleanup coverage only covers frontend failure after options are built. Added an unchecked `steps.md` follow-up to move installation after successful option assembly or widen the protected region and cover the failure path.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 implementation follow-up IR2 executed: tightened TUI provider lifetime protection for `start-tui-runtime!`. Moved `ui-capabilities/install-provider!` to after navigation callbacks and `tui-opts` are fully assembled, leaving the existing direct `try`/`finally` around `tui-start-fn!` unchanged. This preserves the frontend-observable provider surface for normal startup and frontend exceptions, while preventing a synchronous option-assembly failure from ever installing the attached TUI provider.

Added focused public startup coverage in `app_runtime_tui_startup_test.clj`: `start-tui-runtime-does-not-leak-provider-when-option-assembly-throws-test` forces `tui-session-nav/current-context-widget` to throw during option assembly, captures the real runtime context, asserts the frontend is not entered, and asserts `ui-capabilities/provider` remains nil. Existing provider-lifetime tests still cover provider attachment during frontend execution and cleanup when the frontend throws.

Verification:
- `clj-paren-repair components/app-runtime/src/psi/app_runtime.clj components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` → success.
- `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 10 tests, 65 assertions, 0 failures/errors.
- `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors.
- `bb clojure:test:unit` → exit 0.
- `bb lint` → 0 errors, 0 warnings (one pre-existing info).
- `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 implementation review: no new actionable implementation issue found after IR2. Re-read the task artifacts, `start-tui-runtime!`, `tui_wiring.clj`, and the public TUI startup/app-runtime tests. Provider installation now happens only after option assembly, remains cleared by the direct `finally` around frontend execution, and the accepted TT11 idle follow-up fix stays minimal and covered. Re-verified `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` (10/65 green), `bb clojure:test:scry --namespace psi.app-runtime-test` (32/128 green), `bb lint` (0 errors/0 warnings; one pre-existing info), and `bb commit-check:file-lengths` (green). Re-ran current-HEAD Gordian gates: A2/A4 target burden remains decreased (`7.031652915638373` → `6.8162713696596535`) and `bb gordian gate --baseline munera/open/211-simplify-start-tui-runtime/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0` passed. No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable coverage gap (TT14). Public-path TUI startup tests now cover provider lifetime, startup option forwarding, command dispatch, session-tree/current-context surfaces, active-input, OAuth, UI projection/chrome, and session navigation/fork/resume. They still do not cover the model-selection branch of `:frontend-action-handler-fn!` through public `start-tui-runtime!` opts; only the lower-level `tui-frontend-actions` helper test stubs `session/set-model-in!` and `resolve-model-by-provider+id`. A regression dropping or miswiring `:resolve-model-by-provider+id` in `start-tui-runtime!`/`tui_wiring` would leave the public startup suite green while breaking TUI model picker submission. Added an unchecked `steps.md` follow-up to characterize a real `:select-model` submitted action result against runtime session state.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT14 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-frontend-action-select-model-updates-session-test`. The test drives public `start-tui-runtime!`, captures real TUI opts, invokes captured `:frontend-action-handler-fn!` with a submitted `:select-model` action result for the real built-in `openai/gpt-5.3-codex` model, and asserts both the returned success text and the focused runtime session's updated `:model` map (`provider`, `id`, and reasoning flag). It stubs only startup infrastructure and the initial model-key resolver; it does not stub `tui_wiring`, `tui-frontend-actions`, `session/set-model-in!`, or `resolve-model-by-provider+id`, so dropping/miswiring `:resolve-model-by-provider+id` in the public TUI startup wiring fails through runtime session state. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` → success/no changes; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 11 tests, 68 assertions, 0 failures/errors; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors; `bb clojure:test:unit` → exit 0; `bb lint` → 0 errors, 0 warnings (one pre-existing info); `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable coverage gap (TT15). Public-path TUI startup tests now exercise many callback surfaces and direct frontend-action submitted results, but they do not prove that TUI-only command dispatch branches `/model`, `/thinking`, and `/resume` still route through `tui-frontend-actions/command-result` from the captured `:dispatch-fn`. Lower-level command/action tests cover the branches separately, but replacing the TUI dispatch wrapper with plain command dispatch would leave current public startup coverage green while breaking the TUI frontend-action launch surface for model picker, thinking picker, and persisted-session resume picker. Added an unchecked `steps.md` follow-up to characterize those commands through public `start-tui-runtime!` opts. Verified `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` (11/68 green) and `bb clojure:test:scry --namespace psi.app-runtime-test` (32/128 green).

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT15 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-dispatches-tui-only-frontend-action-commands-test`. The test drives public `start-tui-runtime!`, captures the real TUI `:dispatch-fn`, creates a real persisted session under the startup `:session-root`, then dispatches `/model`, `/thinking`, and `/resume`. It asserts each result is a `:frontend-action` with the expected action id (`:select-model`, `:select-thinking-level`, `:select-resume-session`), submit contract, and meaningful items/options including a built-in model, all thinking levels, and the persisted resumable session. This makes replacing `tui-frontend-actions/command-result` with plain command dispatch, or dropping those TUI-only branches, fail through the public startup path. Verification: `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` → success/no changes; `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 12 tests, 81 assertions, 0 failures/errors.

PASS_STATUS: REVIEW_COMPLETE


2026-06-05 test review: found one new actionable coverage gap (TT16). Public-path `start-tui-runtime!` tests now cover provider lifetime, startup/config forwarding, callback/action surfaces, command dispatch, session-tree widgets, OAuth, and frontend-action commands, but none assert the terminal mode option `:alt-screen false`. `psi.tui.app/start!` defaults missing `:alt-screen` to true, so a regression dropping this option from `tui_wiring/build-tui-opts` would leave current public startup tests green while changing observable TUI terminal behaviour. Added an unchecked `steps.md` follow-up to characterize the option through public startup opts.

Verification during review: `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 12/81 green; `bb clojure:test:scry --namespace psi.app-runtime-test` → 32/128 green.

PASS_STATUS: ACTIONABLE_FEEDBACK

2026-06-05 test-review follow-up TT16 executed: extended `components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` with `start-tui-runtime-forwards-alt-screen-false-to-tui-opts-test`. The test drives public `start-tui-runtime!`, captures the TUI opts passed to the frontend start function, and asserts `:alt-screen` is present and false. This makes dropping the explicit terminal-mode option from `tui_wiring/build-tui-opts` fail through the public startup path instead of falling silently back to `psi.tui.app/start!`'s default true alternate-screen behaviour.

Verification:
- `clj-paren-repair components/app-runtime/test/psi/app_runtime_tui_startup_test.clj` → success/no changes.
- `bb clojure:test:scry --namespace psi.app-runtime-tui-startup-test` → 13 tests, 84 assertions, 0 failures/errors.
- `bb clojure:test:scry --namespace psi.app-runtime-test` → 32 tests, 128 assertions, 0 failures/errors.
- `bb clojure:test:unit` → exit 0.
- `bb lint` → 0 errors, 0 warnings (one pre-existing info).
- `bb commit-check:file-lengths` → exit 0.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 test review: found one new actionable coverage gap (TT17). Public-path startup tests now cover the TUI option/callback surface and navigation focus through captured `:query-fn`, but none prove the cross-runtime `session-state` publication of the live `:tui-focus*` atom remains correct for nREPL consumers. `start-tui-runtime!` publishes `:tui-focus*` specifically so `psi.app-runtime.nrepl-runtime/active-session-id-in-session-state` can follow TUI focus changes; a regression that omits it or stores the initial session id/stale atom could leave current TUI callback tests green while editor nREPL operations target the wrong session after navigation. Added an unchecked `steps.md` follow-up to characterize this through public startup plus a real focus change.

Verification during review: inspected task artifacts, `start-tui-runtime!`, `tui_wiring.clj`, `nrepl_runtime.clj`, and public TUI startup/app-runtime tests. No code/tests changed in this review pass.

PASS_STATUS: ACTIONABLE_FEEDBACK
