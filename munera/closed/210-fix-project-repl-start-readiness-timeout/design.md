# 210 — Fix `psi-tool project-repl op=start` readiness error

## Intent

`psi-tool` `project-repl` with `op: start` fails for real-world start commands.
Make managed started-mode project nREPL acquisition succeed for slow-to-boot
start commands and stop it from latching onto a stale `.nrepl-port`.

## Observed behaviour (reproduction)

In this worktree, with project config
(`.psi/project.edn`):

```edn
{:agent-session
 {:project-nrepl
  {:start-command ["clojure" "-M:run:dev:test-paths" "--nrepl"]}}}
```

`psi-tool {action: project-repl, op: start}` returns:

```edn
{:psi-tool/overall-status :error
 :psi-tool/error
 {:message "Timed out waiting for started project nREPL .nrepl-port"
  :phase :started-readiness
  :data {:timeout-ms 5000
         :path ".../.nrepl-port"}}}
```

## Root causes (to confirm during design)

Two distinct defects in `components/project-nrepl`:

1. **Hard-coded, too-short readiness timeout.**
   `ops/start` calls `started/start-instance-in! ctx worktree-path command-vector`
   with **no `opts`**, so `wait-for-started-endpoint!` always uses
   `default-readiness-timeout-ms` = `5000`. A cold `clojure -M …` JVM + nREPL
   boot (classpath build, AOT, nREPL/cider middleware load) routinely exceeds 5s,
   so start fails before the child writes `.nrepl-port`. There is no config or
   call-site path to raise the timeout.

2. **Stale `.nrepl-port` race / false-positive readiness.**
   `wait-for-started-endpoint!` returns the *first* `.nrepl-port` it can parse.
   If a stale `.nrepl-port` already exists in the worktree (left by a prior
   process, an unrelated REPL, or a crashed run), start latches onto a port that
   is not the freshly-launched process — a silent wrong-endpoint connection.
   There is no sentinel distinguishing the new process's port file from a
   pre-existing one (e.g. mtime/launch-time gate, or pre-launch removal).

## Scope

In scope:

- `components/project-nrepl` started-mode acquisition: `ops/start`,
  `started/start-instance-in!`, `started/wait-for-started-endpoint!`, and
  `config` if a new config key is introduced.
- The readiness-timeout configurability path (config → `ops/start` → opts).
- The stale-`.nrepl-port` correctness guard.
- Tests under `components/project-nrepl/test` exercising both fixes without
  mocks (per the project's testing-without-mocks standard; task 108 precedent).
- User docs (`doc/`) and CHANGELOG for any user-visible config surface.

Out of scope:

- Attach-mode acquisition (`attach.clj`) except where it shares the discovery
  helper — do not change attach semantics.
- The `psi-tool` / slash-command surface shape itself (no new ops/args beyond a
  possible config key).
- nREPL session/eval behaviour after a successful connect.

## Resolved questions (Q1–Q4)

- **Q1 — Timeout configurability surface (resolved).**
  Add a config key `:start-readiness-timeout-ms` under
  `[:agent-session :project-nrepl]`, following the existing `:start-command` /
  `:attach` precedence (system < user < project). It carries a **raised default
  of `120000` ms (120 s)** — a cold `clojure -M …` JVM + classpath build + nREPL/
  cider middleware load can take well over the prior 5 s, and the default must
  succeed for a real slow boot out of the box. **Validation bounds:
  `[1000 600000]`** (1 s–10 min), validated in `config.clj` in the same
  `cond`-style integer-range idiom as `resolved-attach-endpoint`'s port check;
  out-of-range or non-integer values throw a `:phase :validate` `ex-info`.
  Resolution path: `config` → `ops/start` builds `opts` `{:timeout-ms …}` →
  `started/start-instance-in!` → `wait-for-started-endpoint!`. No new psi-tool
  `op` arg (no surface-shape change, per scope).
  - `default-readiness-timeout-ms` in `started.clj` is raised from `5000` to
    `120000` so the no-config call path also gets the safe default; the config
    key only overrides it.

- **Q2 — Stale-port strategy (resolved; this is the resolution of A1's gate).**
  Use the **combination (option c): pre-launch removal *plus* a launch-instant
  mtime acceptance gate**, both in the started-mode layer (A1):
  1. **Pre-launch removal.** Immediately before launching the process,
     `start-instance-in!` deletes any existing `<worktree>/.nrepl-port`. This
     makes any subsequently-observed `.nrepl-port` necessarily a *new* file, so
     correctness does not depend on mtime precision.
  2. **Mtime acceptance gate (defence in depth).** The launch instant is
     **captured in `start-instance-in!` at the moment of launch** (it is the
     existing `:started-at` runtime-handle value, captured at launch rather than
     post-wait — see INC1) and **threaded into `wait-for-started-endpoint!` via
     `opts` (`:launched-at`)**. `wait-for-started-endpoint!` only accepts a
     `.nrepl-port` whose last-modified time is **≥ (launch-instant floored to
     whole seconds)**. The whole-second floor tolerates coarse filesystem mtime
     granularity (see AMB4) so a legitimately-fresh port written in the same
     second as launch is not rejected; the pre-launch removal guarantees
     correctness even when the gate is lenient. `wait-for-started-endpoint!`
     never self-times the launch instant — by the time it runs the launch has
     already happened in its caller, so a self-timed gate would reference its own
     entry time, not the true launch (INC1).
  This guarantees "the port belongs to *this* launch" without touching the
  shared discovery primitive or attach-mode (A1). A `.nrepl-port` failing the
  gate (present but older than the launch floor) is treated as stale: the poll
  loop continues until the gate passes or the deadline fires; a deadline hit
  while only a too-old port is present is reported as a stale-port rejection
  (`:phase :started-stale-port`, A2) rather than a plain readiness timeout.

- **Q3 — Confirmed scope of the fix (resolved): both fixes are required
  unconditionally.** The reproduction (`:phase :started-readiness`,
  `:timeout-ms 5000`, a port `64474` present in the worktree) is consistent with
  *either or both* defects: discovery may have read a parseable stale port (then
  the bug is silent wrong-endpoint, masked here only because connect was not yet
  reached) **or** never observed it before the 5 s deadline (then the bug is the
  timeout). Because the two failure modes are not reliably distinguishable from a
  single observation and each is an independent latent defect, **both fixes ship
  regardless of which cause a given reproduction exhibits.** A single-cause
  empirical finding does **not** scope either fix out. "Avoid speculative
  changes" is satisfied: both changes target defects already proven present by
  code inspection (hard-coded 5 s with no config path; first-parseable-port
  acceptance with no launch-ownership check), not speculative ones.

- **Q4 — Process-exit diagnostics (resolved: deferred, out of scope).**
  Capturing a stdout/stderr tail in the `:exit-code` error payload is **deferred
  to a follow-up task**. It is orthogonal to both in-scope defects (it improves
  diagnosis of a *failing* start command, not the timeout or stale-port
  correctness bugs) and would require process-output capture plumbing
  (`ProcessBuilder` redirection / reader drain) that broadens scope. The existing
  `:exit-code` on the early-exit error path is unchanged.

## Architectural-fit decisions (from design review)

- **A1 — Stale-port guard placement (started-mode only).**
  The stale-`.nrepl-port` correctness guard lives entirely in the **started-mode
  acquisition layer** (`started.clj`), not in the shared discovery primitive
  `config/read-dot-nrepl-port` (used by both started and attach modes). Concretely:
  - `read-dot-nrepl-port` stays a pure, mode-agnostic "read+validate the current
    `.nrepl-port`" primitive (single responsibility) — unchanged. Attach-mode's
    documented `.nrepl-port` fallback (`attach/resolve-attach-endpoint`) keeps its
    current semantics: it accepts whatever `.nrepl-port` is present, by design,
    because an attach target is an externally-owned, already-running REPL.
  - The stale-port strategy is the resolved **Q2 combination** (pre-launch
    removal + launch-instant mtime gate), implemented entirely in the
    started-mode layer:
    - `start-instance-in!` deletes any existing `<worktree>/.nrepl-port`
      immediately before launching the process, so any observed port file is
      necessarily new.
    - **Launch-instant ownership (INC1).** `start-instance-in!` is the sole owner
      of the true launch instant: it captures `(now)` at the moment it launches
      the process (`(launcher …)`), and this single instant is **both** the
      runtime-handle `:started-at` value **and** the mtime-gate reference. It is
      threaded into `wait-for-started-endpoint!` via `opts` (`:launched-at`).
      Today `start-instance-in!` writes `:started-at (now)` onto the
      runtime-handle on the success path *after* `wait-for-started-endpoint!`
      returns; this task moves that capture **to the launch site** (a single
      `launched-at` binding taken immediately before/at `(launcher …)`) and
      reuses it for both `:started-at` and the gate, so there is exactly one
      launch-instant source. `wait-for-started-endpoint!` does **not** record the
      launch instant itself (it runs after the launch and could only capture its
      own entry time); it consumes the threaded `:launched-at`. The polling loop
      still owns the rest of the started-process lifecycle (`process-exited?`,
      deadline) and applies the gate against the passed-in instant.
  - This keeps the started-mode acquisition *policy* (the Q2 stale-port strategy,
    of which A1 is the single authoritative statement) out of the orthogonal
    discovery *mechanism*, satisfying single-responsibility and leaving
    attach-mode untouched. Q2 in "Resolved questions" and this A1 are one
    decision, not two: A1 is the resolution of Q2.

- **A2 — Observable status projection through the registry instance.**
  New observable started-mode outcomes are projected as canonical instance
  status on the runtime-owned project-nrepl **registry instance**, consistently
  with the existing `:readiness` / `:last-error` projection, rather than as
  ad-hoc op-return-only data. Specifically:
  - The project-nrepl registry is a runtime handle (subprocess launch + `.nrepl-port`
    polling are documented runtime-owned I/O per the Layer Map / Frontier; they do
    not move under dispatch effects). The instance map in `runtime.clj` is the
    canonical status surface; `ops/instance-payload` is its read projection.
  - **Stale-port rejection** is recorded on the instance via the existing
    `:last-error` field on the failure path (`start-instance-in!`'s `catch`
    already writes `:lifecycle-state :failed` + `:last-error {:message :data :at}`);
    the rejection's `ex-data` carries a distinct `:phase :started-stale-port`
    (vs `:started-readiness`) plus the rejected/launch instants, so the
    diagnostic is observable from the instance, not only the op return.
  - **Effective configured timeout** is recorded on the instance as a new
    `:readiness-timeout-ms` status field set when the launch begins (alongside
    the existing instance status fields), and is surfaced through
    `instance-payload` so the resolved timeout is observable as instance status
    rather than inferred from config or returned only ad hoc.
  - **`instance-payload` projection is extended (AMB3).** `instance-payload`
    (`ops.clj`) today projects a *fixed* key set
    (`:worktree-path :acquisition-mode :lifecycle-state :readiness :endpoint
    :active-session-id :last-eval :last-error`) and would otherwise drop
    `:readiness-timeout-ms`. This task **adds `:readiness-timeout-ms` to that
    projected key list** so "surfaced through `instance-payload`" means a
    concrete change to the projection, with a single interpretation. No
    additional stale-port diagnostic key is added: the stale-port rejection rides
    the already-projected `:last-error` (its `ex-data` carries
    `:phase :started-stale-port` and the rejected/launch instants), so no new
    projected key is needed for it.
  - `ops/start`'s return shape continues to project from `instance-payload`, so
    the op return is a derived view of the canonical instance status (no new
    op-only status channel).

## Acceptance criteria

- `psi-tool {action: project-repl, op: start}` against a real, slow-booting
  `:start-command` (the `clojure -M …` case above) reaches `:status :started`
  /`:present` rather than `:started-readiness` timeout, with the **raised
  `120000` ms default** and no per-call config required.
- A pre-existing stale `.nrepl-port` does not cause start to report success
  against the wrong endpoint; start connects only to the port written by the
  process it launched (or fails diagnosably). The stale-port acceptance gate is
  implemented in the started-mode layer (`started.clj`), leaving the shared
  `config/read-dot-nrepl-port` discovery primitive and attach-mode discovery
  unchanged (A1).
- Started-mode observable outcomes (stale-port rejection diagnostic carried on
  `:last-error`'s `:data`/ex-data as `:phase :started-stale-port` — matching
  `start-instance-in!`'s `catch`, which writes `:last-error {:message :data :at}`
  with the thrown `ex-data` under `:data`; effective configured timeout via a new
  `:readiness-timeout-ms` field) are projected as canonical status on
  the project-nrepl registry instance and surfaced through `instance-payload`
  (whose projected key list is extended with `:readiness-timeout-ms`, AMB3),
  consistently with the existing `:readiness` / `:last-error` projection — not as
  op-return-only data (A2).
- The readiness timeout is controllable through the
  `[:agent-session :project-nrepl :start-readiness-timeout-ms]` config key (Q1),
  with `[1000 600000]` ms integer-range validation in `config.clj` consistent
  with the existing `resolved-attach-endpoint` port-range idiom.
- Both the timeout raise and the stale-port gate ship unconditionally; neither is
  scoped out by a single-cause empirical finding (Q3).
- Behaviour-preserving for the existing fast happy path and for attach-mode.
- Tests cover: timeout configurability, the stale-port guard, and the
  unchanged happy path — no `with-redefs`, using the existing
  `process-launcher` / `nrepl-connector` seams.
- User-visible config additions documented in `doc/` and CHANGELOG.
```
