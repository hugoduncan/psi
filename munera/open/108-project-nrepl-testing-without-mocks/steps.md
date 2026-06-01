# 108 — Steps

Rewritten 2026-06-01 to match the stabilised design and slice order in plan.md.

## Slice 1 — Audit + source confirmation

- [ ] Re-read the six in-scope tests and confirm `with-redefs` sites: `config_test.clj` (`read-user-config`, `read-project-preferences`), `client_test.clj` (`nrepl.core/connect|client|client-session`), `attach_test.clj` (`connect-instance-in!`), `started_test.clj` (`start-process!`, `connect-instance-in!`), `commands_test.clj` (`ops/eval-op`, `ops/interrupt`), `ops_test.clj` (`eval/eval-instance-in!`)
- [ ] Confirm `eval_test.clj` `[:runtime-handle :client-session]` idiom and `install-instance!` pattern as the reuse template
- [ ] Verify in `client.clj` the inline `requiring-resolve` connect/client/session block and the session-id derivation + throw in `connect-instance-in!`
- [ ] Verify `connect-instance-in!` `merge`s into existing `:runtime-handle`
- [ ] Verify `start-instance-in!` currently **overwrites** `:runtime-handle` with process keys
- [ ] Verify `ensure-instance-in!` forwards `:runtime-handle` to `build-instance` only via `:as opts` passthrough (not its own `:keys`)
- [ ] Grep for all callers of `attach-instance-in!` and `start-instance-in!` to bound the signature-change blast radius

## Slice 2 — `:nrepl-connector` seam (production)

- [ ] Promote the inline `requiring-resolve` connect/client/session block in `client.clj` into a real-default connector fn returning `{:transport :client :client-session}`
- [ ] Resolve the connector via `(or (get-in instance [:runtime-handle :nrepl-connector]) <real-default>)` in `connect-instance-in!`
- [ ] Keep session-id derivation (`session-fn` meta `:nrepl.core/taking-until :session`) and the throw-on-missing-session-id behavior in `connect-instance-in!`
- [ ] `clj-paren-repair` + targeted lint `client.clj`

## Slice 3 — `client_test.clj`

- [ ] Replace `nrepl.core/*` redefs with a seeded `[:runtime-handle :nrepl-connector]` fn returning a deterministic transport/client/session-fn (session-fn carrying `:nrepl.core/taking-until {:session ...}` metadata)
- [ ] Assert on connected-instance `:runtime-handle` state (`:transport :client :client-session :session-id`)
- [ ] Run `client_test.clj` green

## Slice 4 — Composite seed param (production)

- [ ] Add optional `:runtime-handle` seam-seed to `attach-instance-in!`; thread into its `ensure-instance-in!` call (`{:worktree-path … :acquisition-mode :attached :endpoint … :runtime-handle <seed>}`)
- [ ] Add optional `:runtime-handle` seam-seed (from opts) to `start-instance-in!`; thread into its `ensure-instance-in!` call (`{... :acquisition-mode :started :command-vector … :runtime-handle <seed>}`)
- [ ] Confirm real callers (no seed) observe unchanged behavior
- [ ] `clj-paren-repair` + targeted lint the changed namespaces

## Slice 5 — `attach_test.clj`

- [ ] Remove `connect-instance-in!` redefs; seed `[:runtime-handle :nrepl-connector]` via the new `attach-instance-in!` seed param
- [ ] Assert attach behavior through resulting instance state (no interaction assertions)
- [ ] Run `attach_test.clj` green

## Slice 6 — `:process-launcher` seam + started-mode merge (production)

- [ ] Promote private `start-process!` to the real default behind `[:runtime-handle :process-launcher]`; resolve via `(or (get-in instance [:runtime-handle :process-launcher]) start-process!)`
- [ ] Change `start-instance-in!` `:runtime-handle` overwrite to `(update % :runtime-handle merge {:process … :pid … :started-at … :launch-id …})`
- [ ] Confirm seeded `:process-launcher`/`:nrepl-connector` survive the merge into the internal `connect-instance-in!`
- [ ] `clj-paren-repair` + targeted lint `started.clj`

## Slice 7 — `started_test.clj`

- [ ] Remove `start-process!` and `connect-instance-in!` redefs
- [ ] Seed **both** `[:runtime-handle :process-launcher]` (returning a `fake-process`-shaped object: `isAlive`/`exitValue`/`pid`/`destroy`) and `[:runtime-handle :nrepl-connector]` via the `start-instance-in!` seed param
- [ ] Drive `.nrepl-port` appearance / readiness through state; assert on resulting instance state
- [ ] Run `started_test.clj` green

## Slice 8 — `config_test.clj`

- [ ] Reshape the `resolve-config` merge test to write a real user file `<tmp-home>/.psi/agent/config.edn` = `{:agent-session {:project-nrepl {:attach {:host "localhost" :port 7888}}}}` and a real project file `<wt>/.psi/project.edn` = `{:agent-session {:project-nrepl {:attach {:port 9999}}}}`
- [ ] Temporarily rebind `user.home` to the temp home; restore original in `finally`
- [ ] Assert `resolve-config` returns the deep-merged `:project-nrepl` (project `:port` wins, user `:host` survives) — no `:version` key in output
- [ ] Reshape the empty case: temp `user.home` with no user file + temp worktree with no project files → assert `{:project-nrepl {}}`
- [ ] Remove all `read-user-config`/`read-project-preferences` redefs
- [ ] Run `config_test.clj` green

## Slice 9 — `commands_test.clj`

- [ ] Remove `ops/eval-op` and `ops/interrupt` redefs
- [ ] Install a real managed instance with in-memory `[:runtime-handle :client-session]` (eval_test `install-instance!` pattern)
- [ ] Dispatch real `/project-repl eval` and `/project-repl interrupt` strings through real `commands → ops → eval`; assert `{:type :text :message ...}` results
- [ ] Leave pure formatting/parsing/missing-start-command tests seamless (already real-value)
- [ ] Run `commands_test.clj` green

## Slice 10 — `ops_test.clj`

- [ ] Remove `eval/eval-instance-in!` redefs (success + interrupted cases)
- [ ] Install a real managed instance with deterministic in-memory `[:runtime-handle :client-session]`
- [ ] Assert `eval-op` public success/interrupted contract through real `eval-instance-in!`
- [ ] Run `ops_test.clj` green

## Slice 11 — Verification

- [ ] Confirm no remaining `with-redefs` in the six in-scope test files (`git grep`)
- [ ] Run all focused `project-nrepl` component tests green (incl. unchanged `runtime_test.clj`, `eval_test.clj`)
- [ ] Run targeted `clj-kondo` lint on changed source/test namespaces
- [ ] If entry-point ownership shifted, run one higher-level consuming-path proof to confirm no boundary behavior drift

## Slice 12 — Document

- [ ] Record a **separate** `implementation.md` strategy note for the `:nrepl-connector` seam
- [ ] Record a **separate** `implementation.md` strategy note for the `:process-launcher` seam
- [ ] Record any remaining justified exception
- [ ] Update `mementum/state.md` with task-108 completion state
