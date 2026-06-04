# Steps — 210 Fix `project-repl op=start` readiness/stale-port

## Slice 1 — Config surface (timeout key + validation)

- [ ] Add `resolved-start-readiness-timeout-ms` to `config.clj`: read
      `[:project-nrepl :start-readiness-timeout-ms]`; `nil` when unset;
      `cond`-style range check `[1000 600000]` (integer) mirroring
      `resolved-attach-endpoint`; throw `ex-info` `{:phase :validate}` on
      non-integer / out-of-range.
- [ ] Add `config_test.clj` cases: unset → `nil`; valid in-range → value;
      below 1000 → throws `:phase :validate`; above 600000 → throws; non-integer
      → throws.
- [ ] Run `clojure -X:test` (project-nrepl) + `clj-kondo --lint` on changed
      files; commit slice 1.

## Slice 2 — Timeout threading + raised default + payload projection

- [ ] Raise `default-readiness-timeout-ms` in `started.clj` `5000 → 120000`.
- [ ] In `ops/start`, resolve the timeout via
      `resolved-start-readiness-timeout-ms` from the already-resolved `cfg`, and
      pass `start-instance-in!` an `opts` map carrying `:timeout-ms` (only when
      non-nil; nil falls back to the started.clj default).
- [ ] In `start-instance-in!`, set instance status field
      `:readiness-timeout-ms` (the effective resolved timeout, defaulted to
      `default-readiness-timeout-ms` when `opts` omits it) when the launch
      begins, alongside the existing status fields.
- [ ] Extend `ops/instance-payload`'s fixed key list with
      `:readiness-timeout-ms` (AMB3).
- [ ] Add tests: configured timeout flows into `wait-for-started-endpoint!`'s
      effective deadline (via the seam); `instance-payload` includes
      `:readiness-timeout-ms`; fast happy path still reaches `:started`.
- [ ] Run tests + lint; commit slice 2.

## Slice 3 — Stale-port ownership guard

- [ ] Grep for consumers of the runtime-handle `:started-at` to confirm none
      depend on its current post-wait (connect-time) capture before moving it.
- [ ] In `start-instance-in!`, capture a single `launched-at (now)` binding at
      the launch site (immediately before `(launcher …)`).
- [ ] Use `launched-at` for the runtime-handle `:started-at` (remove the
      post-wait `:started-at (now)`), so there is one launch-instant source.
- [ ] In `start-instance-in!`, delete any existing
      `<worktree>/.nrepl-port` immediately before launching.
- [ ] Thread `launched-at` into `wait-for-started-endpoint!` via
      `opts {:launched-at …}`.
- [ ] In `wait-for-started-endpoint!`, gate acceptance: read the
      `.nrepl-port` file's last-modified time; accept only when
      `≥ (launched-at floored to whole seconds)` (AMB4). Treat a present
      too-old port as not-yet-ready (continue polling).
- [ ] On deadline with only a too-old port present, throw
      `ex-info` `:phase :started-stale-port` carrying rejected mtime +
      `launched-at` (so it lands on `:last-error → :data` via the existing
      `catch`), distinct from `:started-readiness`.
- [ ] Add tests: pre-existing too-old `.nrepl-port` is rejected (stale-port
      phase on deadline / continues polling); a fresh port written after launch
      is accepted; pre-launch removal occurs.
- [ ] Add/confirm a test asserting attach-mode discovery and
      `config/read-dot-nrepl-port` are behaviour-preserved (no stale gate).
- [ ] Run tests + lint; commit slice 3.

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
