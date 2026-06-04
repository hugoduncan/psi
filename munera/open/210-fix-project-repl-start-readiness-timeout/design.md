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

## Open questions (resolve collaboratively before plan)

- **Q1 — Timeout configurability surface.** A new config key under
  `[:agent-session :project-nrepl]` (e.g. `:start-readiness-timeout-ms`) with a
  raised default, vs. only raising the default, vs. an explicit `op` arg on
  psi-tool. Preference: config key + sensible raised default, following the
  existing `:start-command` / `:attach` config precedence (system < user <
  project). Confirm name, default value, and validation bounds.
- **Q2 — Stale-port strategy.** Options:
  (a) **Remove** any existing `.nrepl-port` immediately before launch, then wait
      for it to (re)appear — simplest, but destroys a file the running process
      may legitimately own if a different start raced.
  (b) **mtime/launch-time gate** — record launch instant, only accept a
      `.nrepl-port` whose last-modified ≥ launch instant.
  (c) Combination. Confirm which guarantees "the port belongs to *this* launch"
      without breaking the existing happy path or attach-mode discovery.
- **Q3 — Is the 5s timeout the actual failure here, the stale port, or both?**
  Confirm empirically before fixing: at reproduction time a `.nrepl-port`
  existed in the worktree (port 64474) belonging to an unrelated REPL, yet start
  reported a *timeout* — clarify whether discovery never saw that file (so the
  timeout is the real cause) or the diagnosis differs. The fix must address the
  confirmed cause(s); avoid speculative changes.
- **Q4 — Process-exit diagnostics.** When the child exits early
  (`process-exited?`), the error already surfaces `:exit-code` but not stdout/
  stderr. Decide whether capturing a tail of child output for the error payload
  is in scope (it materially aids diagnosing real start-command failures) or
  deferred.

## Acceptance criteria

- `psi-tool {action: project-repl, op: start}` against a real, slow-booting
  `:start-command` (the `clojure -M …` case above) reaches `:status :started`
  /`:present` rather than `:started-readiness` timeout, given a reasonable
  default and/or configured timeout.
- A pre-existing stale `.nrepl-port` does not cause start to report success
  against the wrong endpoint; start connects only to the port written by the
  process it launched (or fails diagnosably).
- The readiness timeout is controllable through the confirmed Q1 surface, with
  validation consistent with existing `project-nrepl` config validation.
- Behaviour-preserving for the existing fast happy path and for attach-mode.
- Tests cover: timeout configurability, the stale-port guard, and the
  unchanged happy path — no `with-redefs`, using the existing
  `process-launcher` / `nrepl-connector` seams.
- User-visible config additions documented in `doc/` and CHANGELOG.
```
