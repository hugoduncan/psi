# Steps — Simplify `psi.app-runtime/start-tui-runtime!`

## Slice 0 — Orientation and safety-net assessment

- [ ] Read `components/app-runtime/src/psi/app_runtime.clj` around `start-tui-runtime!` and note each observable responsibility in the current call order.
- [ ] Read `components/app-runtime/test/psi/app_runtime_test.clj` and list the tests that directly or transitively exercise `start-tui-runtime!`.
- [ ] Map existing tests to Phase 0 behaviour areas: TUI provider install/clear, exceptional cleanup, startup/session-root options, persisted current-session file, command journaling, `/new` focus targeting, callback wiring, and nullable deterministic execution mode.
- [ ] Record in `implementation.md` whether existing coverage is sufficient or which characterization gaps must be filled before production refactoring.

## Slice 1 — Characterization net, if needed

- [ ] Add minimal characterization test(s) for any Phase 0 gap identified in Slice 0, asserting observable state or outputs rather than interactions.
- [ ] Run the focused app-runtime test namespace with the unmodified production code and confirm all existing and new characterization tests pass.
- [ ] Record the green pre-refactor characterization command and result in `implementation.md`.

## Slice 2 — Local lifecycle/data-shape refactor

- [ ] Identify the minimal coherent helper or local data-shape boundaries to extract from `start-tui-runtime!` without changing architecture or broadening the blast radius.
- [ ] Refactor runtime bootstrap bindings so `ctx`, `oauth-ctx`, `cwd`, `startup-rehydrate`, `session-id`, and `tui-focus*` remain explicit and ordered.
- [ ] Refactor TUI provider installation and frontend startup so `ui-capabilities/clear-provider!` remains protected by a direct `finally` around `tui-start-fn!`.
- [ ] Refactor session-navigation and command option assembly while preserving `/new` behaviour that reads `@tui-focus*` and ignores the source callback argument.
- [ ] Refactor TUI wiring dependency and option assembly while preserving all keys consumed by `tui-wiring/build-tui-opts` and the TUI start function.
- [ ] Run `clj-paren-repair components/app-runtime/src/psi/app_runtime.clj` after source edits.

## Slice 3 — Behaviour verification

- [ ] Run the focused app-runtime tests after the refactor and fix any behaviour regression without weakening expectations.
- [ ] Run `bb clojure:test:unit` or the smallest project-approved affected unit suite that includes app-runtime coverage.
- [ ] Run `bb lint` and fix any lint introduced by the refactor.
- [ ] Run `bb commit-check:file-lengths` and fix any file-length regression.
- [ ] Record verification commands and outcomes in `implementation.md`.

## Slice 4 — Burden and architecture gates

- [ ] Run `bb gordian local --json > /tmp/after-local.json` from the worktree root.
- [ ] Compare `/tmp/after-local.json` with `munera/open/211-simplify-start-tui-runtime/before-local.json` using the A2 logical-key reconciliation rule and confirm metric-derived touched-set total burden strictly decreases.
- [ ] Confirm A4 target burden decreases by re-identifying the after target with logical key `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)`.
- [ ] Run `bb gordian gate --baseline munera/open/211-simplify-start-tui-runtime/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0` from the worktree root and confirm exit code `0`.
- [ ] Record the A2, A3, and A4 results in `implementation.md`.

## Slice 5 — Final review and blast-radius documentation

- [ ] Review `git diff` and ensure changes are limited to `start-tui-runtime!`, minimal surrounding helpers, tests added for characterization, and task artifacts.
- [ ] Document in `implementation.md` why every touched helper outside the original target body is within the design's blast radius.
- [ ] Ensure `steps.md` is fully checked for completed implementation work and set the task implementation status in `implementation.md`.
- [ ] Commit the implementation and task artifact updates with a symbolized Munera commit message.
