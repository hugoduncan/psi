# 108 — Steps

Rewritten 2026-06-01 to match the stabilised design and slice order in plan.md.

## Slice 1 — Audit + source confirmation

- [x] Re-read the six in-scope tests and confirm `with-redefs` sites: `config_test.clj` (`read-user-config`, `read-project-preferences`), `client_test.clj` (`nrepl.core/connect|client|client-session`), `attach_test.clj` (`connect-instance-in!`), `started_test.clj` (`start-process!`, `connect-instance-in!`), `commands_test.clj` (`ops/eval-op`, `ops/interrupt`), `ops_test.clj` (`eval/eval-instance-in!`)
- [x] Confirm `eval_test.clj` `[:runtime-handle :client-session]` idiom and `install-instance!` pattern as the reuse template
- [x] Verify in `client.clj` the inline `requiring-resolve` connect/client/session block and the session-id derivation + throw in `connect-instance-in!`
- [x] Verify `connect-instance-in!` `merge`s into existing `:runtime-handle`
- [x] Verify `start-instance-in!` currently **overwrites** `:runtime-handle` with process keys
- [x] Verify `ensure-instance-in!` forwards `:runtime-handle` to `build-instance` only via `:as opts` passthrough (not its own `:keys`)
- [x] Grep for all callers of `attach-instance-in!` and `start-instance-in!` to bound the signature-change blast radius — only `ops.clj` (internal) calls both; `project_nrepl_extension_install_test.clj` redefs `start-instance-in!` wholesale (unaffected by an added optional arg)

## Slice 2 — `:nrepl-connector` seam (production)

- [x] Promote the inline `requiring-resolve` connect/client/session block in `client.clj` into a real-default connector fn returning `{:transport :client :client-session}` (`real-nrepl-connector`, takes `{:host :port}`)
- [x] Resolve the connector via `(or (get-in instance [:runtime-handle :nrepl-connector]) <real-default>)` in `connect-instance-in!`
- [x] Keep session-id derivation (`session-fn` meta `:nrepl.core/taking-until :session`) and the throw-on-missing-session-id behavior in `connect-instance-in!`
- [x] `clj-paren-repair` + targeted lint `client.clj` (green)

## Slice 3 — `client_test.clj`

- [x] Replace `nrepl.core/*` redefs with a seeded `[:runtime-handle :nrepl-connector]` fn returning a deterministic transport/client/session-fn (session-fn carrying `:nrepl.core/taking-until {:session ...}` metadata)
- [x] Assert on connected-instance `:runtime-handle` state (`:transport :client :client-session :session-id`)
- [x] Run `client_test.clj` green (2 tests, 14 assertions, 0 failures)

## Slice 4 — Composite seed param (production)

- [x] Add a new trailing optional `opts` arity to `attach-instance-in!` — `([ctx wt] [ctx wt attach-input] [ctx wt attach-input opts])` — and thread `(:runtime-handle opts)` into its `ensure-instance-in!` call. `attach-input` stays domain-only; seam seed lives only in the new 4th-positional `opts` map.
- [x] Add optional `:runtime-handle` seam-seed (from opts) to `start-instance-in!`; thread into its `ensure-instance-in!` call
- [x] Confirm real callers (no seed) observe unchanged behavior — existing attach/started tests (still with-redefs) pass: 4 tests, 23 assertions
- [x] `clj-paren-repair` + targeted lint the changed namespaces (green)

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
- [ ] Make readiness file-backed (not runtime-handle-state-backed): `wait-for-started-endpoint!` → `read-dot-nrepl-port-safe` reads a REAL on-disk `.nrepl-port` in the temp worktree. The test MUST write a real `.nrepl-port` file in the temp worktree (so `read-dot-nrepl-port` parses a host/port) before/while the started process polls; assert on the resulting instance state (endpoint discovered from the file). Do NOT seed readiness via runtime-handle state.
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
- [ ] Seed session-state so the dispatch `session-id` resolves to the instance's worktree-path: `dispatch-project-nrepl-command` derives `(ss/session-worktree-path-in ctx session-id)` and looks up the managed instance at that worktree, so the test must register the same `session-id → worktree-path` mapping (e.g. via the session-state surface `ss/session-worktree-path-in` reads) as where the instance is installed, or the dispatch instance lookup misses and the command never reaches real `ops → eval`
- [ ] Dispatch real `/project-repl eval` through real `commands → ops/eval-op → eval/eval-instance-in!`; assert `{:type :text :message ...}` results. The eval `client-session` fn returns the `:success` template `[{:value "3" :status #{"done"}}]` (`eval_test.clj` provides this template)
- [ ] Dispatch real `/project-repl interrupt` through real `commands → ops/interrupt → eval/interrupt-instance-in!` (NOT `eval-op`/`eval-instance-in!`); assert `{:type :text :message ...}` result. PRECONDITION: `interrupt-instance-in!` short-circuits to `{:status :unavailable :reason :no-active-eval}` when `[:runtime-handle :active-op]` is absent, so the test MUST establish an `:active-op` on the installed instance before dispatching `/project-repl interrupt` — either by triggering an in-flight eval, or by directly seeding `[:runtime-handle :active-op]` (e.g. `{:op-id "…" :started-at …}`). Only with `:active-op` present does `interrupt-instance-in!` invoke the seeded `client-session {:op "interrupt" :interrupt-id (:op-id active-op) …}`; that fn must then return a response seq carrying nREPL status `"interrupted"` (e.g. `[{:status #{"interrupted"}}]` or `[{:status #{"done" "interrupted"}}]`) so `summarize-response` yields `:interrupted`. `eval_test.clj` provides only a `:success` template and no `:interrupted` template, so this interrupt response must be constructed here
- [ ] Leave pure formatting/parsing/missing-start-command tests seamless (already real-value)
- [ ] Run `commands_test.clj` green

## Slice 10 — `ops_test.clj`

- [ ] Remove `eval/eval-instance-in!` redefs (success + interrupted cases)
- [ ] Install a real managed instance with deterministic in-memory `[:runtime-handle :client-session]`. To drive the `:interrupted` case, the seeded `client-session` fn must return responses carrying nREPL status `"interrupted"` (e.g. `[{:status #{"interrupted"}}]`) so `summarize-response` → `:interrupted`; the `:success` case uses the eval_test-style `[{:value "…" :status #{"done"}}]`. No canned op result is injected — `:interrupted` is derived from the response statuses through real `eval-instance-in!`
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

## Plan/steps ambiguity follow-ups (2026-06-01 review)

- [x] Slice 4: specify the concrete `:runtime-handle` seed placement on `attach-instance-in!` (new arity vs. key merged into the existing `attach-input` 3rd-positional map vs. a new opts map) so the seam-seed thread into `ensure-instance-in!` is deterministic; `start-instance-in!` already has an `opts` map and is unambiguous — RESOLVED: new 4th-positional `opts` map on `attach-instance-in!` (`[ctx wt attach-input opts]`), forward `(:runtime-handle opts)`; `attach-input` stays domain-only. Concretized in Slice 4 step + plan.md production-change #3.
- [x] Slice 9: state that the `commands_test.clj` operational tests must seed session-state so the dispatch `session-id` resolves (via `ss/session-worktree-path-in`) to the same worktree-path where the real managed instance is installed, or the dispatch instance lookup misses — RESOLVED: added explicit session-state→worktree binding step to Slice 9.
- [x] Slices 9 & 10: specify what the in-memory `[:runtime-handle :client-session]` fn must return to drive the `:interrupted` path (responses whose `summarize-response` yields `:interrupted`, i.e. a `"interrupted"` status), since `eval_test.clj` provides only a `:success` eval template and no `:interrupted` eval template — RESOLVED: specified `[{:status #{"interrupted"}}]`-shaped response in both Slice 9 and Slice 10 steps; success template `[{:value "…" :status #{"done"}}]`.
- [x] Slice 7: replace "drive `.nrepl-port` appearance / readiness through state" with the concrete file-backed mechanism — `started_test.clj` must write a real `.nrepl-port` file in the temp worktree (consumed by `wait-for-started-endpoint!` / `read-dot-nrepl-port-safe`); readiness is file-backed, not runtime-handle-state-backed (also fix the matching wording in plan.md's started_test reshape bullet) — RESOLVED: rewrote Slice 7 readiness step as file-backed `.nrepl-port` write; updated matching plan.md started_test reshape bullet.

## Plan/steps inconsistency follow-ups (2026-06-01 review)

- [x] Slice 9: correct the `/project-repl interrupt` routing and add the `active-op` precondition. Steps Slice 9 says dispatch `/project-repl eval` AND `/project-repl interrupt` "through real `commands → ops → eval`" and that the seeded `[:runtime-handle :client-session]` fn returning `[{:status #{"interrupted"}}]` drives "the `:interrupted` path". Source: `/project-repl interrupt` routes via `ops/interrupt → eval/interrupt-instance-in!` (NOT `eval-op`/`eval-instance-in!`), and `interrupt-instance-in!` short-circuits to `{:status :unavailable :reason :no-active-eval}` when `[:runtime-handle :active-op]` is absent — so it never reaches the seeded `client-session`. Fix Slice 9 to: (a) correct the interrupt routing wording (`ops/interrupt → interrupt-instance-in!`, not `→ eval`); and (b) require the interrupt test to establish an `:active-op` (e.g. an in-flight eval, or seed `[:runtime-handle :active-op]`) before dispatching `/project-repl interrupt`, so `interrupt-instance-in!` reaches the seeded `client-session {:op "interrupt" ...}` call and the `[{:status #{"interrupted"}}]` response actually drives the assertion. Update the matching plan.md Slice 9 reshape bullet if it carries the same eval-only wording.
