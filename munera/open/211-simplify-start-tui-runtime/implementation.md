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
