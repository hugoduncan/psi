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

- [x] Remove `connect-instance-in!` redefs; seed `[:runtime-handle :nrepl-connector]` via the new `attach-instance-in!` seed param
- [x] Assert attach behavior through resulting instance state (no interaction assertions)
- [x] Run `attach_test.clj` green (2 tests, 17 assertions)

## Slice 6 — `:process-launcher` seam + started-mode merge (production)

- [x] Promote private `start-process!` to the real default behind `[:runtime-handle :process-launcher]` (renamed `real-process-launcher`); resolve via `(or (get-in instance [:runtime-handle :process-launcher]) real-process-launcher)`
- [x] Change `start-instance-in!` `:runtime-handle` overwrite to `(update % :runtime-handle merge {:process … :pid … :started-at … :launch-id …})`
- [x] Confirm seeded `:process-launcher`/`:nrepl-connector` survive the merge into the internal `connect-instance-in!` (verified in Slice 7 test run)
- [x] `clj-paren-repair` + targeted lint `started.clj` (green)

## Slice 7 — `started_test.clj`

- [x] Remove `start-process!` and `connect-instance-in!` redefs
- [x] Seed **both** `[:runtime-handle :process-launcher]` (returning a `fake-process`-shaped object: `isAlive`/`exitValue`/`pid`/`destroy`) and `[:runtime-handle :nrepl-connector]` via the `start-instance-in!` seed param
- [x] Make readiness file-backed (not runtime-handle-state-backed): launcher writes a real `.nrepl-port` in the temp worktree, consumed by `wait-for-started-endpoint!` → `read-dot-nrepl-port-safe`; assert endpoint discovered from the file
- [x] Run `started_test.clj` green (2 tests, 12 assertions). Seeded `:nrepl-connector` survived the runtime-handle merge into the internal `connect-instance-in!` (session-id derived from the seeded session fn)

## Slice 8 — `config_test.clj`

- [x] Reshape the `resolve-config` merge test to write a real user file `<tmp-home>/.psi/agent/config.edn` and a real project file `<wt>/.psi/project.edn`, both nested under `{:agent-session {:project-nrepl {...}}}`
- [x] Temporarily rebind `user.home` to the temp home; restore original in `finally`
- [x] Assert `resolve-config` returns the deep-merged `:project-nrepl` (project `:port` wins, user `:host` survives) — no `:version` key in output
- [x] Reshape the empty case: temp `user.home` with no user file + temp worktree with no project files → assert `{:project-nrepl {}}`
- [x] Remove all `read-user-config`/`read-project-preferences` redefs
- [x] Run `config_test.clj` green (7 tests, 32 assertions)

## Slice 9 — `commands_test.clj`

- [x] Remove `ops/eval-op` and `ops/interrupt` redefs
- [x] Install a real managed instance with in-memory `[:runtime-handle :client-session]` (eval_test `install-instance!` pattern)
- [x] Seed session-state so the dispatch `session-id` resolves to the instance's worktree-path — instance installed at `(System/getProperty "user.dir")`, the same `:worktree-path` passed to `create-test-session` `:session-defaults`, so `ss/session-worktree-path-in` resolves to it
- [x] Dispatch real `/project-repl eval` through real `commands → ops/eval-op → eval/eval-instance-in!`; `client-session` returns `[{:value "3" :status #{"done"}}]`; assert eval-ok + value
- [x] Dispatch real `/project-repl interrupt` through real `commands → ops/interrupt → eval/interrupt-instance-in!` with seeded `[:runtime-handle :active-op]` precondition; `client-session` returns `[{:status #{"done" "interrupted"}}]` → `:interrupted`; added a separate no-active-eval `:unavailable` case
- [x] Leave pure formatting/parsing/missing-start-command tests seamless (already real-value)
- [x] Run `commands_test.clj` green (2 tests, 19 assertions)

## Slice 10 — `ops_test.clj`

- [x] Remove `eval/eval-instance-in!` redefs (success + interrupted cases)
- [x] Install a real managed instance with deterministic in-memory `[:runtime-handle :client-session]`; `:interrupted` driven by `[{:status #{"interrupted"}}]`, `:success` by `[{:value "3" :status #{"done"}}]`
- [x] Assert `eval-op` public success/interrupted contract through real `eval-instance-in!` (timing keys present, not exact instants; `:ns` is nil — real-behavior contract, prior mock fabricated `:ns`)
- [x] Run `ops_test.clj` green (2 tests, 17 assertions)

## Slice 11 — Verification

- [x] Confirm no remaining `with-redefs` in the six in-scope test files (`git grep` → NONE)
- [x] Run all focused `project-nrepl` component tests green (incl. unchanged `runtime_test.clj`, `eval_test.clj`) — 25 tests, 154 assertions, 0 failures
- [x] Run targeted `clj-kondo` lint on changed source/test namespaces — `components/project-nrepl/src` + `/test`, 0 errors, 0 warnings
- [x] Entry-point ownership shifted (attach/start signatures gained optional seed) → ran `psi.agent-session.project-nrepl-extension-install-test` (consuming path that redefs `start-instance-in!`): 1 test, 5 assertions, 0 failures — no boundary drift

## Slice 12 — Document

- [x] Record a **separate** `implementation.md` strategy note for the `:nrepl-connector` seam
- [x] Record a **separate** `implementation.md` strategy note for the `:process-launcher` seam
- [x] Record any remaining justified exception (none — all six files with-redefs-free)
- [x] Update `mementum/state.md` with task-108 completion state

## Plan/steps ambiguity follow-ups (2026-06-01 review)

- [x] Slice 4: specify the concrete `:runtime-handle` seed placement on `attach-instance-in!` (new arity vs. key merged into the existing `attach-input` 3rd-positional map vs. a new opts map) so the seam-seed thread into `ensure-instance-in!` is deterministic; `start-instance-in!` already has an `opts` map and is unambiguous — RESOLVED: new 4th-positional `opts` map on `attach-instance-in!` (`[ctx wt attach-input opts]`), forward `(:runtime-handle opts)`; `attach-input` stays domain-only. Concretized in Slice 4 step + plan.md production-change #3.
- [x] Slice 9: state that the `commands_test.clj` operational tests must seed session-state so the dispatch `session-id` resolves (via `ss/session-worktree-path-in`) to the same worktree-path where the real managed instance is installed, or the dispatch instance lookup misses — RESOLVED: added explicit session-state→worktree binding step to Slice 9.
- [x] Slices 9 & 10: specify what the in-memory `[:runtime-handle :client-session]` fn must return to drive the `:interrupted` path (responses whose `summarize-response` yields `:interrupted`, i.e. a `"interrupted"` status), since `eval_test.clj` provides only a `:success` eval template and no `:interrupted` eval template — RESOLVED: specified `[{:status #{"interrupted"}}]`-shaped response in both Slice 9 and Slice 10 steps; success template `[{:value "…" :status #{"done"}}]`.
- [x] Slice 7: replace "drive `.nrepl-port` appearance / readiness through state" with the concrete file-backed mechanism — `started_test.clj` must write a real `.nrepl-port` file in the temp worktree (consumed by `wait-for-started-endpoint!` / `read-dot-nrepl-port-safe`); readiness is file-backed, not runtime-handle-state-backed (also fix the matching wording in plan.md's started_test reshape bullet) — RESOLVED: rewrote Slice 7 readiness step as file-backed `.nrepl-port` write; updated matching plan.md started_test reshape bullet.

## Plan/steps inconsistency follow-ups (2026-06-01 review)

- [x] Slice 9: correct the `/project-repl interrupt` routing and add the `active-op` precondition. Steps Slice 9 says dispatch `/project-repl eval` AND `/project-repl interrupt` "through real `commands → ops → eval`" and that the seeded `[:runtime-handle :client-session]` fn returning `[{:status #{"interrupted"}}]` drives "the `:interrupted` path". Source: `/project-repl interrupt` routes via `ops/interrupt → eval/interrupt-instance-in!` (NOT `eval-op`/`eval-instance-in!`), and `interrupt-instance-in!` short-circuits to `{:status :unavailable :reason :no-active-eval}` when `[:runtime-handle :active-op]` is absent — so it never reaches the seeded `client-session`. Fix Slice 9 to: (a) correct the interrupt routing wording (`ops/interrupt → interrupt-instance-in!`, not `→ eval`); and (b) require the interrupt test to establish an `:active-op` (e.g. an in-flight eval, or seed `[:runtime-handle :active-op]`) before dispatching `/project-repl interrupt`, so `interrupt-instance-in!` reaches the seeded `client-session {:op "interrupt" ...}` call and the `[{:status #{"interrupted"}}]` response actually drives the assertion. Update the matching plan.md Slice 9 reshape bullet if it carries the same eval-only wording.

## Implementation review follow-ups (2026-06-01)

- [x] Consolidate duplicated component-local test helpers into a shared `psi.project-nrepl.test-support` namespace: `install-instance!` (×3: eval/commands/ops_test), `temp-dir` (×4), `delete-tree!` (×4), `session-fn-with-id` (×2), `make-ctx` (×6). Reduces drift risk on the seeded `:runtime-handle` shape and aligns with the consistency guideline. Keep tests green + lint clean after. — DONE: added `psi.project-nrepl.test-support` (`make-ctx`, `install-instance!`, `temp-dir [prefix]`, `delete-tree!`, `session-fn-with-id`); all seven `*_test.clj` files (incl. `runtime_test` for the 6th `make-ctx`) now `:refer` the shared helpers. `temp-dir` is uniformly prefix-taking; per-file prefixes preserved. 25 tests, 153 assertions, 0 failures; lint clean. (`test_support.clj` does not match `.*-test$`, so it is not collected as a test ns.)
- [x] Reconsider `client_test.clj` `connect-instance-in-test`'s `@calls*` interaction-style assertion (`(= [{:host … :port …}] @calls*)`): either drop the `calls*` atom (the connected-instance state assertions already cover the result) or retain only if endpoint-passthrough is the specific behaviour under test. — DONE: dropped the `calls*` atom and its assertion; the connector now takes `_endpoint`. The connected-instance state assertions (`:transport`/`:client`/`:client-session`/`:session-id` from the seeded connector) already prove the connector result was used, so the input-capture interaction check is redundant. (153 assertions, down 1 from 154.)

## Implementation review follow-ups (2026-06-01, second pass)

- [ ] `client_test.clj` `connect-instance-in-test`: replace the inline `session-fn` (`(with-meta (fn [_] nil) {(keyword "nrepl.core" "taking-until") {:session "nrepl-session-1"}})`) with the shared `psi.project-nrepl.test-support/session-fn-with-id` — `started_test` already uses it; `client_test` is the one remaining inline copy of that metadata shape (the exact drift the consolidation targeted). Keep tests green + lint clean.
- [ ] Fold the remaining hand-rolled temp-dir create/delete onto the shared `temp-dir`/`delete-tree!` helpers so each file uses one idiom: `config_test.clj` `read-project-preferences-test` + `read-dot-nrepl-port-test` (currently `(io/file (System/getProperty "java.io.tmpdir") (str …-(UUID/randomUUID)))` + inline `doseq` delete) and `commands_test.clj` missing-start-command test (currently `Files/createTempDirectory` + inline `doseq` delete). These were outside the de-mock reshape set but now mix both idioms within the same files. Keep tests green + lint clean.
