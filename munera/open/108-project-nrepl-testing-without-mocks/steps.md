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

- [x] `client_test.clj` `connect-instance-in-test`: replace the inline `session-fn` (`(with-meta (fn [_] nil) {(keyword "nrepl.core" "taking-until") {:session "nrepl-session-1"}})`) with the shared `psi.project-nrepl.test-support/session-fn-with-id` — `started_test` already uses it; `client_test` is the one remaining inline copy of that metadata shape (the exact drift the consolidation targeted). Keep tests green + lint clean. — DONE: `client_test` now `:refer`s `session-fn-with-id` and binds `session-fn (session-fn-with-id "nrepl-session-1")`; the inline `with-meta` copy is gone. No inline copies of the `:nrepl.core/taking-until` metadata shape remain in the test files.
- [x] Fold the remaining hand-rolled temp-dir create/delete onto the shared `temp-dir`/`delete-tree!` helpers so each file uses one idiom: `config_test.clj` `read-project-preferences-test` + `read-dot-nrepl-port-test` (currently `(io/file (System/getProperty "java.io.tmpdir") (str …-(UUID/randomUUID)))` + inline `doseq` delete) and `commands_test.clj` missing-start-command test (currently `Files/createTempDirectory` + inline `doseq` delete). These were outside the de-mock reshape set but now mix both idioms within the same files. Keep tests green + lint clean. — DONE: `config_test` `read-project-preferences-test` (×3 testing blocks) and `read-dot-nrepl-port-test` (×2) now bind `dir (temp-dir "prefix-")` and clean up with `(delete-tree! dir)`; `temp-dir` returns a path string consumed directly by `read-project-preferences`/`read-dot-nrepl-port`, with `(io/file dir ...)` used for the on-disk fixture files. `commands_test` missing-start-command test now uses `(temp-dir "psi-project-nrepl-commands-")` + `(delete-tree! worktree-path)` and dropped its now-unused `clojure.java.io` require. Each file uses one temp-dir idiom.

## Implementation review follow-ups (2026-06-01, third pass)

- [x] Remove the interaction-style `@calls*` assertions from the canonical `eval_test.clj` so the reference pattern matches `¬assert(interactions(test))` (the same standard the 2nd-pass follow-up applied to `client_test.clj`): drop `(is (= "eval" (:op (first @calls*))))` (line 30) and `(is (= "interrupt" (:op (first @calls*))))` + `(is (= "eval-123" (:interrupt-id (first @calls*))))` (lines 84–85). The behaviour is already proven by state/result assertions in the same tests — eval `:op` by the `:success`/`:value "3"` result; interrupt `:op`/`:interrupt-id` by `(:interrupted-op-id result)` and `(:last-interrupt instance)`. Keep the `swap! conj` capture only where a returned value still needs the message; otherwise drop the `calls*` atom too. Keep tests green + lint clean. — DONE: dropped all three interaction assertions; the `calls*` atom + its `(swap! calls* conj msg)` capture were dead once the assertions went (the `client-session` fn's return uses only `(:id msg)`, an input→output mapping needing no capture), so removed `calls*` from both `eval-instance-in-test` (success block) and `interrupt-instance-in-test` (active-op block). The canonical reference pattern now carries zero interaction-style assertions, consistent with the standard this task enforced across the six in-scope files. Behaviour still proven by state/result: eval `:op` by `:success`/`:value "3"`; interrupt `:op`/`:interrupt-id` by `(:interrupted-op-id result)` + `(:last-interrupt instance)`. Verified: focused project-nrepl suite (8 ns) → 25 tests, 150 assertions, 0 failures (down 3 from 153, the three removed assertions); `clj-kondo --lint …/eval_test.clj` → 0 errors, 0 warnings.

## Implementation review follow-ups (2026-06-01, fourth pass)

- (none) Fourth independent review pass found no new actionable issues. Verified
  empirically: 25 tests / 150 assertions / 0 failures; lint 0/0; zero
  `with-redefs`, zero interaction-capture atoms, zero inline session-fn metadata
  copies; three production seams match design exactly; all acceptance criteria
  met. Review complete — no follow-up steps added.

## Test review follow-ups (2026-06-01, task-test-review)

- [x] Add a `client_test.clj` covering test for the missing-session-id throw in
  `connect-instance-in!`. The `:nrepl-connector` seam was shaped so session-id
  derivation (and its throw-on-missing branch, `"… did not expose a session
  id"`) stays in `connect-instance-in!` — a design-retained behaviour now
  trivially testable through the same seam the migrated tests already use, yet
  uncovered. Seed a `[:runtime-handle :nrepl-connector]` whose returned
  `:client-session` fn carries NO `:nrepl.core/taking-until` metadata (i.e. a
  bare `(fn [_] nil)`, not `session-fn-with-id`), invoke `connect-instance-in!`,
  and assert it throws `clojure.lang.ExceptionInfo` with message `#"did not
  expose a session id"`. No new seam needed; reuse the existing
  `connect-instance-in-test` setup pattern. Keep tests green + lint clean. —
  DONE: added `connect-instance-in-missing-session-id-test`. Seeds a
  `[:runtime-handle :nrepl-connector]` returning `:client-session (fn [_] nil)`
  (no `:nrepl.core/taking-until` metadata) and asserts `connect-instance-in!`
  throws `clojure.lang.ExceptionInfo` `#"did not expose a session id"` via
  `thrown-with-msg?`. Reused the existing `ensure-instance-in!` +
  `update-instance-in!` connector-seed setup; state-only, no interaction
  assertions. `client_test`: 3 tests, 14 assertions, 0 failures (up from 2/12).
  Focused project-nrepl suite (8 ns) green; `clj-kondo` 0/0 on `client_test.clj`.

## Test review follow-ups (2026-06-01, task-test-review 2nd pass)

- (none) Second independent test review pass found no new actionable issues.
  Verified empirically: 26 tests / 151 assertions / 0 failures; zero
  `with-redefs`; all infra deps injectable/nullable/¬mock/¬stub via
  `[:runtime-handle <seam-key>]`; all design-named behaviours covered (the
  previously-flagged missing-session-id throw is now covered). The two
  pre-existing `connect-instance-in!` guard branches (instance-not-found,
  missing-host/port) were considered but deliberately NOT raised — they are
  pre-existing infra guards outside this de-mocking task's behaviour set;
  covering them would be scope expansion. No follow-up steps added.

## Test review follow-ups (2026-06-01, test-shaper)

- [x] Remove the wall-clock/thread-scheduling dependency from `started_test.clj`
  so the readiness tests are deterministic (`control(time)` ∧ `control(concurrency)`
  ∧ `¬flaky`). Both `wait-for-started-endpoint-test` (happy case) and
  `start-instance-in-test` currently drive `.nrepl-port` appearance via
  `(future (Thread/sleep …) (spit …))` racing the polling
  `wait-for-started-endpoint!`. The race is unnecessary because
  `wait-for-started-endpoint!` (started.clj:52–53) checks `read-dot-nrepl-port-safe`
  on the FIRST loop iteration. Fix: (a) in `wait-for-started-endpoint-test`, write
  the `.nrepl-port` file synchronously before invoking
  `wait-for-started-endpoint!` (drop the `future`/`Thread/sleep`); (b) in
  `start-instance-in-test`, have the seeded `launcher` write `.nrepl-port`
  synchronously before returning the fake process (drop its `future`/`Thread/sleep`)
  — the launcher runs synchronously before `wait-for-started-endpoint!`, so the
  first poll finds the file. Preserve the file-backed-readiness signal (still
  discover the endpoint from a real on-disk `.nrepl-port`); leave the
  process-exit failure case unchanged (already deterministic). Keep tests green +
  lint clean. — DONE: both readiness tests now write `.nrepl-port` synchronously
  before the wait/launch (dropped both `future`/`Thread/sleep` races);
  file-backed-readiness signal preserved; process-exit failure case unchanged.
  `started_test` 2 tests/12 assertions green; full focused suite 26/151/0
  unchanged; lint 0/0. (Detail in implementation.md.)

## Test-shaper follow-ups (2026-06-01, second test-shaper pass)

- [x] Split `config_test.clj` `read-dot-nrepl-port-test`'s "fails when
  .nrepl-port is missing or invalid" block into two single-concern `testing`
  blocks, each with its own fresh `temp-dir`/`delete-tree!`: (1) absent
  `.nrepl-port` throws `clojure.lang.ExceptionInfo`; (2) malformed
  `.nrepl-port` (non-integer content) throws `clojure.lang.ExceptionInfo`.
  Removes the intra-test `spit`-then-reassert ordering coupling and gives each
  boundary contract its own meaningful failure name, matching the
  one-concern-per-block style already used by `read-project-preferences-test`.
  Keep tests green + lint clean. — DONE: replaced the single combined block
  with "fails when .nrepl-port is absent" (fresh `temp-dir`, assert throws on a
  bare empty dir) and "fails when .nrepl-port content is malformed" (fresh
  `temp-dir`, `spit` `"not-a-port"`, assert throws). No intra-test
  `spit`-then-reassert ordering coupling remains; each block has its own
  `temp-dir`/`delete-tree!` lifecycle and a meaningful failure name. Assertion
  count unchanged (2 `thrown?` checks total). `config_test` 7 tests/32
  assertions green; full focused project-nrepl suite (8 ns) 26 tests/151
  assertions/0 failures unchanged; `clj-kondo` 0/0 on `config_test.clj`.

## Test-shaper follow-ups (2026-06-01, third test-shaper pass)

- [x] Consolidate the duplicated connector-seed ceremony in `client_test.clj`
  into a shared `psi.project-nrepl.test-support/seed-connector!` helper. Both
  `connect-instance-in-test` and `connect-instance-in-missing-session-id-test`
  repeat the same `ensure-instance-in!` (`:worktree-path`/`:acquisition-mode
  :attached`/`:endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}`)
  + `update-instance-in!` that `assoc-in`s the test's `connector` under
  `[:runtime-handle :nrepl-connector]` — the one seam-seeding idiom never folded
  into `test-support` (unlike `install-instance!`/`session-fn-with-id`/
  `temp-dir`/`delete-tree!`). Add `(seed-connector! ctx worktree connector)`
  (mirroring `install-instance!`) that performs the ensure + connector-seed,
  single-sourcing the seeded `[:runtime-handle :nrepl-connector]` shape and the
  endpoint map. Each test keeps its own `connector` fn (happy vs. metadata-less
  session fn) and its own assertions; only the install ceremony is shared. Keep
  tests green + lint clean. — DONE: added
  `psi.project-nrepl.test-support/seed-connector!` (ensure attached instance +
  `assoc-in` connector under `[:runtime-handle :nrepl-connector]`, mirroring
  `install-instance!`). Both `connect-instance-in-test` and
  `connect-instance-in-missing-session-id-test` now `:refer` and call
  `(seed-connector! ctx worktree connector)`, dropping the duplicated
  ensure+update ceremony and the inline endpoint map. Each test keeps its own
  `connector` fn and assertions; `disconnect-instance-in-test` (distinct
  proxy-Closeable setup, not connector-seeding) still uses
  `project-nrepl-runtime` directly. The seeded `[:runtime-handle
  :nrepl-connector]` shape + endpoint map are now single-sourced. Focused
  project-nrepl suite (8 ns) 26 tests / 151 assertions / 0 failures unchanged;
  `clj-kondo` 0/0 on `client_test.clj` + `test_support.clj`.

## Test-shaper follow-ups (2026-06-01, fourth test-shaper pass)

- [x] Remove the misleading `:ns "user"` incidental setup from `ops_test.clj`
  `eval-op-test`. Both the success block (line 37) and the interrupted block
  (line 59) seed `:ns "user"` in the in-memory `client-session` response, then
  each asserts `(is (nil? (:ns result)))`. The seeded `:ns` feeds nothing the
  test asserts: `summarize-response` carries `:ns` and `combine-responses`
  preserves it, but `eval-instance-in!`'s built `result` map omits `:ns`, so
  `eval-op`'s `(:ns result)` is nil regardless of the response. The seeded
  `:ns "user"` is dead/misleading setup (`minimal_incidental_setup`,
  `meaningful_failures`) — it implies `:ns` flows through when it is dropped a
  layer below the assertion. Fix: drop `:ns "user"` from both seeded responses;
  keep the `(nil? (:ns result))` assertion + explanatory comment (the real
  drop-`:ns` contract is best proven by NOT seeding `:ns` and still observing
  nil). The `:err "Interrupted"` seeded in the interrupted block stays — it is
  asserted (`(= "Interrupted" (:err result))`). Keep tests green + lint clean.
  — DONE: dropped `:ns "user"` from both the success and interrupted seeded
  `client-session` responses; kept both `(is (nil? (:ns result)))` assertions.
  Updated the success-block comment to state the contract is now proven by NOT
  seeding `:ns` and still observing nil (removed the now-inaccurate "prior mock
  fabricated :ns" framing). The interrupted block's `:err "Interrupted"` seed
  stays (asserted). `ops_test` 2 tests/17 assertions green; full focused
  project-nrepl suite (8 ns) 26 tests/151 assertions/0 failures unchanged
  (assertion count steady — only dead setup removed); `clj-kondo` 0/0 on
  `ops_test.clj`.

## Test-shaper follow-ups (2026-06-01, fifth test-shaper pass)

- [x] Consolidate the duplicated happy nullable-connector construction into a
  shared `psi.project-nrepl.test-support` helper (e.g. `fake-connector` /
  `nullable-connector`). The identical connector value
  `(fn [_endpoint] {:transport {:transport :fake} :client (fn ([] nil) ([_] nil))
  :client-session (session-fn-with-id "nrepl-session-1")})` is constructed
  verbatim at THREE sites — `attach_test.clj` `attach-instance-in-test` (line 33),
  `client_test.clj` `connect-instance-in-test` (line 15), and `started_test.clj`
  `start-instance-in-test` (line 65). This is the one nullable-seam *fixture
  value* never folded into `test-support`: the prior consolidation passes shared
  the connector *seeding* ceremony (`seed-connector!`) and the session fn
  (`session-fn-with-id`), but not the connector return-map construction itself,
  so the deterministic transport/client/session-fn shape is triplicated.
  Violates `consistent(test_abstractions)` ∧ `minimal(incidental_variation)` ∧
  `helpers_that_compress(ceremony)` — a drift in the nullable transport/client
  shape (or the `{:transport :client :client-session}` connector contract) must
  be edited in three places. Add a `test-support` helper returning the canonical
  happy connector (parameterised by session-id, defaulting to
  `"nrepl-session-1"`), and have the three happy-path tests `:refer` it. The
  distinct-behaviour connectors stay inline (each is its own intent, not
  ceremony): `client_test.clj` `connect-instance-in-missing-session-id-test`'s
  metadata-less `:client-session (fn [_] nil)` connector (line 35) and
  `attach_test.clj` "attach failure" throwing connector (line 52) are NOT folded
  in — only the identical happy connector is shared. Keep tests green + lint
  clean. — DONE: added `psi.project-nrepl.test-support/fake-connector`
  (`([])`/`([session-id])`, default `"nrepl-session-1"`) returning a fn of
  `_endpoint` that yields a constant `{:transport {:transport :fake} :client
  (fn ([] nil) ([_] nil)) :client-session (session-fn-with-id session-id)}` map
  (built once, closed over — so callers may invoke it once to obtain the
  expected runtime-handle values). The three happy-path tests now `:refer`
  `fake-connector` and drop their inline connector maps:
  `client_test/connect-instance-in-test` (binds `connector (fake-connector …)`
  and destructures `(connector nil)` for the `transport`/`client`/
  `client-session` equality assertions — same objects flow through the seam),
  `attach_test/attach-instance-in-test`, and
  `started_test/start-instance-in-test`. The two distinct-behaviour connectors
  stayed inline: `client_test`'s metadata-less `(fn [_] nil)` session-fn
  connector and `attach_test`'s throwing connector. `session-fn-with-id` is no
  longer `:refer`-ed by client/attach/started (folded into `fake-connector`).
  Verified: focused project-nrepl suite (8 ns) 26 tests / 151 assertions / 0
  failures (count unchanged — pure helper extraction); `clj-kondo` 0/0 on
  `test_support.clj` + the three test files.

## Test-shaper follow-ups (2026-06-01, sixth test-shaper pass)

- [x] Consolidate the duplicated session-with-resolvable-worktree construction
  ceremony in `commands_test.clj` into a shared `psi.project-nrepl.test-support`
  helper. The call `(test-support/create-test-session {:persist? false
  :session-defaults {:worktree-path <wt>}})` is open-coded verbatim at SIX sites
  (lines 11–12, 19–20, 27–28, 42–43, 59–60, 79–80), identical except the
  `:worktree-path` value. This is the one session-construction idiom never
  folded into `test-support` (prior passes shared `make-ctx`,
  `install-instance!`, `seed-connector!`, `session-fn-with-id`, `fake-connector`,
  `temp-dir`/`delete-tree!`): `commands_test` needs a ctx WITH a session-id that
  resolves (via `ss/session-worktree-path-in`) to a specific worktree, which the
  no-session-id `make-ctx` cannot provide, so it repeats the full
  `:persist?`/`:session-defaults` map. Violates `helpers_that_compress(ceremony)`
  ∧ `consistent(test_abstractions)` ∧ `minimal(incidental_variation)` — a drift
  in the session-construction shape (new required `:session-defaults` key,
  `:persist?` default change) must be edited in six places. Add
  `(session-ctx-at worktree-path)` (or similarly named) to `test-support`
  returning `[ctx session-id]` and single-sourcing the `{:persist? false
  :session-defaults {:worktree-path …}}` shape; have the six `commands_test`
  sites call it. Re-express `make-ctx` in terms of the new helper (discard the
  returned session-id) so one source owns session construction. `create-test-session`
  is called directly only in `commands_test` (×6) + inside `make-ctx` (verified),
  so the helper genuinely single-sources the idiom. Keep tests green + lint clean.
  — DONE: added `psi.project-nrepl.test-support/session-ctx-at` (`[worktree-path]
  → [ctx session-id]`) single-sourcing the `{:persist? false :session-defaults
  {:worktree-path …}}` shape. The six `commands_test` sites now `:refer` and call
  `(session-ctx-at <wt>)` (the agent-session `test-support` alias was dropped —
  no other reference remained). Re-expressed `make-ctx` in terms of
  `session-ctx-at` (passing `(System/getProperty "user.dir")`, discarding the
  session-id) so one source owns session construction. `create-test-session` is
  now called directly only inside `session-ctx-at`. Verified: focused
  project-nrepl suite (8 ns) 26 tests / 151 assertions / 0 failures (count
  unchanged — pure helper extraction); `clj-kondo` 0/0 on `test_support.clj` +
  `commands_test.clj`.

## Implementation review follow-ups (2026-06-01, fifth pass)

- (none) Fifth independent implementation-review pass found no new actionable
  issues. Verified empirically: 26 tests / 151 assertions / 0 failures; lint
  0/0; zero `with-redefs`, zero interaction-capture atoms; three production
  seams match design exactly; real `ops.clj` callers preserve original arities;
  all helper idioms consolidated in shared `test-support`; all acceptance
  criteria met. Review complete — no follow-up steps added.

## Test review follow-ups (2026-06-01, task-test-review 3rd pass)

- (none) Third independent test-review pass found no new actionable issues.
  Verified empirically: 26 tests / 151 assertions / 0 failures; zero
  `with-redefs`; zero interaction-capture atoms; all infra deps
  injectable/nullable/¬mock/¬stub via `[:runtime-handle <seam-key>]`; every
  design-named behaviour covered. Candidate gaps (untested `/project-repl
  attach` + `/project-repl stop` dispatch branches, `wait-for-started-endpoint!`
  timeout branch, pre-existing `connect-instance-in!` guard branches) were
  considered and deliberately NOT raised — all are pre-existing or outside this
  de-mocking task's named behaviour set; raising them would be scope expansion.
  No follow-up steps added.
