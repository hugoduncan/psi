# Steps — 210 Fix `project-repl op=start` readiness/stale-port

## Slice 1 — Config surface (timeout key + validation)

- [x] Add `resolved-start-readiness-timeout-ms` to `config.clj`: read
      `[:project-nrepl :start-readiness-timeout-ms]`; `nil` when unset;
      `cond`-style range check `[1000 600000]` (integer) mirroring
      `resolved-attach-endpoint`; throw `ex-info` `{:phase :validate}` on
      non-integer / out-of-range.
- [x] Add `config_test.clj` cases: unset → `nil`; valid in-range → value;
      below 1000 → throws `:phase :validate`; above 600000 → throws; non-integer
      → throws.
- [x] Run the unit suite (`clojure -M:test --focus
      psi.project-nrepl.config-test`) + `clj-kondo --lint` on changed files;
      commit slice 1. → 8 tests/40 assertions green; lint 0/0; commit.

## Slice 2 — Timeout threading + raised default + payload projection

- [x] Raise `default-readiness-timeout-ms` in `started.clj` `5000 → 120000`.
- [x] In `ops/start`, resolve the timeout via
      `resolved-start-readiness-timeout-ms` from the already-resolved `cfg`, and
      pass `start-instance-in!` an `opts` map carrying `:timeout-ms` (only when
      non-nil; nil falls back to the started.clj default).
- [x] In `start-instance-in!`, add a **new launch-site `update-instance-in!`**
      (after `ensure-instance-in!`, immediately before `(launcher …)` /
      `wait-for-started-endpoint!`) that writes the top-level status field
      `:readiness-timeout-ms` = effective resolved timeout (`(:timeout-ms opts)`
      else `default-readiness-timeout-ms`, matching the wait's fallback) (PA1).
      It is written *pre-wait* so it survives the throwing failure path; it is
      *not* seeded via `ensure-instance-in!` and *not* deferred to the post-wait
      success update.
- [x] Extend `ops/instance-payload`'s fixed key list with
      `:readiness-timeout-ms` (AMB3).
- [x] Add tests: configured timeout flows into `wait-for-started-endpoint!`'s
      effective deadline (via the seam); `instance-payload` includes
      `:readiness-timeout-ms`; fast happy path still reaches `:started`; a
      `status` (op) read of the *failure-path* instance carries
      `:readiness-timeout-ms` (PA4 — observable via `status`, not the throwing
      `start` return).
- [x] Run tests + lint; commit slice 2. → committed with slice 3 (shared
      launch-site update); full project-nrepl suite green.

## Slice 3 — Stale-port ownership guard

- [x] Grep for consumers of the runtime-handle `:started-at` to confirm none
      depend on its current post-wait (connect-time) capture before moving it.
      → grep confirms only `eval.clj` `:active-op :started-at` (distinct field)
      and `ops.clj` `:timing :started-at` (eval-result field); no consumer reads
      the runtime-handle `:started-at`, so the move is safe (matches the
      plan/steps inconsistency-review grep).
- [x] In `start-instance-in!`, capture a single `launched-at (now)` binding at
      the launch site (immediately before `(launcher …)`).
- [x] Write the runtime-handle `:started-at` = `launched-at` in the new
      launch-site `update-instance-in!` (the same pre-wait update that writes
      `:readiness-timeout-ms`, PA1), and **remove** the post-wait success-path
      `:started-at (now)` write, so there is one launch-instant source that
      survives the failure path (PA2). Leave `build-instance`'s top-level
      slot-creation `:started-at` unchanged (distinct field).
- [x] In `start-instance-in!`, delete any existing
      `<worktree>/.nrepl-port` immediately before launching.
- [x] Thread `launched-at` into `wait-for-started-endpoint!` via
      `opts {:launched-at …}`.
- [x] In `wait-for-started-endpoint!`, gate acceptance: read the
      `.nrepl-port` file's last-modified time; accept only when
      `≥ (launched-at floored to whole seconds)` (AMB4). Treat a present
      too-old port as not-yet-ready (continue polling).
- [x] On deadline with only a too-old port present, throw
      `ex-info` `:phase :started-stale-port` carrying rejected mtime +
      `launched-at` (so it lands on `:last-error → :data` via the existing
      `catch`), distinct from `:started-readiness`.
- [x] Add tests (PA3 — exercise the gate at the right level):
      - At the `wait-for-started-endpoint!` *unit* level (injected
        `:launched-at`, pre-written too-old `.nrepl-port`, bypassing removal):
        a too-old port is rejected — continues polling, then
        `:phase :started-stale-port` on deadline; a fresh port (mtime ≥
        launched-at floor) is accepted.
      - At the `start-instance-in!` level: assert **pre-launch removal occurs**
        (a pre-seeded `.nrepl-port` is gone before the launcher writes the fresh
        one) and the fresh post-launch port is accepted. Do *not* assert
        gate-rejection through `start-instance-in!` (removal makes the gate see
        only fresh ports there).
- [x] Add/confirm a test asserting attach-mode discovery and
      `config/read-dot-nrepl-port` are behaviour-preserved (no stale gate).
      → existing `attach_test.clj` + `config_test.clj read-dot-nrepl-port-test`
      remain green unchanged (gate lives only in `started.clj`; the shared
      discovery primitive and attach-mode are untouched).
- [x] Run tests + lint; commit slice 3. → committed with slice 2.

## Slice 4 — Docs + CHANGELOG

- [ ] `doc/project-nrepl.md`: document `:start-readiness-timeout-ms` in the
      started-mode config section (default 120000 ms, range [1000 600000],
      precedence system < user < project).
- [ ] `CHANGELOG.md` `[Unreleased]`: `Added` the config key;
      `Fixed` the slow-boot timeout + stale-`.nrepl-port` wrong-endpoint bugs.
- [ ] Commit slice 4.

## Slice 5 — Coherence pass

- [ ] Run the full project-nrepl test suite green; `clj-kondo --lint` clean.
- [ ] Verify each acceptance criterion in `design.md` is satisfied:
      raised-default slow boot; stale-port wrong-endpoint prevented; A2 status
      projection (`:readiness-timeout-ms` + `:phase :started-stale-port` on
      `:last-error → :data`); Q1 config + `[1000 600000]` validation; both fixes
      unconditional (Q3); attach/happy-path preserved; no-mocks tests; docs.
- [ ] Update `mementum/state.md` if a reusable insight emerged; commit.

## Plan/steps review follow-ups (ambiguity)

- [x] PA1: Resolve `:readiness-timeout-ms` write-site ordering. Name the exact
      `update-instance-in!` (or `ensure-instance-in!` seed) that writes
      `:readiness-timeout-ms`, and ensure it is written *before*
      `wait-for-started-endpoint!` so the effective timeout is observable on a
      timeout *failure*, not only the success path. Update slice-2 steps + plan.
      → Resolved: new pre-wait launch-site `update-instance-in!` writes
      `:readiness-timeout-ms` (top-level), not `ensure-instance-in!` seed nor
      post-wait update. Plan "PA1" + slice-2 step updated.
- [x] PA2: Specify the `:started-at` launch-site move mechanism. State whether a
      new launch-site `update-instance-in!` writes `:started-at launched-at`
      (so it survives the failure path) or it is only written at the existing
      post-wait update; reconcile with the `:runtime-handle` nesting. Update
      slice-3 steps + plan.
      → Resolved: the same new launch-site `update-instance-in!` writes
      runtime-handle `:started-at = launched-at`; post-wait `:started-at` write
      removed; `build-instance` top-level `:started-at` untouched. Plan "PA2" +
      slice-3 steps updated.
- [x] PA3: Reconcile the stale-port rejection test with pre-launch removal.
      Specify that the mtime-gate rejection case is exercised at the
      `wait-for-started-endpoint!` unit level with an injected `:launched-at`
      (since `start-instance-in!`'s pre-launch removal deletes any pre-existing
      port before the gate runs). Update slice-3 test steps.
      → Resolved: gate-rejection at `wait-for-started-endpoint!` unit level
      (injected `:launched-at`); `start-instance-in!`-level test asserts only
      pre-launch removal + fresh acceptance. Plan "PA3" + slice-3 test step
      updated.
- [x] PA4: Disambiguate the failure-path diagnostic surface. State that the
      stale-port/timeout diagnostics on `instance-payload` are observable via a
      `status` (op) read of the instance, not via the throwing `start` op return;
      add/confirm a `status`-read acceptance test. Update slice-2/slice-5 steps.
      → Resolved: failure-path diagnostics observable via `status` op read (not
      the throwing `start` return); `status`-read test added to slice-2. Plan
      "PA4" updated.

## Plan/steps review follow-ups (inconsistency)

- [x] PSI1: Fix the test-runner invocation in slice 1. steps.md said
      "Run `clojure -X:test` (project-nrepl)", but the repo `:test` alias
      (`deps.edn`) is a Kaocha `-M` runner (`:main-opts ["-m" "kaocha.runner"]`,
      no `:exec-fn`), so `clojure -X:test` is invalid. Replace with the project's
      actual unit-test command (`clojure -M:test --focus unit` / `bb test`, per
      bb.edn `clojure:test:unit` + AGENTS.md), consistent with the "full
      project-nrepl test suite" wording in slice 5 and the "Run tests" steps in
      slices 2–3.
      → Resolved: slice-1 step now reads "Run the unit suite (`clojure -M:test
      --focus unit`, or namespace-focused `clojure -M:test --focus
      project-nrepl.config-test`; `bb clojure:test:unit` equivalently)".
      `project-nrepl/test` is in the Kaocha `:unit` suite (`tests.edn`); no
      component-isolated `clojure -X:test` path exists. Task-artifact-only fix
      (steps.md); no code/test/doc change.
