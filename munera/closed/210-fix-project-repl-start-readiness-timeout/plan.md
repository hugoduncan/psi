# Plan — 210 Fix `project-repl op=start` readiness/stale-port

## Approach

Two independent, unconditionally-shipped started-mode defect fixes (Q3), both
confined to `components/project-nrepl` and projected as canonical instance
status (A2). No psi-tool surface-shape change.

### Fix 1 — configurable, raised readiness timeout (Q1)

- New config key `[:agent-session :project-nrepl :start-readiness-timeout-ms]`,
  precedence system < user < project (existing `resolve-config` deep-merge).
- Add `resolved-start-readiness-timeout-ms` to `config.clj`, validated with the
  `resolved-attach-endpoint` `cond`-style integer-range idiom:
  - non-integer or out of `[1000 600000]` → `ex-info` `{:phase :validate}`
  - unset → `nil` (caller falls back to the started.clj default)
- Raise `default-readiness-timeout-ms` in `started.clj` `5000 → 120000` so the
  no-config call path is safe by default; the config key only overrides it.
- Resolution path: `ops/start` resolves the config value, builds
  `opts {:timeout-ms …}`, passes it to `start-instance-in!` →
  `wait-for-started-endpoint!` (already reads `:timeout-ms` from `opts`).

### Fix 2 — stale-`.nrepl-port` ownership guard (Q2 = A1, combination c)

Entirely in `started.clj` (A1); `config/read-dot-nrepl-port` and attach-mode
untouched.

- **Launch-instant ownership (INC1).** `start-instance-in!` captures a single
  `launched-at (now)` binding at the launch site (immediately before/at
  `(launcher …)`). This one instant becomes **both** the runtime-handle
  `:started-at` (moved from the post-wait success path to the launch site)
  **and** the mtime-gate reference threaded into `wait-for-started-endpoint!`
  via `opts {:launched-at …}`. `wait-for-started-endpoint!` never self-times.
- **Pre-launch removal.** `start-instance-in!` deletes any existing
  `<worktree>/.nrepl-port` immediately before launching, so any observed port
  file is necessarily new (makes correctness independent of mtime precision).
- **Mtime acceptance gate (defence in depth).** `wait-for-started-endpoint!`
  accepts a `.nrepl-port` only when its last-modified time is
  `≥ (launched-at floored to whole seconds)` (AMB4 tolerance for coarse FS
  mtime). A present-but-too-old port is treated as stale: the poll loop
  continues; a deadline hit while only a too-old port exists is reported as
  `:phase :started-stale-port` (A2) rather than `:started-readiness`.

### A2 — observable status projection

- New instance status field `:readiness-timeout-ms`, set when the launch begins
  (the effective resolved timeout).
- Extend `ops/instance-payload`'s fixed projected key list with
  `:readiness-timeout-ms` (AMB3).
- Stale-port rejection rides the existing `:last-error` failure-path projection
  (`start-instance-in!`'s `catch` writes `{:message :data :at}`); the thrown
  `ex-data` carries `:phase :started-stale-port` + rejected/launch instants,
  landing under `:last-error → :data` (INC2). No new projected key for it.

### Plan/steps review resolutions (PA1–PA4)

Grounded against `started.clj`, `runtime.clj`, `ops.clj`, `started_test.clj`.
The unifying mechanism is a **single new launch-site `update-instance-in!`** in
`start-instance-in!`, placed *after* `ensure-instance-in!` and *immediately
before* `(launcher …)` / `wait-for-started-endpoint!`, that writes the
launch-time status fields so they survive the throwing failure path.

- **PA1 — `:readiness-timeout-ms` write site.** The effective resolved timeout
  is written by the new launch-site `update-instance-in!` (a top-level instance
  status key), **before** `wait-for-started-endpoint!` runs. It is *not* seeded
  via `ensure-instance-in!` (whose match-key set is fixed and matters for
  conflict detection) and *not* deferred to the post-wait success
  `update-instance-in!`. Because it is written pre-wait, it is present on the
  instance even when the wait throws (timeout / stale-port), so the diagnostic
  timeout is observable on a *failure* via a `status` read (see PA4). The
  effective resolved timeout = `(:timeout-ms opts)` when present, else
  `default-readiness-timeout-ms` — the same fallback `wait-for-started-endpoint!`
  applies — so the recorded value matches the wait's actual deadline basis.

- **PA2 — `:started-at` move mechanism.** The same launch-site
  `update-instance-in!` captures one `launched-at (now)` local and writes it as
  the runtime-handle `:started-at` (`update :runtime-handle merge {:started-at
  launched-at}`). The existing **post-wait** success-path `:started-at (now)`
  write is **removed**, so `:started-at` is the launch instant (not the connect
  instant) and survives the failure path. `build-instance`'s top-level
  `:started-at (now)` (the slot-creation timestamp, a distinct field from the
  runtime-handle `:started-at`) is left unchanged — only the runtime-handle
  capture moves. `launched-at` is also threaded into `wait-for-started-endpoint!`
  via `opts {:launched-at …}` as the mtime-gate reference, giving one
  launch-instant source (INC1).

- **PA3 — gate-rejection test level.** Pre-launch `.nrepl-port` removal in
  `start-instance-in!` deletes any pre-existing port before `(launcher …)`, so
  through `start-instance-in!` the gate only ever sees fresh launcher-written
  ports. The **mtime-gate rejection case is therefore exercised at the
  `wait-for-started-endpoint!` unit level** with an injected `:launched-at` and a
  pre-written too-old `.nrepl-port` (bypassing removal). The
  `start-instance-in!`-level test asserts only that **pre-launch removal occurs**
  (a pre-seeded port is gone before the launcher writes the fresh one) and the
  fresh post-launch port is accepted — it does *not* attempt to assert
  gate-rejection through `start-instance-in!`.

- **PA4 — failure-path diagnostic surface.** On the stale-port/timeout failure
  path `start-instance-in!` *throws*, so the `start` op return never reaches
  `instance-payload`. The launch-time diagnostics (`:readiness-timeout-ms`;
  stale-port rejection under `:last-error → :data` with
  `:phase :started-stale-port`) are observable via a separate **`status` (op)
  read** of the instance, not via the `start` op return. A `status`-read
  acceptance test confirms the failure-path instance carries
  `:readiness-timeout-ms` and the stale-port `:last-error` phase.

### Tests (no mocks; existing `process-launcher`/`nrepl-connector` seams)

- timeout configurability (config resolution + range validation + opts threading)
- stale-port guard (pre-existing too-old port rejected; fresh port accepted)
- unchanged fast happy path + attach-mode behaviour-preserving

### Docs

- `doc/project-nrepl.md`: document `:start-readiness-timeout-ms` (default 120000,
  range [1000 600000]) in the started-mode config section.
- `CHANGELOG.md` `[Unreleased] → Added/Fixed`: user-visible config key + the
  slow-boot/stale-port fixes.

## Risks

- **Long default (120 s) masking a genuinely-dead start command.** Mitigated:
  `process-exited?` short-circuits the wait on early process exit, so a command
  that dies returns immediately rather than waiting out the timeout.
- **Mtime granularity / clock skew.** Mitigated by the whole-second floor
  (AMB4) plus pre-launch removal making correctness independent of the gate.
- **`:started-at` capture move.** Moving the runtime-handle `:started-at` from
  the post-wait success update to the new launch-site `update-instance-in!`
  (PA2) is a semantic shift (now = launch instant, not connect instant);
  confirm no consumer depends on the post-wait timing. Grep before changing.
- **Config resolution placement in `ops/start`.** `ops/start` currently calls
  `start-instance-in!` with no `opts`; ensure the resolved timeout is threaded
  without changing the missing-start-command path.

## Slice order

Vertical, smallest-first; each slice keeps the suite green.

1. **Config surface** — `resolved-start-readiness-timeout-ms` + range validation
   in `config.clj`, with tests. (No behaviour change to start yet.)
2. **Timeout threading + raised default** — raise `default-readiness-timeout-ms`;
   thread resolved timeout through `ops/start` → `start-instance-in!` → opts;
   record `:readiness-timeout-ms` on the instance via a new pre-wait launch-site
   `update-instance-in!` (PA1); extend `instance-payload`. Tests for
   configurability + payload projection + happy path + a `status`-read of the
   failure-path instance carrying `:readiness-timeout-ms` (PA4).
3. **Stale-port guard** — launch-instant ownership move: the runtime-handle
   `:started-at` is written at the new launch-site `update-instance-in!` from a
   `launched-at` local (post-wait `:started-at` removed, PA2); pre-launch
   `.nrepl-port` removal; threaded `:launched-at`; mtime gate;
   `:phase :started-stale-port` deadline rejection. Tests: unit-level gate
   rejection in `wait-for-started-endpoint!` with injected `:launched-at` (PA3);
   `start-instance-in!`-level pre-launch removal + fresh acceptance; attach-mode
   unchanged.
4. **Docs + CHANGELOG** — `doc/project-nrepl.md` + `[Unreleased]` entries.
5. **Coherence pass** — full suite + clj-kondo; verify acceptance criteria.
