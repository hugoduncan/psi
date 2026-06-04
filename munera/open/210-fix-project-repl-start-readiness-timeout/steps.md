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

- [x] `doc/project-nrepl.md`: document `:start-readiness-timeout-ms` in the
      started-mode config section (default 120000 ms, range [1000 600000],
      precedence system < user < project).
- [x] `CHANGELOG.md` `[Unreleased]`: `Added` the config key;
      `Fixed` the slow-boot timeout + stale-`.nrepl-port` wrong-endpoint bugs.
- [x] Commit slice 4.

## Slice 5 — Coherence pass

- [x] Run the full project-nrepl test suite green; `clj-kondo --lint` clean.
      → `bb clojure:test:unit` green; project-nrepl suite 28 tests/169
      assertions; lint 0/0; file-lengths exit 0.
- [x] Verify each acceptance criterion in `design.md` is satisfied:
      raised-default slow boot; stale-port wrong-endpoint prevented; A2 status
      projection (`:readiness-timeout-ms` + `:phase :started-stale-port` on
      `:last-error → :data`); Q1 config + `[1000 600000]` validation; both fixes
      unconditional (Q3); attach/happy-path preserved; no-mocks tests; docs.
      → all verified (see implementation.md "Implementation execution").
- [x] Update `mementum/state.md` if a reusable insight emerged; commit.
      → state.md updated with the consuming-test-arity-break insight.

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

## Implementation review follow-ups (quality)

- [x] IR1: Make the stale-port diagnostic deterministic on the
      exit-with-stale-port path. In `wait-for-started-endpoint!`, the
      `process-exited?` branch is checked before the deadline branch and threw
      `:phase :started-readiness` ignoring the present-but-too-old `.nrepl-port`,
      so a process that writes only a stale port then exits lost A2's
      `:phase :started-stale-port` distinction.
      → Resolved: the exit branch now folds in the stale-only condition —
      when `(and endpoint (not fresh?))` on exit it throws
      `:phase :started-stale-port` (keeping `:command-exited? true` +
      `:exit-code`, plus the rejected `:port-mtime-ms`/`:min-mtime-ms`/
      `:launched-at`); otherwise it stays `:phase :started-readiness`. The
      stale-port diagnostic wins on exit-with-stale-port, so A2's distinction is
      preserved on every path. Added a `wait-for-started-endpoint!` unit test
      (`exit leaving only a stale port reports :started-stale-port (IR1)`,
      `alive? false`) asserting the chosen `:phase`/`:command-exited?`/message.
      started-test 3 tests/25 assertions green (+3 over pass-1's 22);
      lint 0/0; clj-paren-repair Success; files 198/228 (< 800).

## Test review follow-ups (coverage)

- [x] TR1: Pin the raised `120000` ms default behaviour with a test. Add a
      `start-instance-in!`-with-**no**-`:timeout-ms`-opts test asserting
      `(:readiness-timeout-ms instance) = 120000` (the effective
      `default-readiness-timeout-ms`), so a regression of the default back to
      `5000` is caught. Today only the *configured* timeout (`90000`) is
      asserted; the no-config raised-default acceptance criterion is untested.
      → Resolved: added `started_test.clj` case "no :timeout-ms opts records the
      raised 120000 ms default (TR1)" (runtime-handle launcher seam, no
      `:timeout-ms` opt) asserting `(:readiness-timeout-ms instance) = 120000`.
- [x] TR2: Cover the happy-path `instance-payload`/`status`
      `:readiness-timeout-ms` projection (AMB3) at the ops level. Add an
      `ops_test.clj` case asserting a normal present/ready instance's
      `status` (or `instance-payload`) includes `:readiness-timeout-ms`, so the
      projected-key-list extension is pinned at its owning layer independently of
      the started-mode PA4 failure-path `status` read.
      → Resolved: added `ops_test.clj` `status-readiness-timeout-projection-test`
      — installs a real ready attached instance, sets `:readiness-timeout-ms`
      120000 via `update-instance-in!`, asserts `status` projects `:present`,
      `:readiness true`, and `:readiness-timeout-ms 120000` through
      `instance-payload`.
- [x] TR3: Cover the `ops/start` config→opts threading end-to-end. The Q1
      acceptance criterion — the timeout is *controllable through the
      `[:agent-session :project-nrepl :start-readiness-timeout-ms]` config key* —
      has no test that drives a *configured* timeout from project config through
      `ops/start`'s `cond-> {} (some? timeout-ms) (assoc :timeout-ms …)` glue
      (`ops.clj`) into `start-instance-in!`. Today `config_test` only asserts
      `resolved-start-readiness-timeout-ms` returns the value, and `started_test`
      only asserts `start-instance-in!` records a *directly-passed* `:timeout-ms`
      (90000) and the no-opts default (TR1, 120000); `ops_test/start-test` only
      exercises the missing-start-command path and TR2 sets `:readiness-timeout-ms`
      directly via `update-instance-in!`. A regression in the ops glue (dropped
      `assoc`, wrong config key, not reading from `cfg`) would pass all current
      tests. Add an `ops_test.clj` case that writes a project `.psi/project.edn`
      with both `:start-command` and a configured `:start-readiness-timeout-ms`
      (e.g. 90000), drives `ops/start` with a seeded `:runtime-handle` launcher
      seam (no mocks; file-backed `.nrepl-port`), and asserts the resulting
      instance/`status` carries `:readiness-timeout-ms 90000` — pinning the
      config-key → ops → opts → `start-instance-in!` resolution path. (Resolve the
      runtime-handle pre-seed vs `ensure-instance-in!` conflict-detection seam in
      the builder; the threading is the assertion target.)
      → Resolved: added `ops_test.clj` `start-config-timeout-threading-test`.
      Writes `<worktree>/.psi/project.edn`
      `{:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
      :start-readiness-timeout-ms 90000}}}`, pre-seeds the runtime-handle
      launcher/connector seam via `ensure-instance-in!` (matching
      `:started`/`:command-vector`/nil-`:endpoint`, `:lifecycle-state :starting`
      so `start-instance-in!`'s `ensure-instance-in!` matches the slot and keeps
      the seam — and `ops/start` does not short-circuit on `:readiness`), drives
      `ops/start`, and asserts both the `start` return and a follow-up `status`
      read project `:readiness-timeout-ms 90000`. Pins the config-key →
      `resolve-config` → `resolved-start-readiness-timeout-ms` → `cond-> opts`
      → `start-instance-in!` path. No mocks (real `live-fake-process` proxy +
      `fake-connector` seam, file-backed `.nrepl-port`). Coverage-only; no
      production change.
- [x] TR4: Pin the A1 "attach/shared-discovery accepts a stale port" semantics.
      The A1 acceptance criterion keeps the shared `config/read-dot-nrepl-port`
      primitive and `attach/resolve-attach-endpoint` *unchanged* — they must
      accept whatever `.nrepl-port` is present, with **no** mtime/launch gate
      (the gate lives only in `started.clj`). But every current attach/discovery
      test (`attach_test.clj` `resolve-attach-endpoint-test` fallback;
      `config_test.clj` `read-dot-nrepl-port-test`) writes a *freshly-spit* port
      and accepts it — none ages the file. A regression leaking an mtime gate
      into the shared primitive or attach-mode would still pass every test
      (fixtures only present fresh ports). Add a case (in `attach_test.clj` for
      `resolve-attach-endpoint`'s `.nrepl-port` fallback, and/or `config_test.clj`
      for `read-dot-nrepl-port`) that writes `.nrepl-port`, `setLastModified` to
      well before now (mirroring the started-mode `(- now 60000)` stale fixture),
      and asserts the endpoint is **still resolved** — symmetric to the
      started-mode stale-rejection tests, pinning the A1 separation against a
      future gate leak. Coverage-only (production verified by inspection).
      → Resolved: added two symmetric stale-acceptance cases mirroring the
      started-mode `(- now 60000)` fixture. `attach_test.clj`
      `resolve-attach-endpoint-test` gains "accepts a stale (old-mtime)
      `.nrepl-port` — no started-mode gate in attach" (spit + `setLastModified`
      to `(- now 60000)`, asserts `{:host "127.0.0.1" :port 7999 :port-source
      :dot-nrepl-port}` still resolves). `config_test.clj` `read-dot-nrepl-port-test`
      gains "accepts a stale (old-mtime) `.nrepl-port` — no started-mode gate in
      shared read" (asserts `{:port 7888 :port-source :dot-nrepl-port}` still
      reads). Both pin the A1 separation (gate lives only in `started.clj`)
      against a future gate leak into the shared/attach path.
- [x] TR5: Cover the plain deadline-timeout `:started-readiness` path — the
      original reproduction's failure mode. `wait-for-started-endpoint!`'s
      deadline branch has two outcomes: `:started-stale-port` (too-old port
      present — tested) and the plain `:started-readiness` else-branch (alive
      process, **no** `.nrepl-port` ever, deadline fires). The plain
      `:started-readiness` deadline path is the exact reproduction
      (`:phase :started-readiness`, `:timeout-ms`, no fresh port) and the
      headline criterion's named negative outcome, yet has no test: the only
      `:started-readiness` assertion (`#"exited before \.nrepl-port"`) hits the
      **exit** branch, not the deadline branch. A regression mis-routing the
      deadline timeout to `:started-stale-port`, dropping `:timeout-ms`, or
      inverting the `(and endpoint (not fresh?))` guard would pass every test.
      Add a `wait-for-started-endpoint!` unit case: an **alive** `fake-process`,
      an **empty** temp dir (no `.nrepl-port`), a short `:timeout-ms`, asserting
      the thrown ex carries `:phase :started-readiness`, the configured
      `:timeout-ms`, and `:path`, and that `:command-exited?` is absent/false
      (distinguishing it from the exit branch). Coverage-only (production
      verified by inspection); symmetric to the `:started-stale-port` deadline
      test.
      → Resolved: added `wait-for-started-endpoint-test` case "plain deadline
      timeout (alive process, no .nrepl-port) reports :started-readiness (TR5)".
      An **alive** `fake-process`, an **empty** temp dir (no `.nrepl-port`), a
      `:timeout-ms 100` / `:poll-interval-ms 10`, asserting the thrown
      `ExceptionInfo` carries `:phase :started-readiness`, `:timeout-ms 100`,
      `:path` = `<dir>/.nrepl-port`, `:command-exited?` absent/false (alive
      process → distinct from the exit branch), and the "Timed out waiting for
      started project nREPL" message (distinct from the stale-port deadline's
      "only a stale port was present"). Pins the deadline else-branch — the
      design reproduction's exact failure mode — so a regression mis-routing it
      to `:started-stale-port`, dropping `:timeout-ms`, or inverting the
      `(and endpoint (not fresh?))` guard fails green. Coverage-only; no
      production/doc/CHANGELOG change.

## Test-shaper review follow-ups (shape)

- [x] TS1: Lift one parameterised `fake-process` `java.lang.Process` proxy into
      `test_support.clj` and delete `ops_test/live-fake-process` (a strict
      special case: alive / exit 0 / pid 4321). Have `ops_test` call the shared
      helper with `{:alive? true :exit-code 0 :pid 4321}`. Single-sources the
      16-method proxy ceremony and keeps the two files consistent
      (`consistent(fixtures)` ∧ `helpers_that_compress(ceremony)`). Shape-only;
      behaviour-preserving (no assertion change). Re-run the
      `started/ops/config/attach` suite + clj-kondo after.
      → Resolved: moved the parameterised 16-method `Process` proxy into
      `test_support/fake-process` (`{:alive? :exit-code :pid :destroyed*}`);
      `started_test` now `:refer`s it (deleted its local copy); `ops_test`
      deleted `live-fake-process` and calls `(fake-process {:alive? true
      :exit-code 0 :pid 4321})`. clj-kondo 0/0; `started/ops/config/attach`
      17 tests/113 assertions green (unchanged).
- [x] TS2: Add a `test_support.clj` helper naming the stale-port fixture
      (e.g. `spit-stale-port!`/`age-file-back!`, defaulting to the `60000` ms
      offset) and replace the six open-coded
      `(.setLastModified port-file (- (System/currentTimeMillis) 60000))` sites
      in `started_test` (×4), `config_test`, and `attach_test`. Single-sources
      the staleness convention so readers see intent, not bare arithmetic
      (`consistent(test_abstractions)` ∧ economy). Shape-only;
      behaviour-preserving.
      → Resolved: added `test_support` `age-file-back!` (set mtime `60000` ms —
      private `stale-port-offset-ms` — before now) and `spit-stale-port!`
      (write port + age). Replaced all six sites: `started_test` ×4
      (two via `spit-stale-port!` dropping the now-redundant `port-file` let
      binding; the pre-launch-removal seed + PA4 launcher via the helpers),
      `attach_test` + `config_test` via `age-file-back!` (kept the explicit
      `spit` so the asserted port value stays at the call site). No bare
      `setLastModified (- … 60000)` remains.
- [x] TS3 (judgement, optional hardening): Make the
      `wait-for-started-endpoint-stale-port-gate-test` "accepts a fresh
      .nrepl-port" case assert the mtime≥floor relation by construction
      (`setLastModified` to a known-fresh instant) rather than relying on the
      real-FS same-second wall-clock landing after `spit`. Removes the residual
      timing coupling; the whole-second floor (AMB4) means it is not flaky today,
      so treat as optional robustness hardening — consider alongside a TS2 helper
      that can also set a fresh mtime. Shape-only; behaviour-preserving.
      → Resolved: added `test_support/touch-fresh!` (set mtime `+1000` ms after
      now, the TS2-companion fresh setter) and called it in the "accepts a fresh
      .nrepl-port" case after `spit`, so the mtime≥floor accept relation is
      explicit by construction — symmetric to the reject cases' aging — instead
      of relying on the same-second wall-clock landing.

## Test-shaper review follow-ups (shape, pass 2)

- [x] TS4: Strengthen the under-asserted `:started-at` launch-instant contract
      in `started_test`'s "records the effective :readiness-timeout-ms and
      launch-instant :started-at" case. Today it asserts only
      `(instance? java.time.Instant (get-in instance [:runtime-handle
      :started-at]))` — a bare type check that a PA2 regression (re-adding the
      removed post-wait `:started-at (now)` connect-time write) would still pass.
      Assert the *provenance*: have the launcher seam capture the instant it is
      invoked (the true launch site) and assert the instance's `:started-at`
      equals (or is `≤`) that launcher-observed instant, and/or bracket the call
      with `before`/`after` wall-clock and assert `:started-at` falls within and
      precedes connect completion. Pins "started-at = launch instant, not connect
      instant" (PA2/INC1) so a regression fails green. Coverage/assertion-only;
      production verified by inspection.
- [x] TS5 (judgement, optional): Split the bundled "records the effective
      :readiness-timeout-ms and launch-instant :started-at" case into two named
      `testing` blocks (effective-timeout recording vs `:started-at` launch-instant
      provenance) so a failure names which contract broke (`single_concern` ∧
      `meaningful_failures`). Fold into TS4 if convenient. Shape-only;
      behaviour-preserving.
      → Resolved (folded with TS4): split the bundled case into two named
      `testing` blocks — "records the effective :readiness-timeout-ms" (asserts
      `:readiness-timeout-ms 90000`) and "records :started-at = launch instant,
      not connect instant (TS4/PA2)" (the strengthened provenance check). A
      failure now names which contract broke.

## Test-shaper review follow-ups (shape, pass 3)

- [x] TS6: Single-source the duplicated happy started-launcher arrange. Four
      `start-instance-in-test` cases ("launches command…", "no :timeout-ms opts…
      120000 default (TR1)", "records the effective :readiness-timeout-ms",
      "records :started-at = launch instant (TS4/PA2)") plus `ops_test`'s
      `start-config-timeout-threading-test` each open-code the same launcher
      (`(spit … ".nrepl-port" "7777\n")` + happy `(fake-process {:alive? true
      :exit-code 0 :pid 4321})`) and `(fake-connector "nrepl-session-1")`. Add a
      `test_support` helper (e.g. `started-launcher!`, default port `7777` pid
      `4321`) so each case calls it and asserts only its distinct contract,
      removing the `minimal_incidental_setup` repetition and the
      `consistent(fixtures)` drift risk of hand-propagating the `"7777"`/`:pid
      4321` literals across ≥5 sites. Keep the TS4 case's `launcher-at` capture —
      the helper must compose with (not hide) an injected pre-write hook or hand
      back the launcher for the caller to wrap
      (`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`).
      Shape-only; behaviour-preserving (no assertion change). Re-run
      started/ops/config/attach + clj-kondo after.
  → Resolved: added `test_support/started-launcher!` — the canonical happy
  started-mode `:process-launcher` (writes a fresh `<worktree>/.nrepl-port`
  default port `7777`, returns a happy `fake-process` default pid `4321`), with
  an `:on-launch` pre-write hook so the TS4 provenance case composes its
  `launcher-at` capture without re-open-coding the launcher
  (`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`). Rewired the
  four `start-instance-in-test` happy cases ("launches command…", TR1 no-opts
  120000 default, "records the effective :readiness-timeout-ms", TS4 "records
  :started-at = launch instant") + `ops_test`'s
  `start-config-timeout-threading-test` onto the helper; dropped each site's
  open-coded launcher and the now-unused `fake-process` binding/refer in
  `ops_test`. The `"7777"`/`:pid 4321` literals are now single-sourced. The
  stale/exit/deadline cases (which need non-happy or stale-port launchers) keep
  their bespoke launchers. Shape-only/behaviour-preserving: started/ops/config/
  attach 17 tests/115 assertions green (unchanged); clj-kondo 0/0;
  clj-paren-repair Success; files 185/273/134 (< 800); file-lengths exit 0.
