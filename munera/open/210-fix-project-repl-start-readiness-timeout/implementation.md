# Implementation

## Design review — architectural fit (ψ)

Scope: design.md only; fit vs AGENTS.md / META.md / doc/architecture.md +
doc/project-nrepl.md. Not ambiguity/clarity/correctness.

Good fit:
- Q1 config key under `[:agent-session :project-nrepl]` with system<user<project
  precedence matches doc/project-nrepl.md precedence and existing
  `resolved-attach-endpoint` range-validation idiom.
- Subprocess launch + `.nrepl-port` polling correctly stay in the runtime handle
  (project nREPL registry) per the `:state*` boundary: runtime handles own
  side-effecting I/O; runtime-owned subprocess execution is the documented
  current state (Layer Map / Frontier), so staying outside dispatch effects is
  consistent — no misfit there.
- Uses existing `process-launcher` / `nrepl-connector` seams, no `with-redefs`.

Actionable architectural-fit gaps:
1. Stale-port guard placement. Design lets the guard touch the *shared*
   discovery helper while "not changing attach semantics". Started-mode
   acquisition policy (launch-time/mtime gate) should live in the started-mode
   layer, keeping the shared discovery primitive orthogonal (single
   responsibility) and attach-mode's documented `.nrepl-port` fallback intact.
2. Observable status projection. New observable outcomes (stale-port rejection
   diagnostics; effective configured timeout) are status; META principle =
   project observable status into `:state*` via dispatch as canonical data
   rather than surfacing only as ad-hoc op-return payload. Design should state
   whether/how the stale-port-rejection outcome is projected (the registry
   already projects `readiness`/`:last-error`).

## Design-review follow-up execution (ψ)

Executed both architectural-fit design-steps; both resolved in design.md (no
blockers — design-only task, decisions only).

- A1 (guard placement): stale-port gate goes in `started.clj`
  (`wait-for-started-endpoint!` / `start-instance-in!`), which already owns the
  started-process lifecycle and the launch instant. Shared
  `config/read-dot-nrepl-port` stays a mode-agnostic read+validate primitive;
  `attach/resolve-attach-endpoint` fallback unchanged. Verified by reading:
  `read-dot-nrepl-port` is called by both `wait-for-started-endpoint!` (via
  `read-dot-nrepl-port-safe`) and attach `resolve-attach-endpoint` → the gate
  must not live there.
- A2 (status projection): clarified the review's "`:state*` via dispatch"
  framing against the documented reality — the project-nrepl registry is a
  runtime handle, not dispatch state; subprocess + `.nrepl-port` I/O is
  documented runtime-owned (Layer Map / Frontier) and does not move under
  dispatch effects (consistent with the earlier design-review note in this file).
  Canonical status surface = the registry instance map (`runtime.clj`), read via
  `ops/instance-payload`. Decision: stale-port rejection → existing `:last-error`
  failure-path projection with distinct `:phase :started-stale-port`; effective
  timeout → new instance `:readiness-timeout-ms` status field surfaced through
  `instance-payload`; `ops/start` keeps projecting from `instance-payload` (no
  op-only status channel). Acceptance criteria updated to match.

## Design review — ambiguity (ψ)

Scope: design.md only (not plan/steps). Architectural-fit (A1/A2) already
resolved. New actionable ambiguities, all single-interpretation gaps a builder
would otherwise guess at:

- AMB1: Q1–Q4 are still "open … resolve collaboratively before plan", yet
  acceptance criteria assert "the confirmed Q1 surface" and "a reasonable
  default and/or configured timeout". Each open question (Q1 key name/default/
  bounds; Q2 strategy a/b/c; Q3 confirmed cause; Q4 process-exit diagnostics
  scope) must be resolved in design.md before plan, or the criteria reference
  decisions that do not exist.
- AMB2: A1 commits to a launch-time/mtime acceptance gate (last-modified ≥
  launch instant) as the stale-port mechanism, but Q2 still lists that gate as
  only one of three open options (a/b/c) and asks to "confirm which". Design
  simultaneously decides and leaves-open the Q2 strategy — which governs?
- AMB3: A2 names a new `:readiness-timeout-ms` instance status field "surfaced
  through `instance-payload`", but `instance-payload` (`ops.clj:22`) has an
  explicit fixed key list that today lacks it (and lacks any stale-port
  diagnostic key beyond `:last-error`). Design does not state that
  `instance-payload`'s projected key set is extended, so "surfaced through
  instance-payload" is ambiguous about whether the projection itself changes.
- AMB4: The mtime gate "last-modified ≥ launch instant" has unspecified
  precision/tolerance. Filesystem mtime is often coarse (1s) vs. a ms launch
  instant; a stale port touched in the same second as launch could pass, a fresh
  port could be rejected under clock/mtime skew. Design does not specify the
  comparison tolerance nor whether Q2(a) pre-launch removal supplements the gate
  (Q2c "combination").
- AMB5: Acceptance mandates BOTH the timeout raise and the stale-port gate, but
  Q3 (confirmed cause) is unresolved and the design warns "avoid speculative
  changes". If Q3 empirically shows a single cause, the criteria are ambiguous
  about whether the other fix is still required.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Design-review inconsistency follow-up execution (ψ)

Executed both newly-added inconsistency design-steps (INC1, INC2); both resolved
in design.md, no blockers (design-only task — decisions, grounded by reading
`started.clj`).

- INC1 (launch-instant ownership): grounded against `started.clj`. Confirmed the
  process is launched inside `start-instance-in!` (`(launcher effective-worktree
  validated-command)`) and `wait-for-started-endpoint!` runs *after* that, so it
  cannot capture the true launch instant — it could only record its own entry
  time. Also confirmed `:started-at (now)` is currently written onto the
  runtime-handle on the success path **after** `wait-for-started-endpoint!`
  returns, not at launch. Resolution: `start-instance-in!` is the sole launch-
  instant owner — capture a single `launched-at` binding `(now)` at the launch
  site, use it for **both** the runtime-handle `:started-at` (moved from post-wait
  to the launch site) **and** the mtime-gate reference, threading it into
  `wait-for-started-endpoint!` via `opts` (`:launched-at`).
  `wait-for-started-endpoint!` no longer "records the launch instant"; it
  consumes the threaded value. Updated Q2 step 2 and A1 (new "Launch-instant
  ownership (INC1)" bullet) so the gate-reference instant has one code-consistent
  source reconciled with `:started-at`.
- INC2 (`:last-error` `:phase` shape): grounded against `start-instance-in!`'s
  `catch`, which writes `:last-error {:message (.getMessage t) :data (ex-data t)
  :at (now)}` — so a thrown `:phase :started-stale-port` lands under
  `:last-error → :data`, not as a direct `:last-error` key. Reworded the
  acceptance criterion shorthand ("via `:last-error` with `:phase
  :started-stale-port`") to state the phase is carried on `:last-error`'s
  `:data`/ex-data, matching the precise A2 body. A2 body already correct; only
  the acceptance criterion needed alignment.

No blocked design-steps. design.md verified internally coherent (Q2 ↔ A1 launch-
instant ownership ↔ `:started-at`; A2 body ↔ acceptance criterion `:last-error`
shape).

PASS_STATUS: REVIEW_COMPLETE

## Design-review ambiguity follow-up execution (ψ)

Executed all five newly-added ambiguity design-steps (AMB1–AMB5); all resolved
in design.md, no blockers (design-only task — decisions, grounded by reading the
started/ops/config/runtime source).

- AMB1: Rewrote the "Open questions" section as "Resolved questions (Q1–Q4)".
  Q1: config key `:start-readiness-timeout-ms` under
  `[:agent-session :project-nrepl]`, default 120000 ms, bounds [1000 600000],
  validated in `config.clj` (attach-port idiom); `default-readiness-timeout-ms`
  in `started.clj` raised 5000→120000. Q4: stdout/stderr-tail diagnostics
  deferred (orthogonal, plumbing cost) — out of scope.
- AMB2: Folded Q2 into A1 as one decision (stale-port strategy = Q2c
  combination: pre-launch `.nrepl-port` removal + launch-instant mtime gate).
  Q2 prose says "A1 is the resolution of Q2"; A1 cross-references back.
- AMB3: A2 now states `instance-payload`'s fixed key list (ops.clj) is extended
  with `:readiness-timeout-ms`; stale-port rejection rides existing `:last-error`
  (`:phase :started-stale-port`), so no extra projected key. Verified the fixed
  key list in ops.clj `instance-payload`.
- AMB4: mtime gate = last-modified ≥ launch-instant **floored to whole seconds**
  (tolerates coarse FS mtime); pre-launch removal (Q2c) makes correctness
  independent of mtime precision — stated in Q2.
- AMB5: Q3 resolved to "both fixes ship unconditionally" — each defect is
  independently proven by code inspection (hard-coded 5s no-config path;
  first-parseable-port acceptance with no launch-ownership check), so no
  single-cause empirical finding scopes either out. Acceptance criteria add an
  explicit Q3 line; criteria referencing "confirmed Q1 surface" / "reasonable
  default" rewritten to the concrete Q1 decision.

No blocked design-steps. design.md verified internally coherent (Q1–Q4 ↔ A1/A2
↔ acceptance criteria align).

## Design review — inconsistency (ψ)

Scope: design.md only (not plan/steps). Cross-checked Q2/A1/A2 + acceptance vs
`started.clj`, `ops.clj`, `runtime.clj`, `config.clj`, `client.clj`,
`doc/project-nrepl.md`. Most claims verified consistent (Q1 precedence ↔ doc;
attach-port range idiom ↔ config.clj; `instance-payload` fixed key list ↔
ops.clj:22; `instance-payload`←`update-instance-in!` return chain ↔
runtime.clj/client.clj). New actionable inconsistencies:

- INC1 (launch-instant ownership contradiction). Q2/A1 split the launch-instant
  capture across two functions: pre-launch `.nrepl-port` removal happens in
  `start-instance-in!` "immediately before launching the process" (design 94,
  149) — i.e. the process is launched *inside* `start-instance-in!` (code:
  `(launcher effective-worktree validated-command)` then `(wait-for-started-
  endpoint! …)`). Yet A1/Q2 also assert `wait-for-started-endpoint!` "records the
  launch instant" (design 99, 151). By the time `wait-for-started-endpoint!`
  runs, the launch already happened in its caller, so it cannot record the true
  launch instant — it can only record its own entry time. The function that owns
  the real launch instant is `start-instance-in!`, which **already** records
  `:started-at (now)` into the runtime-handle on the success path
  (`started.clj`). Design contradicts itself on which function captures the gate
  reference instant and never reconciles the gate with the existing `:started-at`
  value. A builder must guess whether to (a) capture in `start-instance-in!` and
  thread the instant into `wait-for-started-endpoint!` (via `opts`), or (b) let
  `wait-for-started-endpoint!` self-time (looser gate). Resolve to one owner and
  state the threading.

- INC2 (`:last-error` `:phase` shape mismatch). A2 body is precise: stale-port
  rejection rides the existing `:last-error {:message :data :at}` projection and
  the `:phase :started-stale-port` lives inside `ex-data` (design 173–174,
  190–191) — matching `start-instance-in!`'s catch, which writes
  `:last-error {:message … :data (ex-data t) :at …}` (so `:phase` is nested under
  `:last-error → :data`). The acceptance criterion shorthand says "via
  `:last-error` with `:phase :started-stale-port`" (design 210), which reads as
  `:phase` being a direct key of `:last-error`. Minor but actionable: align the
  acceptance wording with the A2 body (phase is in `:last-error`'s `:data`/
  ex-data), so the projected shape has one interpretation.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Plan/steps review — ambiguity (ψ)

Scope: plan.md + steps.md only (not design.md). Grounded against
`started.clj`, `ops.clj`, `runtime.clj`, `started_test.clj`. New actionable
ambiguities a builder would otherwise guess at:

- PA1: `:readiness-timeout-ms` write-site ordering. Slice-2 step says set it
  "when the launch begins, alongside the existing status fields" — but in
  `started.clj` the *only* pre-launch instance write is
  `ensure-instance-in!`; every other status field (`:lifecycle-state`,
  `:endpoint`, …) is written by the *post-wait* success-path
  `update-instance-in!`. There is no `update-instance-in!` at the launch site
  today. For the timeout to be observable on a *timeout failure* (the
  diagnostic motivation), it must be written *before* `wait-for-started-
  endpoint!` throws. Plan/steps don't say which `update-instance-in!` writes it
  nor that it must precede the wait. Resolve: name the write site
  (ensure-instance-in! seed vs a new pre-wait update) and confirm it survives
  the failure path.

- PA2: `:started-at` move mechanism unspecified. Plan "Risks" + slice-3 steps
  say move `:started-at` "to the launch site", but `:started-at` is nested under
  `:runtime-handle` and is written by the *post-wait* success
  `update-instance-in!` (`(now)` inside the `update :runtime-handle merge …`).
  Capturing `launched-at` at the launch site but writing it into the
  runtime-handle still happens at the post-wait update unless an extra launch-site
  `update-instance-in!` is added. Steps don't specify whether to (a) add a
  launch-site update, or (b) capture a `launched-at` local and still write
  `:started-at launched-at` at the existing post-wait update (in which case it is
  not present on the failure path). Resolve the write mechanism.

- PA3: Stale-port rejection test vs pre-launch removal tension. Slice-3 steps
  require BOTH "pre-launch removal occurs" AND "pre-existing too-old `.nrepl-port`
  is rejected". But pre-launch removal deletes any pre-existing port *before*
  `(launcher …)`, so via `start-instance-in!` the mtime gate never observes a
  stale pre-existing port — only ports the launcher writes after delete (which
  are fresh). The existing `start-instance-in-test` launcher `spit`s the port
  itself, post-delete. Steps don't say the gate-rejection case must be exercised
  at the `wait-for-started-endpoint!` *unit* level with an injected
  `:launched-at` (bypassing removal). Resolve which level tests the gate.

- PA4: `ops/start` failure-path projection. Slice-2/A2 steps assert the new
  diagnostics are "surfaced through `instance-payload`", but `ops/start` reaches
  `instance-payload` only via `start-instance-in!`'s *return*; on the stale-port/
  timeout failure path `start-instance-in!` *throws*, so the `start` op return
  never projects the instance. The diagnostics are observable only via a separate
  `status` (op) read of the instance. Steps don't disambiguate that the
  failure-path diagnostic is a `status`-read surface, not the `start` return.
  Resolve (or confirm a `status`-read acceptance test covers it).

PASS_STATUS: ACTIONABLE_FEEDBACK

## Plan/steps ambiguity follow-up execution (ψ)

Executed all four newly-added plan/steps ambiguity follow-ups (PA1–PA4); all
resolved into plan.md + steps.md, no blockers (still design/plan stage — no
production code exists yet; resolutions are decisions grounded by reading
`started.clj`, `runtime.clj`, `ops.clj`, `started_test.clj`).

Unifying mechanism: a **single new launch-site `update-instance-in!`** in
`start-instance-in!`, placed after `ensure-instance-in!` and immediately before
`(launcher …)` / `wait-for-started-endpoint!`, writing the launch-time status
fields so they survive the throwing failure path.

- PA1 (`:readiness-timeout-ms` write site): written by the new pre-wait
  launch-site `update-instance-in!` as a top-level key (effective timeout =
  `(:timeout-ms opts)` else `default-readiness-timeout-ms`). Not seeded via
  `ensure-instance-in!` (its match-key set drives conflict detection), not
  deferred to the post-wait success update — so it is present on a timeout/
  stale-port *failure*. Plan "PA1" bullet + slice-2 step updated.
- PA2 (`:started-at` move): the same launch-site update writes runtime-handle
  `:started-at = launched-at` (one captured `(now)` local); the post-wait
  success-path `:started-at (now)` write is removed; `build-instance`'s
  top-level slot-creation `:started-at` (distinct field) is unchanged.
  `launched-at` also threads into `wait-for-started-endpoint!` via
  `opts {:launched-at …}` (one launch-instant source, INC1). Plan "PA2" +
  slice-3 steps updated.
- PA3 (gate-rejection test level): pre-launch removal in `start-instance-in!`
  means the gate only sees fresh ports there, so the mtime-gate *rejection* case
  is tested at the `wait-for-started-endpoint!` unit level with injected
  `:launched-at` + a pre-written too-old port; the `start-instance-in!`-level
  test asserts only pre-launch removal + fresh acceptance. Plan "PA3" + slice-3
  test step updated.
- PA4 (failure-path diagnostic surface): on the failure path
  `start-instance-in!` throws, so the `start` op return never projects via
  `instance-payload`; diagnostics (`:readiness-timeout-ms`; stale-port under
  `:last-error → :data` with `:phase :started-stale-port`) are observable via a
  separate `status` (op) read. Added a `status`-read acceptance test to slice-2.
  Plan "PA4" updated.

No blocked items. plan.md ↔ steps.md ↔ design.md verified coherent (launch-site
`update-instance-in!` ↔ PA1/PA2; gate-rejection unit-test level ↔ PA3; `status`
read surface ↔ PA4/A2).

PASS_STATUS: REVIEW_COMPLETE

## Plan/steps review — inconsistency (ψ)

Scope: plan.md + steps.md only (not design.md). Cross-checked against
`started.clj`, `ops.clj`, `runtime.clj`, `config.clj`, `started_test.clj`,
`deps.edn`, `bb.edn`. Most claims verified consistent: slice numbering plan↔steps
(1–5); `default-readiness-timeout-ms 5000→120000` ↔ code; `{:phase :validate}`
range idiom ↔ `resolved-attach-endpoint`; `wait-for-started-endpoint!` already
reads `:timeout-ms` from opts ↔ code; runtime-handle `:started-at` (started.clj)
vs `build-instance` top-level slot-creation `:started-at` are distinct fields ↔
code (PA2 correct); `instance-payload` fixed key list ↔ ops.clj; `:last-error
{:message :data :at}` catch shape ↔ code; no consumer reads runtime-handle
`:started-at` (eval `:active-op`/`:timing` `:started-at` are separate) ↔ grep, so
PA2's move is safe. New actionable inconsistency:

- PSI1 (wrong test runner invocation). steps.md Slice 1 says
  "Run `clojure -X:test` (project-nrepl) + `clj-kondo --lint`". The repo `:test`
  alias in `deps.edn` is a Kaocha `-M` runner (`:main-opts ["-m"
  "kaocha.runner"]`, no `:exec-fn`), so `clojure -X:test` is invalid and would
  fail. The project's unit-test command (bb.edn `clojure:test:unit`, AGENTS.md)
  is `clojure -M:test --focus unit` / `bb test`. This is inconsistent both with
  the runtime config and with the other slices (Slice 5 plan/steps say "full
  project-nrepl test suite" with no `-X:test` form). Replace the `-X:test`
  invocation with the actual Kaocha `-M:test --focus unit` (or `bb test`) command
  in slice 1 (and use the same command consistently where slices 2/3 say "Run
  tests").

PASS_STATUS: ACTIONABLE_FEEDBACK

## Follow-up execution — PSI1 resolved (2026-06-03)

Executed the single new actionable follow-up item (PSI1) from the preceding
plan/steps inconsistency review.

- Fixed slice-1 test-runner wording in steps.md: replaced the invalid
  `clojure -X:test` (project-nrepl) form with the repo's actual Kaocha command
  `clojure -M:test --focus unit` (namespace-focused variant
  `clojure -M:test --focus project-nrepl.config-test`; `bb clojure:test:unit`
  equivalently).
- Verification grounding: `project-nrepl/test` is a member of the `:unit`
  Kaocha suite in `tests.edn`; bb.edn `clojure:test:unit` runs
  `bb.kaocha-runner/run! ["--focus" "unit"]`; the root `:test` alias is a
  Kaocha `-M` runner with no `:exec-fn`, confirming `clojure -X:test` is
  invalid. No `clojure -X:test` reference exists outside steps.md slice 1
  (design.md's `clojure -M …` mentions are the slow-boot start command being
  timed, not a test runner). Slices 2/3/5 already use generic "Run tests" /
  "full project-nrepl test suite" wording, so no further edits needed for
  consistency.
- Scope: task-artifact-only (steps.md). PSI1 references no code/test/doc, so
  none were touched. Marked PSI1 `[x]` with an inline `→ Resolved` note.

## Implementation execution (ψ, 2026-06-03)

All five slices executed; `steps.md` fully checked. Plan/design/plan-review
were all REVIEW_COMPLETE, so the plan gate was satisfied; production code added
directly per the slice order.

- **Slice 1 — config surface.** Added `resolved-start-readiness-timeout-ms` to
  `config.clj` (reads `[:project-nrepl :start-readiness-timeout-ms]`; `nil`
  unset; `cond` range `[1000 600000]` integer; `{:phase :validate}` throw —
  exact `resolved-attach-endpoint` idiom). 5 `config_test.clj` cases (unset /
  in-range×3 / below / above / non-integer / `:phase :validate`). Commit, lint
  0/0, 8 tests/40 assertions green.

- **Slices 2+3 — committed together** because they share the single new
  pre-wait launch-site `update-instance-in!` (PA1/PA2 unifying mechanism), so
  splitting the commit would leave a half-written launch-site update.
  - Slice 2: `default-readiness-timeout-ms 5000→120000`; `ops/start` resolves
    the config timeout and threads `{:timeout-ms …}` (only when non-nil) as the
    4th `opts` arg to `start-instance-in!`; new pre-wait launch-site
    `update-instance-in!` writes top-level `:readiness-timeout-ms` (effective =
    `(:timeout-ms opts)` else default, matching the wait's fallback);
    `instance-payload` projects `:readiness-timeout-ms` (AMB3).
  - Slice 3: one `launched-at (now)` captured at the launch site → both
    runtime-handle `:started-at` (post-wait `:started-at` write removed, PA2)
    and the gate reference threaded via `opts :launched-at` (INC1); pre-launch
    `.nrepl-port` deletion (`dot-nrepl-port-file` `.delete`); `wait-for-
    started-endpoint!` mtime gate (`floored-to-whole-seconds`, AMB4) accepting
    only `mtime ≥ floor`, continuing to poll a too-old port, throwing
    `:phase :started-stale-port` on a deadline reached with only a stale port.
  - Tests: unit-level gate reject (`:started-stale-port`) + accept (injected
    `:launched-at`, `setLastModified` to force mtime); `start-instance-in!`
    pre-launch removal + fresh acceptance; `:readiness-timeout-ms` + launch-
    instant `:started-at` recording; `status`-read of the failure-path instance
    (PA4 — `:readiness-timeout-ms` + `:last-error :data :phase
    :started-stale-port`). No mocks; existing `process-launcher`/`nrepl-
    connector` seams. Full project-nrepl suite green (28 tests/169 assertions).

- **Deviation — consuming-test arity (not in the original steps).** Adding the
  4th `opts` arg to the `ops/start` → `start-instance-in!` call broke the
  agent-session consuming test `project-nrepl-extension-install-test`, whose
  `with-redefs` stub of `start-instance-in!` was a fixed 3-arg fn →
  `ArityException (4)`. Widened the stub to `(fn [ctx worktree-path
  command-vector & _opts] …)` (matching the real fn's optional `opts` arity).
  Separate commit. (`with-redefs` here is a pre-existing consuming-test idiom
  outside this task's no-mocks scope; only the arity was touched.) Full unit
  suite (`bb clojure:test:unit`) green.

- **Slice 4 — docs + CHANGELOG.** `doc/project-nrepl.md` documents
  `:start-readiness-timeout-ms` (default 120000, range, precedence, status
  observability) + the stale-port ownership guard. `CHANGELOG.md [Unreleased]`:
  Added config key; Fixed slow-boot timeout + stale-port wrong-endpoint bugs.

- **Slice 5 — coherence.** `clj-kondo --lint` 0/0 over
  `components/project-nrepl/{src,test}`; full `bb clojure:test:unit` green;
  `bb commit-check:file-lengths` exit 0 (started.clj 185, started_test.clj 206
  — both < 800). All design acceptance criteria verified satisfied:
  raised-120000-default slow boot (no per-call config); stale-port wrong-
  endpoint prevented (pre-launch removal + mtime gate in started.clj only,
  shared discovery + attach untouched); A2 status projection
  (`:readiness-timeout-ms` field + `instance-payload`; `:phase
  :started-stale-port` on `:last-error → :data`); Q1 config +
  `[1000 600000]` validation; both fixes unconditional (Q3); attach/happy-path
  preserved; no-mocks tests; docs.

PASS_STATUS: IMPLEMENTATION_COMPLETE

## Implementation review — quality (ψ, 2026-06-03)

Scope: implementation vs design/plan/architecture. Verified locally: lint 0/0
(`clj-kondo` src+test); `started-test` 3/22 green; combined
`config/ops/attach/started + consuming extension-install` 16 tests/101 assertions
green; `started.clj` 185 / `started_test.clj` 206 lines (< 800). Matches design
(config key, raised 120000 default, pre-launch removal + mtime gate,
`:phase :started-stale-port`, `:readiness-timeout-ms` projection, `[1000 600000]`
validation) and architecture (guard in started-mode layer only; shared
`read-dot-nrepl-port` + attach untouched; runtime-handle ownership respected).
Strong traceability (INC1/INC2/AMB3/AMB4/PA1–PA4 ↔ code).

New actionable issue:

- IR1 (stale-port diagnostic lost on exit-with-stale-port path). In
  `wait-for-started-endpoint!` the `process-exited?` branch is checked *before*
  the deadline branch and unconditionally throws `:phase :started-readiness`
  (`:command-exited? true`), ignoring the already-computed `endpoint`/`fresh?`.
  If the launched process writes only a too-old `.nrepl-port` and then exits —
  the exact stale-port scenario A2's `:started-stale-port` diagnostic exists to
  surface — the wait reports `:started-readiness`, not `:started-stale-port`, so
  the A2 stale-port distinction is silently lost on this path. Not a correctness
  bug (a failure is still reported, and pre-launch removal still prevents wrong-
  endpoint connection), but the observable diagnostic does not match A2's intent
  when exit and a stale-only port coincide. Consider folding the stale-only
  condition into the exit-path payload (or asserting which diagnostic wins on
  exit-with-stale-port) so the projected `:phase` is deterministic. No test
  covers exit-with-stale-port today.

Non-blocking observation (already documented, no new step):

- The slices-2/3 deviation widened a `with-redefs` stub arity in
  `project_nrepl_extension_install_test.clj` (a mock, counter to the no-mocks
  standard). implementation.md already records this as a pre-existing consuming-
  test idiom outside this task's no-mocks scope and only the arity was touched —
  acceptable; recorded here only for completeness, no follow-up step.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Implementation review — quality follow-up IR1 executed (ψ, 2026-06-03)

IR1 (stale-port diagnostic lost on exit-with-stale-port path) resolved.
`wait-for-started-endpoint!`'s `process-exited?` branch now folds in the
stale-only condition: when `(and endpoint (not fresh?))` at exit it throws
`:phase :started-stale-port` (retaining `:command-exited? true` + `:exit-code`,
and carrying `:path`/`:port-mtime-ms`/`:min-mtime-ms`/`:launched-at`); otherwise
it keeps `:phase :started-readiness`. Decision: the **stale-port diagnostic wins**
on exit-with-stale-port, so A2's `:started-stale-port` distinction is preserved
on the exit path as well as the deadline path — the projected `:phase` is now
deterministic and matches A2's intent whenever a stale-only port and process exit
coincide. Pre-launch removal already guards correctness; this change is
diagnostic-only (no correctness/behaviour change for the non-stale exit path).

Added unit test `exit leaving only a stale port reports :started-stale-port
(IR1)` in `wait-for-started-endpoint-stale-port-gate-test` (`alive? false`,
too-old port, injected `:launched-at`): asserts `:phase :started-stale-port`,
`:command-exited? true`, and the "exited leaving only a stale" message. The
pre-existing "exits before port discovery" test (no stale port) still asserts
`:started-readiness`, pinning the non-stale exit path.

Verified: clj-kondo 0/0 (src+test); `started-test` 3 tests/25 assertions green
(+3 over the prior 22); `started/ops/config/attach` combined 15 tests/99
assertions green; consuming `project-nrepl-extension-install-test` 1 test/5
assertions green; clj-paren-repair Success; `started.clj` 198 / `started_test.clj`
228 lines (< 800). No design/doc/CHANGELOG change (no user-visible surface
change — the new `:phase` was already a documented A2 outcome; this only makes it
fire on one more code path).

PASS_STATUS: REVIEW_COMPLETE

## Implementation review — quality (ψ, 2026-06-03, pass 2)

Independent re-review against design/plan/architecture after IR1 resolution.
Verified locally: clj-kondo 0/0 (`components/project-nrepl/{src,test}`);
`started/config/ops` focused suite 13 tests/82 assertions green; consuming
`project-nrepl-extension-install-test` widened-arity stub correct (`& _opts`).

Traceability confirmed end-to-end:
- Q1 config: `resolved-start-readiness-timeout-ms` mirrors the
  `resolved-attach-endpoint` `cond` range idiom (`[1000 600000]`,
  `:phase :validate`); `nil` unset → started.clj default fallback.
- Raised default `120000` in `started.clj`; threaded via `ops/start`
  (`cond-> {} (some? timeout-ms) (assoc :timeout-ms …)`), preserving the
  missing-start-command path.
- Stale-port guard wholly in `started.clj` (A1): pre-launch
  `dot-nrepl-port-file .delete`; single `launched-at (now)` at launch site →
  both runtime-handle `:started-at` and `opts :launched-at`; post-wait
  `:started-at` write removed (PA2); mtime gate `floored-to-whole-seconds`
  (AMB4); `:phase :started-stale-port` on deadline and (IR1) on
  exit-with-stale-port. Shared `read-dot-nrepl-port` + attach untouched.
- A2 projection: `:readiness-timeout-ms` written by the pre-wait launch-site
  `update-instance-in!` (survives the throwing failure path) and added to
  `instance-payload`'s key list (AMB3); stale-port rides `:last-error → :data`.
- Tests no-mocks via `process-launcher`/`nrepl-connector` seams; PA3 split
  (unit-level gate rejection vs `start-instance-in!`-level pre-launch removal)
  honoured; PA4 `status`-read failure-path test present.

Non-blocking observation (no new step — within design tolerance):
- The freshness gate is fail-open on a TOCTOU stat miss: `mtime-ms` is a second
  independent `port-file-mtime-ms` stat after `read-dot-nrepl-port-safe`, and
  `fresh?` short-circuits to `true` when `(nil? mtime-ms)`. If the port file
  vanishes between read and stat, a just-read port is accepted without the gate.
  This is consistent with the design's explicit framing that pre-launch removal
  "guarantees correctness even when the gate is lenient" (Q2/A1 defence-in-depth)
  — the gate is intentionally lenient and correctness does not depend on it.
  Recorded for completeness; not actionable.

No new actionable issues. Implementation matches design + architecture; no
unjustified new pattern, abstraction, or structural performance concern.

PASS_STATUS: REVIEW_COMPLETE

## Test review — implementation tests (ψ, 2026-06-03)

Skill: task-test-review — `well_formed(tests)` ∧ `∀b∈behaviour(design).∃t.covers(t,b)`
∧ `∀d∈infra_deps. injectable ∧ nullable ∧ ¬mock ∧ ¬stub`. Scope: the task's
tests vs design acceptance criteria. Read: `started_test.clj`, `config_test.clj`,
`ops_test.clj`, `test_support.clj`, `started.clj`, `config.clj`, `ops.clj`, and
the consuming `project_nrepl_extension_install_test.clj`. Ran focused suites:
`started-test` 3/25, `config+ops` 10/57 — all green.

Well-formed: tests are deterministic, use `testing` blocks, file-backed real
`.nrepl-port` readiness, temp dirs cleaned in `finally`. No-mocks: the
component's new tests inject **real nullable seams** (`fake-process` = real
`Process` proxy; `fake-connector` = real fn; `process-launcher`) and assert
state/return, not interactions — compliant with the no-mocks standard. The
`mtime` gate is exercised via `setLastModified` on real files (no time mocking).

Coverage is strong for: Q1 config range validation (`config_test`
`resolved-start-readiness-timeout-ms-test`: unset/in-range×3/below/above/
non-integer + `:phase :validate`); the stale-port gate (unit reject/accept,
IR1 exit-with-stale, pre-launch removal + fresh acceptance); the happy path
(`start-instance-in-test` → `:ready`); PA4 failure-path `status` read.

New actionable test-coverage gaps (each maps to a design acceptance criterion
with **no** covering test; not duplicates of IR1 or the with-redefs note):

- TR1 (raised-default behaviour untested). The headline acceptance criterion —
  "reaches `:started` with the **raised `120000` ms default** and no per-call
  config required" — has no test pinning that the **no-opts** path uses
  `default-readiness-timeout-ms = 120000`. `config_test`'s `120000` references
  only assert the *config value* passes validation; no test asserts that
  `start-instance-in!`/`wait-for-started-endpoint!` with no `:timeout-ms`
  records/uses `120000`. A `start-instance-in!`-with-no-opts test asserting
  `(:readiness-timeout-ms instance) = 120000` (the effective default) would pin
  the raise so a regression to `5000` is caught. Today only the *configured*
  timeout (`90000` in `start-instance-in-test`) is asserted, which would still
  pass if the default silently reverted.

- TR2 (happy-path `instance-payload` `:readiness-timeout-ms` projection
  untested at the ops level). AMB3 acceptance — `instance-payload`'s projected
  key list is extended with `:readiness-timeout-ms` and surfaced through it — is
  only exercised by the PA4 *failure-path* `status` read in `started_test`.
  `ops_test.clj` has no assertion that a normal present instance's
  `instance-payload`/`status` includes `:readiness-timeout-ms`. An `ops_test`
  case asserting `status`/`instance-payload` projects `:readiness-timeout-ms` on
  a ready instance would pin the projection (AMB3) at its owning layer, so a
  future `instance-payload` key-list edit dropping it is caught independently of
  the started-mode failure path.

Both gaps are coverage-only (the production code is correct and verified by
inspection); they harden regression detection for two named acceptance criteria.
The `with-redefs` stub in the consuming
`project_nrepl_extension_install_test.clj` is already documented as a
pre-existing out-of-scope idiom (arity-only touch) — no new step.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-review follow-up execution — TR1/TR2 (ψ, 2026-06-03)

Executed both newly-added test-coverage follow-ups (TR1, TR2) from the preceding
test review. Both are coverage-only (production code already correct and
verified); no production change.

- TR1 (raised-default behaviour). Added `started_test.clj` case "no :timeout-ms
  opts records the raised 120000 ms default (TR1)" inside `start-instance-in-test`:
  a real runtime-handle launcher seam (file-backed `.nrepl-port`), **no**
  `:timeout-ms` opt, asserting `(:readiness-timeout-ms instance) = 120000`. Pins
  the effective `default-readiness-timeout-ms` so a silent regression to `5000`
  is caught (the prior tests only asserted the *configured* `90000`).

- TR2 (happy-path ops-level projection). Added `ops_test.clj`
  `status-readiness-timeout-projection-test`: installs a real ready attached
  instance via `install-instance!`, sets `:readiness-timeout-ms 120000` through
  `update-instance-in!`, then asserts `ops/status` returns `:present`,
  `:readiness true`, and projects `:readiness-timeout-ms 120000` through
  `instance-payload` (AMB3). Pins the projected-key-list extension at its owning
  ops layer, independent of the started-mode PA4 failure-path `status` read.
  Required adding `psi.project-nrepl.runtime` to the `ops_test` require.

Verified: clj-paren-repair Success (both files, no changes needed); clj-kondo
0/0 over `components/project-nrepl/test`; `started-test` + `ops-test` 6 tests/46
assertions green (the two new tests included); remaining project-nrepl unit
suite (config/attach/eval/commands/runtime/client) 23 tests/130 assertions
green. File lengths: `started_test.clj` 248, `ops_test.clj` 87 (< 800). No
design/doc/CHANGELOG change (coverage-only; no user-visible surface change).

PASS_STATUS: REVIEW_COMPLETE

## Test review — implementation tests (ψ, 2026-06-03, pass 2)

Independent re-review against the design acceptance criteria after the TR1/TR2
follow-ups landed. Skill: task-test-review — `well_formed(tests)` ∧
`∀b∈behaviour(design).∃t.covers(t,b)` ∧ `∀d∈infra_deps. injectable ∧ nullable ∧
¬mock ∧ ¬stub`. Read: `started_test.clj`, `config_test.clj`, `ops_test.clj`,
`test_support.clj`, `started.clj`, `config.clj`, `ops.clj`. Verified prior gaps
closed: TR1 (`no :timeout-ms opts records 120000`) and TR2
(`status-readiness-timeout-projection-test`) present and asserting the right
state. No-mocks compliance holds: real nullable seams (`fake-process` proxy,
`fake-connector` fn, file-backed `.nrepl-port`, `setLastModified` mtime), state/
return assertions, no interaction assertions.

New actionable coverage gap (not a duplicate of IR1/TR1/TR2 or the
with-redefs note):

- TR3 (`ops/start` config→opts threading untested end-to-end). The Q1 acceptance
  criterion is that the timeout is *controllable through the
  `[:agent-session :project-nrepl :start-readiness-timeout-ms]` config key*. The
  pieces are covered in isolation — `config_test` pins
  `resolved-start-readiness-timeout-ms`'s value/validation; `started_test` pins
  `start-instance-in!` recording a *directly-passed* `:timeout-ms` (90000) and
  the no-opts default (TR1) — but **no test drives a *configured* timeout from
  project config through `ops/start`'s glue** (`ops.clj`:
  `(resolved-start-readiness-timeout-ms cfg)` → `cond-> {} (some? timeout-ms)
  (assoc :timeout-ms …)` → `start-instance-in!`). `ops_test/start-test` only
  exercises the missing-start-command path, and TR2 sets `:readiness-timeout-ms`
  directly via `update-instance-in!`, bypassing the config resolution. A
  regression in that ops seam (dropped `assoc`, wrong config key, not resolving
  from `cfg`) passes every current test. An `ops_test` case writing a project
  config with `:start-command` + a configured `:start-readiness-timeout-ms`,
  driving `ops/start` over a real `:runtime-handle` launcher seam, and asserting
  the resulting instance's `:readiness-timeout-ms` matches the configured value
  would pin the central Q1 integration path. Coverage-only (production verified
  by inspection: `ops.clj`:76-78 reads from `cfg` and threads correctly); this
  hardens regression detection for the headline configurability criterion.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-review follow-up execution — TR3 (ψ, 2026-06-03)

Executed the single newly-added test-coverage follow-up (TR3) from the preceding
test review pass 2. Coverage-only (production already correct and verified by
inspection: `ops.clj` reads `(resolved-start-readiness-timeout-ms cfg)` and
threads it via `cond-> {} (some? timeout-ms) (assoc :timeout-ms …)`); no
production change.

- TR3 (`ops/start` config→opts threading end-to-end). Added `ops_test.clj`
  `start-config-timeout-threading-test`. Writes a project
  `<worktree>/.psi/project.edn`
  (`{:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
  :start-readiness-timeout-ms 90000}}}`) via a new `write-project-config!`
  helper, then drives `ops/start` over a real launcher/connector seam and
  asserts both the `start` return *and* a follow-up `status` read project
  `:readiness-timeout-ms 90000`. This pins the central Q1 configurability path
  config-key → `resolve-config` → `resolved-start-readiness-timeout-ms` →
  `ops/start`'s `cond-> opts` → `start-instance-in!`, so a regression in the
  ops glue (dropped `assoc`, wrong config key, not reading from `cfg`) is caught
  — distinct from `config_test` (value/validation only), `started_test` TR1
  (no-opts default) / directly-passed `:timeout-ms` (90000), and TR2
  (`update-instance-in!`-set, bypassing config).

- Runtime-handle seam resolution (the note's "resolve the pre-seed vs
  `ensure-instance-in!` conflict-detection seam"). `ops/start` calls
  `start-instance-in!` with only `{:timeout-ms …}` (no `:runtime-handle`), so the
  launcher would default to `real-process-launcher` and spawn a real process.
  Resolved by **pre-seeding** the slot via `ensure-instance-in!`
  (`:acquisition-mode :started`, `:command-vector` = same command, nil
  `:endpoint`, `:lifecycle-state :starting`, `:runtime-handle {:process-launcher
  … :nrepl-connector …}`). `start-instance-in!`'s own `ensure-instance-in!`
  request matches that active slot on `(acquisition-mode, endpoint,
  command-vector)`, so it returns the existing instance (no conflict) and the
  seeded `:runtime-handle` survives — the seam launcher is used. `:starting`
  (not `:ready`) is chosen so `ops/start` does not short-circuit on
  `(:readiness existing)` before launching. No mocks: real `live-fake-process`
  proxy + the shared `fake-connector` seam + file-backed `.nrepl-port`.

Verified: clj-paren-repair Success; clj-kondo 0/0
(`components/project-nrepl/{src,test}`); `ops-test` 4 tests/23 assertions green
(new test included); `started/config/attach` 13 tests/83 assertions green;
`bb commit-check:file-lengths` exit 0 (`ops_test.clj` 153 lines < 800). No
design/plan/doc/CHANGELOG change (coverage-only; no user-visible surface
change).

PASS_STATUS: REVIEW_COMPLETE

## Test review — implementation tests (ψ, 2026-06-03, pass 3)

Independent re-review against the design acceptance criteria after TR1/TR2/TR3
landed. Skill: task-test-review — `well_formed(tests)` ∧
`∀b∈behaviour(design).∃t.covers(t,b)` ∧ `∀d∈infra_deps. injectable ∧ nullable ∧
¬mock ∧ ¬stub`. Read: `started_test.clj`, `config_test.clj`, `ops_test.clj`,
`attach_test.clj`, `test_support.clj`, `started.clj`, `config.clj`, `ops.clj`.
Ran `bb clojure:test:unit` — all green. Verified prior gaps closed (TR1
no-opts-default 120000; TR2 ops-level `:readiness-timeout-ms` projection; TR3
config→ops→opts threading). No-mocks compliance holds: real `fake-process`/
`live-fake-process` proxies, `fake-connector` fn, file-backed `.nrepl-port`,
`setLastModified` mtime — state/return assertions, no interaction assertions.

New actionable coverage gap (not a duplicate of IR1/TR1/TR2/TR3 or the
with-redefs note):

- TR4 (A1 attach/shared-discovery "accepts stale port" semantics unproven).
  The A1 acceptance criterion is that the stale-port gate lives *only* in
  `started.clj`, "leaving the shared `config/read-dot-nrepl-port` discovery
  primitive and attach-mode discovery unchanged" — i.e. attach-mode and the
  shared primitive must keep accepting *whatever `.nrepl-port` is present, by
  design*, with **no** mtime gate. But every existing attach/discovery test
  (`attach_test.clj` `resolve-attach-endpoint-test` fallback case;
  `config_test.clj` `read-dot-nrepl-port-test`) writes a **freshly-`spit`** port
  and accepts it — none ever calls `setLastModified` to age the file. So a
  regression that accidentally introduced an mtime/launch gate into the *shared*
  `read-dot-nrepl-port` or into `attach/resolve-attach-endpoint` would still pass
  every current test, because the fixtures only present *fresh* ports. The
  differentiating A1 behaviour — that an **old (stale-mtime) `.nrepl-port` is
  still accepted** by attach-mode / shared discovery (the exact opposite of the
  started-mode gate) — has no covering test. Add a case (in `attach_test.clj`
  for `resolve-attach-endpoint`'s fallback, and/or `config_test.clj` for
  `read-dot-nrepl-port`) that writes a `.nrepl-port`, `setLastModified` to well
  before now (mirroring the started-mode `(- now 60000)` stale fixture), and
  asserts the endpoint is **still resolved** — pinning that no stale gate leaked
  into the shared/attach path (the A1 separation), symmetric to the started-mode
  stale-rejection tests. Coverage-only (production verified by inspection: the
  gate lives only in `started/wait-for-started-endpoint!`); hardens the headline
  A1 "attach unchanged" criterion against a future gate leak.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-review follow-up execution — TR4 (ψ, 2026-06-03)

Executed the single newly-added test-coverage follow-up (TR4) from the preceding
test review pass 3. Coverage-only (production already correct and verified by
inspection: the launch-instant mtime gate lives only in
`started/wait-for-started-endpoint!`; the shared `config/read-dot-nrepl-port`
and `attach/resolve-attach-endpoint` are mode-agnostic read+validate with no
gate); no production change.

- TR4 (A1 attach/shared-discovery "accepts a stale port"). Added two symmetric
  stale-acceptance cases mirroring the started-mode `(- now 60000)` stale
  fixture, pinning the A1 separation against a future gate leak into the
  shared/attach path:
  - `attach_test.clj` `resolve-attach-endpoint-test`: "accepts a stale
    (old-mtime) `.nrepl-port` — no started-mode gate in attach". Spits
    `.nrepl-port` then `setLastModified` to `(- now 60000)`, asserts
    `resolve-attach-endpoint` still resolves
    `{:host "127.0.0.1" :port 7999 :port-source :dot-nrepl-port}` (fallback when
    explicit port absent) — the exact opposite of the started-mode gate.
  - `config_test.clj` `read-dot-nrepl-port-test`: "accepts a stale (old-mtime)
    `.nrepl-port` — no started-mode gate in shared read". Same aging fixture,
    asserts the mode-agnostic primitive still reads
    `{:port 7888 :port-source :dot-nrepl-port}`.
  Both differentiate the A1 behaviour from the started-mode stale-rejection:
  a regression leaking an mtime/launch gate into the shared `read-dot-nrepl-port`
  or `attach/resolve-attach-endpoint` would now fail these tests (prior fixtures
  only presented fresh ports, so such a leak passed every test).

Verified: clj-paren-repair Success (both files); clj-kondo 0/0 over both changed
test files; `psi.project-nrepl.attach-test` + `psi.project-nrepl.config-test`
10 tests/59 assertions green (the two new `testing` blocks included; +19
assertions over the prior 40). No design/plan/doc/CHANGELOG change (coverage-only;
no user-visible surface change).

PASS_STATUS: REVIEW_COMPLETE

## Test review — implementation tests (ψ, 2026-06-03, pass 4)

Independent re-review against the design acceptance criteria after TR1–TR4
landed. Skill: task-test-review — `well_formed(tests)` ∧
`∀b∈behaviour(design).∃t.covers(t,b)` ∧ `∀d∈infra_deps. injectable ∧ nullable ∧
¬mock ∧ ¬stub`. Read: `started_test.clj`, `config_test.clj`, `ops_test.clj`,
`attach_test.clj`, `test_support.clj`, `started.clj`, `config.clj`, `ops.clj`.
Ran `started-test` (3 tests/26 assertions green). Verified prior gaps closed
(TR1 no-opts-default 120000; TR2 ops-level projection; TR3 config→ops→opts
threading; TR4 attach/shared-discovery stale acceptance). No-mocks compliance
holds: real `fake-process`/`live-fake-process` proxies, `fake-connector` fn,
file-backed `.nrepl-port`, `setLastModified` mtime — state/return assertions, no
interaction assertions.

New actionable coverage gap (not a duplicate of IR1/TR1/TR2/TR3/TR4 or the
with-redefs note):

- TR5 (plain deadline-timeout `:started-readiness` path untested — the original
  reproduction's failure mode). `wait-for-started-endpoint!`'s deadline branch
  has two outcomes: `:started-stale-port` (a too-old port is present —
  tested by `wait-for-started-endpoint-stale-port-gate-test`) and the plain
  `:started-readiness` else-branch (an alive process, **no** `.nrepl-port`
  ever appears, deadline fires). The plain `:started-readiness` deadline path is
  the *exact* failure mode of the design's reproduction
  (`:phase :started-readiness`, `:timeout-ms`, no fresh port) and the headline
  acceptance criterion names it as the negative behaviour the raised 120000 ms
  default exists to avoid. Yet no test exercises it: the only `:started-readiness`
  assertion (`#"exited before \.nrepl-port became ready"`) hits the **exit**
  branch (`alive? false`), not the deadline branch. A regression that broke the
  deadline branch's plain payload (`:phase :started-readiness`, `:timeout-ms`,
  `:path`) — e.g. by mis-routing it to `:started-stale-port`, dropping
  `:timeout-ms`, or the `(and endpoint (not fresh?))` guard inverting — would
  pass every current test. Add a `wait-for-started-endpoint!` unit case: an
  **alive** `fake-process`, an **empty** temp dir (no `.nrepl-port` ever
  written), a short `:timeout-ms`, asserting the thrown ex carries
  `:phase :started-readiness`, the configured `:timeout-ms`, and `:path`, and
  that `:command-exited?` is **absent/false** (distinguishing it from the exit
  branch). Coverage-only (production verified by inspection: started.clj's
  deadline else-branch is correct); pins the deadline-timeout diagnostic shape
  for the criterion's named negative outcome, symmetric to the
  `:started-stale-port` deadline test.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-review follow-up execution — TR5 (ψ, 2026-06-03)

Executed the single newly-added test-coverage follow-up (TR5) from the preceding
test review pass 4. Coverage-only (production already correct and verified by
inspection: `wait-for-started-endpoint!`'s deadline else-branch throws
`:phase :started-readiness` with `:timeout-ms`/`:path` when no fresh port ever
appears); no production change.

- TR5 (plain deadline-timeout `:started-readiness` path — the original
  reproduction's failure mode). Added `started_test.clj`
  `wait-for-started-endpoint-test` case "plain deadline timeout (alive process,
  no .nrepl-port) reports :started-readiness (TR5)": an **alive** `fake-process`,
  an **empty** temp dir (no `.nrepl-port` ever written), `:timeout-ms 100` /
  `:poll-interval-ms 10`. Asserts the thrown `ExceptionInfo` carries
  `:phase :started-readiness`, `:timeout-ms 100`, `:path` =
  `<dir>/.nrepl-port`, `:command-exited?` absent/false (the process is alive →
  distinguishes the deadline branch from the exit branch), and the "Timed out
  waiting for started project nREPL" message (distinct from the stale-port
  deadline's "only a stale port was present"). Pins the plain deadline
  else-branch — the design reproduction's exact `:phase :started-readiness`
  failure mode — so a regression mis-routing the deadline timeout to
  `:started-stale-port`, dropping `:timeout-ms`, or inverting the
  `(and endpoint (not fresh?))` guard now fails green. Symmetric to the
  `:started-stale-port` deadline test.

Verified: clj-paren-repair Success; clj-kondo 0/0
(`components/project-nrepl/test/psi/project_nrepl/started_test.clj`);
`started-test` 3 tests/31 assertions green (+5 over pass-4's 26);
`started_test.clj` 271 lines (< 800). No design/plan/doc/CHANGELOG change
(coverage-only; no user-visible surface change).

PASS_STATUS: REVIEW_COMPLETE

## Test review — implementation tests (ψ, 2026-06-03, pass 5)

Independent re-review against the design acceptance criteria after TR1–TR5
landed. Skill: task-test-review — `well_formed(tests)` ∧
`∀b∈behaviour(design).∃t.covers(t,b)` ∧ `∀d∈infra_deps. injectable ∧ nullable ∧
¬mock ∧ ¬stub`. Read: `started_test.clj`, `config_test.clj`, `ops_test.clj`,
`attach_test.clj`, `test_support.clj`, `started.clj`, `config.clj`, `ops.clj`.
Ran `started/config/ops/attach` focused suite — 17 tests/113 assertions green.

Verified well-formed: deterministic, `testing` blocks, file-backed real
`.nrepl-port`, real-file `setLastModified` mtime (no time mocking), temp dirs
cleaned in `finally`. No-mocks: the component's tests inject only real nullable
seams (`fake-process`/`live-fake-process` real `Process` proxies,
`fake-connector` fn, `process-launcher`/`nrepl-connector`) and assert
state/return, never interactions. The single `with-redefs` lives in the
consuming `agent-session` `project_nrepl_extension_install_test.clj` (arity-only
touch) — already documented as a pre-existing out-of-scope idiom; confirmed no
other `with-redefs`/mock/stub in `components/project-nrepl/test`.

Acceptance-criterion → test mapping, all covered:
- raised 120000 default → `started_test` TR1 (no-opts → `:readiness-timeout-ms
  120000`).
- timeout configurability: config validation → `config_test`
  `resolved-start-readiness-timeout-ms-test`; end-to-end config→ops→opts → TR3
  `start-config-timeout-threading-test`; wait honours a passed `:timeout-ms` in
  its deadline/error payload → TR5 (asserts thrown `:timeout-ms 100`).
- stale-port gate reject/accept → `wait-for-started-endpoint-stale-port-gate-test`;
  exit-with-stale → IR1; pre-launch removal + fresh acceptance →
  `start-instance-in-test`.
- deadline diagnostics: `:started-stale-port` → gate test; plain
  `:started-readiness` (the reproduction's mode) → TR5.
- A2 projection `:readiness-timeout-ms`: happy/ops level → `ops_test` TR2;
  failure-path `status` read + stale-port on `:last-error → :data` → `started_test`
  PA4.
- A1 attach/shared-discovery unchanged (accepts stale port) → `attach_test` /
  `config_test` TR4.
- behaviour-preserving happy path / attach-mode → `start-instance-in-test`,
  `attach-instance-in-test`.

No new actionable test-coverage gap. Every design behaviour maps to a real,
no-mock, state/return-asserting test; the prior five passes (TR1–TR5, IR1, PA4)
closed each acceptance-criterion gap. One residual coupling — the recorded
`:readiness-timeout-ms` and the wait's deadline are two independent
`(or (:timeout-ms opts) default)` reads of the same `opts` — is within design
tolerance (same expression, design states it is verified by inspection; TR5 pins
the wait's use of a passed `:timeout-ms`, TR1/TR3 pin the recorded value);
recorded for completeness, not actionable.

PASS_STATUS: REVIEW_COMPLETE

## Test-shaper review — test quality/shape (ψ, 2026-06-03)

Skill: test-shaper — `clarity ∧ signal ∧ robustness ∧ economical`, distinct from
the prior five task-test-review *coverage* passes (TR1–TR5: behaviour↔test
mapping). This pass evaluates the *shape* of the now-complete tests: simple,
consistent, robust, economical. Read: `started_test.clj`, `ops_test.clj`,
`config_test.clj`, `attach_test.clj`, `test_support.clj`, `started.clj`. Ran the
focused `started/ops/config/attach` suite — green.

Strengths: deterministic file-backed `.nrepl-port` readiness (no time mocking on
the happy path); state/return assertions, no interaction assertions; the shared
`test_support.clj` already single-sources `make-ctx`/`temp-dir`/`delete-tree!`/
`fake-connector`/`install-instance!`; every temp dir is cleaned in `finally`;
`testing` strings name the behaviour and cite the criterion (TRn/PA4/IR1).

New actionable shape issues (economy + consistency — not coverage gaps, so
distinct from TR1–TR5/IR1):

- TS1 (duplicated `Process` proxy — `consistent(fixtures)` ∧
  `helpers_that_compress(ceremony)`). The 16-method `java.lang.Process` proxy is
  written twice: `started_test/fake-process` (parameterised on
  `{:alive? :exit-code :pid :destroyed*}`) and `ops_test/live-fake-process`
  (hardcoded alive / exit 0 / pid 4321). `live-fake-process` is a strict special
  case of `fake-process` (verified: the 9 boilerplate stub methods —
  `toHandle`/`info`/`children`/`descendants`/`get*Stream` — are byte-identical;
  the only difference is the parameterised vs hardcoded alive/exit/pid). The big
  proxy ceremony is incidental setup duplicated across two files. Lift one
  `fake-process` into `test_support.clj` (the existing component-local
  test-helper home, whose docstring already enumerates the shared seams) and have
  `ops_test` call it with `{:alive? true :exit-code 0 :pid 4321}`, deleting
  `live-fake-process`. Compresses ceremony, single-sources the `Process` seam
  shape, and keeps the two files consistent. Shape-only; behaviour-preserving.

- TS2 (duplicated stale-mtime fixture — `consistent(test_abstractions)` ∧
  economy). The launch-instant stale fixture
  `(.setLastModified port-file (- (System/currentTimeMillis) 60000))` is
  open-coded **six times** across `started_test` (×4), `config_test`, and
  `attach_test`, always with the same magic `60000` offset that encodes "well
  before the launch floor". The intent (age a port file so it is stale vs the
  mtime gate) is an abstraction worth naming. Add a `test_support.clj` helper
  (e.g. `(spit-stale-port! dir port)` or `(age-file-back! file ms)` defaulting to
  the 60000 offset) and call it from all six sites, so the staleness convention
  is single-sourced and a reader sees intent rather than a bare arithmetic
  `setLastModified`. Shape-only; behaviour-preserving.

- TS3 (`robust`/`deterministic` — wall-clock-coupled "fresh accept" assertion;
  judgement, lower priority). `wait-for-started-endpoint-stale-port-gate-test`'s
  "accepts a fresh .nrepl-port" case captures `launched-at (now)` then `spit`s
  the port **without** `setLastModified`, relying on the real filesystem mtime
  landing `≥ (launched-at floored to whole seconds)`. The whole-second floor
  (AMB4) makes this safe in practice (the spit happens microseconds after the
  capture, same second), so it is not flaky today — but the freshness is implicit
  in wall-clock timing rather than asserted by construction. The symmetric reject
  cases explicitly `setLastModified` to force the relation; the accept case could
  do the same (`setLastModified` to `(+ launched-floor 1000)` or to "now") to
  make the mtime≥floor relation explicit and remove the residual same-second
  wall-clock dependency. Optional hardening — record as a judgement item, not a
  correctness defect; consider alongside TS2's helper (an `age-file!` helper that
  can also set a *fresh* mtime would serve both).

All three are shape-only and behaviour-preserving (no production change, no new
behaviour assertion); they reduce incidental duplication and one residual timing
coupling in the otherwise-complete test set.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-shaper follow-up execution — TS1/TS2/TS3 (ψ, 2026-06-03)

Executed all three newly-added test-shaper follow-ups (TS1, TS2, TS3) from the
preceding test-shaper review pass. All shape-only and behaviour-preserving — no
production change, no new behaviour assertion; they remove incidental
duplication and one residual timing coupling.

- TS1 (single-source the `Process` proxy). Lifted the parameterised 16-method
  `java.lang.Process` proxy into `test_support/fake-process`
  (`{:alive? :exit-code :pid :destroyed*}`, docstring enumerating the seam).
  `started_test` now `:refer`s it and deleted its local `fake-process`;
  `ops_test` deleted `live-fake-process` (the strict alive/exit-0/pid-4321
  special case) and calls `(fake-process {:alive? true :exit-code 0 :pid 4321})`.
  The `Process` seam shape is now single-sourced and the two files consistent.

- TS2 (name the stale-port fixture). Added `test_support/age-file-back!`
  (set mtime `offset-ms` — private `stale-port-offset-ms` 60000 default — before
  now) and `spit-stale-port!` (write `<dir>/.nrepl-port` + age). Replaced all
  six open-coded `(.setLastModified port-file (- (System/currentTimeMillis)
  60000))` sites: `started_test` ×4 (two reject cases via `spit-stale-port!`,
  dropping the now-redundant `port-file` let binding; the pre-launch-removal
  seed + PA4 launcher via the helpers), `attach_test` + `config_test` via
  `age-file-back!` (kept the explicit `spit` so the asserted port value — 7999 /
  7888 — stays visible at the call site). The staleness convention is now
  single-sourced; no bare arithmetic `setLastModified` remains.

- TS3 (explicit fresh-accept by construction). Added `test_support/touch-fresh!`
  (set mtime `+1000` ms after now — the TS2-companion fresh setter) and called
  it in `wait-for-started-endpoint-stale-port-gate-test`'s "accepts a fresh
  .nrepl-port" case after `spit`, so the mtime≥floor accept relation is asserted
  by construction (symmetric to the reject cases' aging) rather than relying on
  the same-second wall-clock landing. Removes the residual timing coupling; the
  whole-second floor (AMB4) already kept it non-flaky.

Verified: clj-paren-repair Success (all 5 changed test files, no changes
needed); clj-kondo 0/0 over `components/project-nrepl/test`;
`started/ops/config/attach` 17 tests/113 assertions green (unchanged — pure
shape refactor); remaining project-nrepl unit suite (client/eval/commands/
runtime) 13 tests/73 assertions green; consuming
`agent-session/project-nrepl-extension-install-test` 1 test/5 assertions green.
File lengths: `test_support.clj` 162, `started_test.clj` 251, `ops_test.clj`
136, `attach_test.clj` 77, `config_test.clj` 276 (all < 800). No
design/plan/doc/CHANGELOG change (shape-only; no user-visible surface change).

PASS_STATUS: REVIEW_COMPLETE

## Test review — test-shaper pass 2 (ψ)

Fresh `test-shaper` read of the project-nrepl test suite (started/ops/config/
attach + test_support). Pass 1 (TS1–TS3) addressed fixture/ceremony economy; this
pass focuses on `behavior_focused` / `meaningful_failures` / `single_concern`.
Suite green (17 tests/113 assertions). Two new actionable items.

- TS4 (under-asserted `:started-at` launch-instant contract). The PA2 contract
  is that the runtime-handle `:started-at` is the *launch* instant captured
  pre-wait (a single launch-instant source, INC1), explicitly *not* the
  post-wait/connect instant — the post-wait `:started-at (now)` write was
  removed. But `started_test`'s "records the effective :readiness-timeout-ms and
  launch-instant :started-at" case asserts only
  `(instance? java.time.Instant (get-in instance [:runtime-handle :started-at]))`
  — a bare type check. A regression reverting PA2 (re-adding `:started-at (now)`
  on the post-wait success path) still yields *an* Instant and passes green, so
  the named launch-instant provenance is untested. Strengthen it to assert the
  *provenance*: have the launcher seam record the instant at which it is invoked
  (the true launch site) and assert the instance's `:started-at` equals (or is
  `≤`) that launcher-observed instant — and/or bracket the call with
  `before`/`after` wall-clock and assert `:started-at` falls within and precedes
  the connect completion. Pins "started-at = launch instant, not connect
  instant" so a PA2 regression fails.

- TS5 (single-concern split — judgement/optional). The same case bundles two
  distinct contracts (effective `:readiness-timeout-ms` recording + `:started-at`
  launch-instant provenance) under one `testing` block. With TS4 strengthening
  the `:started-at` assertion, split the launch-instant provenance into its own
  named `testing` so a failure names which contract broke (`single_concern` ∧
  `meaningful_failures`). Low severity; fold into TS4 if convenient.

### Test-shaper pass-2 follow-up execution (TS4 + TS5)

PASS_STATUS: REVIEW_COMPLETE.

Sole newly-added actionable items (TS4 + TS5, added by test-shaper review
`bafed7ecd`); all prior TR/TS1–3/IR/PA/PSI items already checked. Test-only,
shape-and-assertion-only — no production / doc / CHANGELOG change; suite
behaviour preserved.

- **TS5** (split): split the bundled `started_test` case "records the effective
  :readiness-timeout-ms and launch-instant :started-at" into two named
  `testing` blocks — "records the effective :readiness-timeout-ms" (the
  `:readiness-timeout-ms 90000` assertion) and "records :started-at = launch
  instant, not connect instant (TS4/PA2)" (the strengthened provenance check).
  A failure now names which contract broke.

- **TS4** (provenance, folded into the new split block): replaced the bare
  `(instance? java.time.Instant …)` type check with a launch-instant provenance
  assertion. The launcher seam now captures the instant it is invoked into a
  `launcher-at` atom (the true launch site — the launcher runs *after*
  `launched-at (now)` is captured pre-wait, and *before* the gate poll +
  connect). Assertions: `:started-at` is an `Instant`, is **not after**
  `@launcher-at` (`(not (.isAfter started-at @launcher-at))`), and is **not
  before** a pre-call `before` wall-clock. A PA2 regression re-adding the
  removed post-wait connect-time `:started-at (now)` write would record an
  instant strictly after the launcher fired (poll + connect happen after
  launch), so `started-at` would exceed `@launcher-at` and the `.isAfter` guard
  fails green. The kept `Instant` type check + lower `before` bound keep the
  assertion well-formed.

started-test 3 tests / **33 assertions** green (+8 over pass-1's 25);
ops/config/attach unchanged (4/23, 8/41, 2/18); clj-kondo 0/0; clj-paren-repair
Success; file 284 < 800; `bb commit-check:file-lengths` exit 0.

🔁 PATTERN: a bare `(instance? Instant x)` type check cannot pin *which* instant
was recorded — a PA2-style regression that swaps a launch-instant source for a
connect-instant source still yields *an* Instant and passes green. Pin
provenance by having the seam (here the launcher) capture its own invocation
instant and asserting an ordering relation (`recorded ≤ seam-observed`) that
only the correct source satisfies. Bundling two contracts (timeout recording +
:started-at provenance) under one `testing` block also hides which broke — the
TS5 split makes failures self-naming.

## Test review — test-shaper pass 3 (ψ)

Fresh `test-shaper` read of the project-nrepl test suite (started/ops/config/
attach + test_support). Passes 1–2 single-sourced the `Process` proxy (TS1), the
stale/fresh mtime fixtures (TS2/TS3), strengthened `:started-at` provenance
(TS4), and split the bundled case (TS5). Suite green
(started/ops/config/attach 17 tests/115 assertions). One new actionable item.

- TS6 (happy-launch arrange duplication — `minimal_incidental_setup` ∧
  `consistent(fixtures)` ∧ `helpers_that_compress(ceremony)`). Four
  `start-instance-in-test` cases ("launches command…", "no :timeout-ms opts…
  120000 default (TR1)", "records the effective :readiness-timeout-ms",
  "records :started-at = launch instant (TS4/PA2)") each open-code the identical
  happy-start arrange: a `launcher` that `(spit (io/file worktree ".nrepl-port")
  "7777\n")` and returns `(fake-process {:alive? true :exit-code 0 :pid 4321})`,
  plus `(fake-connector "nrepl-session-1")` and the `make-ctx`/`temp-dir`/
  try-finally `delete-tree!` ceremony. The same launcher shape recurs in
  `ops_test`'s `start-config-timeout-threading-test`. The repeated 4-line
  launcher/connector arrange is incidental to each case's actual concern
  (default-timeout vs configured-timeout vs launch-instant provenance) and the
  duplicated `"7777"`/`:pid 4321` literals are a `consistent(fixtures)` drift
  risk (a port/pid change must be hand-propagated across ≥5 sites). Add a
  `test_support` helper that single-sources the happy started-launcher seam —
  e.g. `started-launcher!` (a launcher fn of `worktree` that writes a given
  fresh `.nrepl-port` and returns a happy `fake-process`, default port `7777`
  pid `4321`), so each case calls the helper and asserts only its distinct
  contract. The TS4 provenance case must keep its `launcher-at` capture, so the
  helper should compose with (not hide) an injected pre-write hook or return the
  launcher for the caller to wrap — `helpers_that_compress(ceremony) ∧
  ¬helpers_that_hide(intent)`. Shape-only; behaviour-preserving (no assertion
  change). Re-run started/ops/config/attach + clj-kondo after.

## Test-shaper pass 3 follow-up — TS6 (ψ)

TS6 (sole newly-added actionable item; commit `54266be36`) executed.
Single-sourced the duplicated happy started-launcher arrange into a new
`test_support/started-launcher!` (default port `7777` pid `4321`): a launcher fn
of `[worktree command]` that synchronously writes a fresh `.nrepl-port` and
returns a happy `fake-process`. It composes — rather than hides — the launch
site via an optional `:on-launch` 0-arg pre-write hook, so the TS4 provenance
case still captures its `launcher-at` instant without re-open-coding the
launcher (`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`).

Rewired the four `start-instance-in-test` happy cases ("launches command…", TR1
no-opts 120000 default, "records the effective :readiness-timeout-ms", TS4
"records :started-at = launch instant") and `ops_test`'s
`start-config-timeout-threading-test` onto the helper. Removed the duplicated
`(spit … ".nrepl-port" "7777\n")` + `(fake-process {:alive? true :exit-code 0
:pid 4321})` launcher at ≥5 sites and the now-unused `fake-process`
binding/`:refer` in `ops_test`. The stale/exit/plain-deadline cases keep their
bespoke (non-happy / stale-port) launchers — the helper only single-sources the
*happy* arrange.

**Ordering discovery:** `started-launcher!` calls `fake-process`, so clj-kondo
flagged an unresolved-symbol forward reference when the helper was placed before
`fake-process`'s `defn`. Moved `started-launcher!` to the end of `test_support`,
after `fake-process`.

Shape-only / behaviour-preserving — test files + task artifacts only; no
production/doc/CHANGELOG change. started/ops/config/attach 17 tests / 115
assertions green (count unchanged); clj-kondo 0/0; clj-paren-repair Success;
files 185/273/134 (< 800); `bb commit-check:file-lengths` exit 0.

🔁 PATTERN: a test-shaper pass after the suite has accreted ≥5 near-identical
happy-arrange blocks (the same launcher writing `"7777"` + `fake-process pid
4321`) single-sources them into one named helper; the helper must *compose* with
the one case that needs a launch-site hook (TS4's `launcher-at` capture) via an
injected `:on-launch` callback rather than hiding it — and a helper that
references another `defn` (`fake-process`) must be ordered *after* it or
clj-kondo flags an unresolved-symbol forward reference.

PASS_STATUS REVIEW_COMPLETE.

## Test review — test-shaper pass 4 (ψ)

Fresh `test-shaper` read of the whole project-nrepl test suite (started/ops/
config/attach/test_support) after passes 1–3. Passes 1–3 single-sourced the
`Process` proxy (TS1), the stale/fresh mtime fixtures (TS2/TS3), strengthened
`:started-at` provenance (TS4/TS5), and single-sourced the happy started-launcher
arrange (TS6). The seam helpers, fixtures, and assertions are now consistent and
behaviour-focused. One new actionable item remains.

- TS7 (temp-dir lifecycle ceremony — `minimal_incidental_setup` ∧
  `helpers_that_compress(ceremony)` ∧ `consistent(structure)`). The
  `(temp-dir …)` + `(try … (finally (delete-tree! dir)))` cleanup ceremony recurs
  ~37 times across the suite (started ×13, config ×12, ops ×4, attach ×3,
  commands ×2): every case that needs a real temp directory open-codes the same
  acquire/try/finally/delete frame, which is incidental to each case's actual
  concern (readiness gate, config resolution, ops threading…) and structurally
  identical at every site. No `with-temp-dir` helper exists. Add a
  `test_support` `with-temp-dir` macro (bind a freshly-created temp dir to a
  caller-named symbol over a body, guaranteeing `delete-tree!` in a `finally`),
  e.g. `(with-temp-dir [dir "psi-project-nrepl-started-"] …)`, and rewire the
  ~37 try/finally sites onto it. This compresses the dominant remaining
  incidental ceremony without *hiding* intent — the directory binding stays
  visible at the call site; only the acquire/cleanup frame is removed
  (`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`). Multi-dir
  cases (config_test's `home`+`worktree`) can nest the macro or it can accept
  multiple bindings. Shape-only; behaviour-preserving (no assertion change).
  Re-run started/ops/config/attach/commands + clj-kondo after.

Considered-and-rejected (no step added): the two file-private
`write-project-config!` helpers (ops_test wraps `{:agent-session {:project-nrepl
…}}`; config_test takes the already-wrapped `content`) share a name but are
*different* abstractions by design — config_test exercises the full nesting/merge
precedence, ops_test only the project-nrepl slice. Consolidating would force one
shape onto both and hide that intent; the local divergence is the clearer choice
(`¬helpers_that_hide(intent)`). No action.

## Test-shaper follow-up execution — TS7 (ψ, 2026-06-03)

Executed the single newly-added test-shaper pass-4 follow-up (TS7) — the only
unchecked item (TS1–TS6/TR1–TR5/IR1/PA1–PA4/PSI1 all already checked).
Shape-only/behaviour-preserving: test files + task artifacts + a clj-kondo
`:lint-as` config entry; no production/doc/CHANGELOG change; no assertion change.

- Added `test_support/with-temp-dir`, a `let`-style macro taking a `[sym prefix …]`
  bindings vector (each `prefix` a `temp-dir` prefix string). It creates all
  named temp directories *before* the body, then guarantees `delete-tree!` of
  each in a `finally` (reverse binding order). Single-sources the
  `(temp-dir …)` + `(try … (finally (delete-tree! dir)))` acquire/cleanup frame
  that recurred across the suite.

- Rewired every temp-dir lifecycle frame onto the macro: `started_test` ×13,
  `config_test` ×7 single-dir + the two `home`+`worktree` `resolve-config` cases
  via one multi-binding `(with-temp-dir [home … worktree …] …)`, `ops_test` ×3,
  `attach_test` ×2, `commands_test` ×1. Cases carrying extra non-temp `let`
  bindings (process/launcher/connector/ctx; config_test's `shared-f`/`local-f`
  derived from `dir`; commands_test's `[ctx session-id]` derived from the temp
  worktree) keep those in an inner `let` in the body, so only the cleanup
  ceremony is removed and the directory binding stays visible at each call site
  (`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`). Dropped the
  now-unused `delete-tree!`/`temp-dir` `:refer`s from all five test namespaces
  (both remain public in `test_support`: `with-temp-dir` uses them and
  `delete-tree!` is still referenced by component code).

- Taught clj-kondo the macro's binding semantics via a `:lint-as
  clojure.core/let` entry in `.clj-kondo/config.edn`
  (`psi.project-nrepl.test-support/with-temp-dir`) — the `let`-style `sym prefix`
  pairs are valid `let` `binding init` forms (the prefix is a string literal
  init). Without it, the bound symbols (`dir`/`worktree`/`home`) read as
  unresolved. Folded the TS3 accept-case `launched-at` capture into its outer
  `let` to avoid a lint-as-induced redundant-nested-let warning (`launched-at`
  is still captured before any port write — the binding inits have no side
  effects, so the capture ordering is preserved).

- 🔁 PATTERN: a `let`-style lifecycle macro (`with-temp-dir`) whose binding
  vector is `sym prefix` pairs lints cleanly with `:lint-as clojure.core/let`
  *only because* the second element is a value (string), not a symbol — clj-kondo
  treats each pair as `binding init` and the string init resolves fine. The
  lint-as expansion also makes a sole nested `(let …)` directly inside the
  macro body read as a redundant let; merge such a child binding into the macro's
  body `let` when the child's inits are side-effect-free.

Verified: clj-paren-repair Success (all five test files + test_support);
clj-kondo 0 errors/0 warnings over `components/project-nrepl/test`;
`clojure -M:test --focus unit` green; `bb commit-check:file-lengths` exit 0.
Every rewired file shrank (started 273→234, config 276→247, ops 134→125, attach
77→71, commands 79→76; test_support 185→209 from the new macro+docstring). No
design/plan/doc/CHANGELOG change (shape-only; no user-visible surface change).

PASS_STATUS: REVIEW_COMPLETE

## Test-shaper review (pass 5)

Read: `started_test.clj`, `ops_test.clj`, `config_test.clj`, `attach_test.clj`,
`commands_test.clj`, `test_support.clj`. Applied `λtests. clarity ∧ signal ∧
robustness → shape`.

Passes TS1–TS7 are landed and effective: the suite is single-concern, no-mock,
behaviour-focused, deterministic (mtime fixtures explicit by construction), and
the `Process` proxy / stale-fresh mtime / happy launcher / temp-dir ceremony are
single-sourced. `clojure -M:test --focus unit` exit 0.

Two new actionable `consistent(fixtures)` / `minimal_incidental_setup` shape
findings remain, both untouched by prior passes (grep-confirmed absent from
steps/implementation):

- **TS8 — divergent `write-project-config!` across files.** `ops_test` and
  `config_test` each define a private `write-project-config!` with **different
  semantics**: `ops_test`'s wraps its arg in `{:agent-session {:project-nrepl …}}`
  (takes the inner map), `config_test`'s spits the arg verbatim (caller wraps).
  `config_test` also has a third sibling `write-user-config!`. Two same-named
  private helpers with different wrapping is exactly the `consistent(fixtures)`
  drift `test_support` exists to prevent — a reader moving between files mis-reads
  the contract. The `test_support` docstring already claims to single-source the
  shared config-write shape, yet these on-disk config writers are not there.

- **TS9 — `read-project-preferences-test` open-coded shared/local pair (×3).**
  Its three cases each open-code the same `shared-f`/`local-f` + `.mkdirs` +
  `spit` arrange for the `.psi/project.edn` (shared) + `.psi/project.local.edn`
  (local) file pair — `minimal_incidental_setup` repetition with no helper, even
  though single-file writers live in the same file. A pair-writer helper would
  compress the ceremony so each case asserts only its distinct merge/fallback
  contract.

Both are shape-only / behaviour-preserving (no assertion change).

## Test-shaper review (pass 5) follow-up execution — TS8/TS9

Executed the two pass-5 follow-ups (the only newly-added unchecked items;
TS1–TS7 + all TR/IR/PA/PSI items already done). Shape-only / behaviour-
preserving — test files + task artifacts only; no production/doc/CHANGELOG;
suite behaviour and assertions unchanged.

**TS8 — single-source the divergent config-file writers.** Lifted three writers
into `test_support`: `write-user-config!` (`~/.psi/agent/config.edn`),
`write-project-config!` (`<worktree>/.psi/project.edn`), and new
`write-local-config!` (`<worktree>/.psi/project.local.edn`). **Decision — one
wrapping convention: caller passes the full on-disk map verbatim**; the helper
only writes EDN to the canonical path. Chosen because it keeps the asserted
on-disk shape (`{:agent-session {:project-nrepl …}}`) visible at every call site
(`¬helpers_that_hide(intent)`) and matches `config_test`'s prior verbatim
semantics — so only `ops_test`'s call site changed (it previously passed the
inner `:project-nrepl` map and let its private writer wrap; it now passes the
full map). `config_test` deleted its two private writers, `ops_test` deleted its
one (and its now-unused `clojure.java.io` require); both `:refer` the shared set.

**TS9 — compress the `read-project-preferences-test` shared/local arrange.**
Rewired all three cases onto the TS8 single-file writers, removing the open-coded
`shared-f`/`local-f` `let` + `.mkdirs` + `spit` frame. **Decision — reused the
TS8 single-file writers, not a new `write-project-prefs!` pair-writer**, because
the two malformed cases write ONE valid file via the writer and the OTHER as raw
invalid EDN (`spit "not valid edn"` to the canonical path); a pair-writer taking
two valid maps could not express the malformed half. The writer's `.mkdirs` also
creates the `.psi` dir for the sibling malformed `spit`.

Verified: clj-paren-repair Success (3 files); clj-kondo 0 errors/0 warnings over
`components/project-nrepl/test`; focused green (config 8/41, ops 4/23, started
3/33, attach 2/18); full `clojure -M:test --focus unit` exit 0;
`bb commit-check:file-lengths` exit 0 (config 230, ops 119, test_support 242 —
all < 800; config/ops shrank, test_support grew by the three writers).

🔁 PATTERN: two same-named private helpers in sibling test files with DIFFERENT
wrapping semantics (`ops_test` wraps the inner map; `config_test` spits verbatim)
is the exact `consistent(fixtures)` drift `test_support` exists to prevent —
unify on the *verbatim full-map* convention so the asserted on-disk shape stays
at the call site, then only the wrapping site changes.
🔁 PATTERN: a pair-writer helper that takes two *valid* maps cannot serve the
malformed-fallback cases (which need one valid file + one raw-invalid-EDN spit);
prefer per-file writers whose `.mkdirs` still provisions the shared dir for the
sibling raw spit.

PASS_STATUS: REVIEW_COMPLETE

## Test-shaper review (pass 6)

Read all eight project-nrepl test files + `test_support.clj`. Applied
`λtests. clarity ∧ signal ∧ robustness → shape`.

Passes TS1–TS9 are landed: single-concern, no-mock, behaviour-focused,
deterministic (explicit-by-construction mtime fixtures), and the `Process`
proxy / stale-fresh mtime / happy launcher / temp-dir / config-writer ceremony
are single-sourced. `clojure -M:test --focus unit` exit 0.

One actionable `behavior_focused` / `meaningful_failures` coverage-of-contract
gap remains, grep-confirmed absent from steps/implementation/tests:

- **TS10 — the stale-port diagnostic *instants* (the A2 observability payload)
  are never asserted.** The A2 acceptance criterion is that the stale-port
  rejection's `ex-data` "carries the rejected/launch instants" so the diagnostic
  is **observable from the instance** via `:last-error → :data`. The production
  `:started-stale-port` ex-data (both the deadline branch and the IR1 exit
  branch) carries `:port-mtime-ms` / `:min-mtime-ms` / `:launched-at` — the
  rejected-vs-floor evidence that makes the rejection diagnosable. But **no test
  asserts any of these three keys are present** on any path: the
  `wait-for-started-endpoint!` reject case asserts only `:phase` + message; the
  IR1 exit case asserts `:phase` + `:command-exited?` + message; and crucially
  the **PA4 `status`-read test** — the one that proves A2's "observable from the
  instance" claim — asserts `[:instance :last-error :data :phase]` only, never
  that the instants survive into `:last-error → :data`. A regression dropping the
  rejected/launch instants from the diagnostic ex-data (gutting A2's
  observability rationale) would pass every current test. Tighten the PA4
  `status`-read test (the contract's owning surface) to also assert
  `:port-mtime-ms` / `:min-mtime-ms` / `:launched-at` are present (and
  `min-mtime-ms ≥ port-mtime-ms` / non-nil) under `[:instance :last-error :data]`,
  and/or add the instant-presence assertions to the
  `wait-for-started-endpoint!` reject + IR1 cases. Coverage-only;
  behaviour-preserving (production already carries the keys).

Secondary (judgement, optional — noted, not blocking):

- TS11: the three `wait-for-started-endpoint!` exception cases (TR5 deadline,
  stale-port reject, IR1 exit) open-code the same `(try … (catch
  clojure.lang.ExceptionInfo e e))` + repeated `(:phase (ex-data ex))` /
  `(.getMessage ex)` inspection frame. A `thrown-ex-data` helper would compress
  the catch-and-inspect ceremony (`consistent(assertion_style)` ∧
  `minimal_incidental_setup`). Shape-only.

## TS10 — assert stale-port diagnostic instants (A2 observability)

Test-shaper pass-6 follow-up. The `:started-stale-port` ex-data carries
`:port-mtime-ms`/`:min-mtime-ms`/`:launched-at` (deadline + IR1 exit branch in
`started.clj`), but no test asserted those keys — A2's "observable from the
instance" rationale was uncovered. Tightened three assertion sites
(coverage-only, no production change):

- PA4 `status`-read test (owning surface): `[:instance :last-error :data]` now
  asserts `:port-mtime-ms`/`:min-mtime-ms` non-nil, `:launched-at` is an
  `Instant`, and `:min-mtime-ms ≥ :port-mtime-ms`, alongside `:phase`.
- `wait-for-started-endpoint!` deadline reject case + IR1 exit-with-stale case:
  each now asserts the same instant trio, with `:launched-at` = the injected
  launch instant.

started-test 3/45 (was 3/25), ops-test 4/23, clj-kondo 0/0, paren-repair OK.

## Implementation review — quality (ψ, 2026-06-03, pass 3)

Skill: task-implementation-review (`review(code) ∧ matches(design) ∧
follows(architecture) ∧ flag(new_pattern ∨ unnecessary_abstraction ∨
structural_performance)`). Independent re-read of `started.clj`, `config.clj`,
`ops.clj`, `doc/project-nrepl.md`, `CHANGELOG.md` after the TS1–TS10 shape
passes. State: clean tree; clj-kondo 0/0 (`components/project-nrepl/{src,test}`);
`started-test` 3/45 green; docs + CHANGELOG present.

Confirms prior pass-2 conclusions: design match (config key + `[1000 600000]`
validation, raised 120000 default, pre-launch removal + `floored-to-whole-seconds`
mtime gate, `:phase :started-stale-port` on both deadline and IR1 exit paths,
`:readiness-timeout-ms` projection via AMB3, launch-site `:started-at`/INC1),
architecture fit (gate confined to `started.clj`; shared `read-dot-nrepl-port` +
attach untouched), no unjustified new pattern or abstraction.

New actionable issue (not previously flagged; amplified by this task):

- IR2 (launched child process leaked on the readiness-failure path). In
  `start-instance-in!` the launched `process` is bound in the inner `let` and
  only recorded onto the runtime-handle (`:process`) *after*
  `wait-for-started-endpoint!` returns. When the wait **throws** (timeout or
  stale-port) for a process that is *alive but never wrote a usable
  `.nrepl-port`* (a hung/slow boot — the headline scenario), the `catch
  Throwable` records `:last-error` and rethrows but (a) never `.destroy`s the
  launched `process` and (b) never stores it on the runtime-handle, so the
  later `stop-started-instance-in!` (which reads `:process` from the
  runtime-handle) cannot reap it either. The child JVM is orphaned. The leak is
  **pre-existing** (the process was bound-then-stored-after-success before this
  task), but this task **worsens its blast radius**: raising the default timeout
  `5000 → 120000` means a hung command now keeps an orphaned JVM alive for up to
  120 s before the timeout fires and leaks it (vs 5 s prior). The
  `process-exited?` short-circuit only covers a process that *exits* on its own;
  an alive-but-port-less hang is exactly the un-covered case. Consider: bind
  `process` in an outer `let`/atom visible to the `catch` and `.destroy` it (and
  delete the freshly-removed `.nrepl-port` is already handled) on the failure
  path, or record `:process` on the runtime-handle pre-wait (mirroring the
  pre-wait `:readiness-timeout-ms`/`:started-at` launch-site update) so
  `stop-started-instance-in!`/`catch` can reap it. Not a correctness/wrong-
  endpoint bug (pre-launch removal still prevents wrong-endpoint connect), but a
  resource-leak regression-in-impact this task's timeout raise directly enlarges.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Implementation review — quality (ψ, 2026-06-04, pass 3 follow-up IR2)

Executed the sole newly-added actionable item from impl-review pass 3 (commit
`66383ab59`) — IR2 (launched child process leaked on the readiness-failure path).
All prior items (5 slices + PA1–4 + PSI1 + IR1 + TR1–5 + TS1–10) already checked.

**Production (`started.clj`, `start-instance-in!`):**
- Hold the launched process in an outer-scope `launched-process (volatile! nil)`
  bound in the top-level `let` (merged there rather than a nested `let` to avoid a
  clj-kondo redundant-let warning), so the `catch` can reach it.
- Record `:process`/`:pid` onto the runtime-handle **pre-wait** via a third
  launch-site `update-instance-in!` (immediately after `(launcher …)`), and
  `vreset!` the volatile — moved off the post-wait success update so
  `stop-started-instance-in!` (reads runtime-handle `:process`) can also reap a
  hung process. The post-wait success update now only adds `:launch-id`.
- `catch Throwable`: when `@launched-process` is non-nil and `(.isAlive process)`,
  `.destroy` it before recording `:last-error` + rethrowing. `.destroy` is a no-op
  on an already-exited process, so the `process-exited?` self-exit short-circuit
  path (which never enters the catch with an alive process) is unaffected.

**Decision — pre-wait runtime-handle record (vs catch-only outer bind):** the
design offered two options. Chose **both**: outer-scope volatile (so the `catch`
reaps immediately) AND pre-wait runtime-handle `:process` record (so a later
`stop-started-instance-in!` can reap a process that somehow survives the catch).
This mirrors the existing pre-wait `:readiness-timeout-ms`/`:started-at`
launch-site update — the runtime-handle is the single durable place the process
must live for the stop path, and the volatile is the in-flight handle the catch
reaches without re-reading the instance.

**Test (no-mocks, `started_test`):** "reaps the alive launched process on the
readiness-failure path (IR2)" — alive `fake-process` with a `:destroyed*` atom,
launcher writes no `.nrepl-port`, `:timeout-ms 100`; asserts the readiness
timeout throws (`:phase :started-readiness`) AND `@destroyed*` is `true`. The
`fake-process` proxy's `destroy` already sets `:destroyed*` (TS-era helper), so
no test-support change. A regression dropping the reap passes `:phase`-only and
fails the destroy assertion.

**Docs:** CHANGELOG `Fixed` entry (no orphaned JVMs on failed starts; window
widened by the 120 s raise) + `doc/project-nrepl.md` readiness-failure note.

**Verify:** started 3/47 green (+2 over 45); ops/config/attach/commands 19/148
green (unchanged); consuming `project-nrepl-extension-install-test` 1/5 green
(the `with-redefs` stub is unaffected by the internal change); `--focus unit`
exit 0; clj-kondo 0/0 (`components/project-nrepl/{src,test}`); clj-paren-repair
Success; `bb commit-check:file-lengths` exit 0.

🔁 PATTERN: a resource bound in an inner `let` and only stored on the durable
handle *after* the success call is unreachable from the `catch` of an enclosing
`try` — to reap it on the failure path, bind it in a scope the `catch` can reach
(outer volatile) AND record it on the durable handle *before* the throwing call
(pre-wait), mirroring how the pre-wait status-field update was already structured
so the diagnostic survives the failure path.

PASS_STATUS: REVIEW_COMPLETE

## Implementation review — quality (ψ, 2026-06-04, pass 4)

Skill: task-implementation-review (`review(code) ∧ matches(design) ∧
follows(architecture) ∧ flag(new_pattern ∨ unnecessary_abstraction ∨
structural_performance)`). Independent re-read of `started.clj`, `config.clj`,
`ops.clj`, `client.clj`, `runtime.clj`, `doc/project-nrepl.md`, `CHANGELOG.md`,
and the project-nrepl test suite after the IR2 follow-up landed. State: clean
tree; `clojure -M:test --focus unit` exit 0; clj-kondo 0/0
(`components/project-nrepl/{src,test}`); steps.md 47/47 checked.

Confirms all prior conclusions — no regressions, no newly-surfaced gaps:
- Design match: config key + `[1000 600000]` validation (`resolved-attach-endpoint`
  idiom), raised 120000 default, pre-launch removal + `floored-to-whole-seconds`
  mtime gate, `:phase :started-stale-port` on both deadline and IR1 exit paths,
  `:readiness-timeout-ms` projection (AMB3), launch-site `:started-at`/INC1, IR2
  failure-path reap.
- Architecture fit: stale-port gate confined to `started.clj` (A1); shared
  `read-dot-nrepl-port` + `attach/resolve-attach-endpoint` untouched; runtime-
  handle ownership of subprocess + `.nrepl-port` I/O respected (no move under
  dispatch effects); `ops/start` keeps projecting from `instance-payload` (no
  op-only status channel, A2).
- No unjustified new pattern, no unnecessary abstraction.

Reviewed-and-not-actionable observations (recorded for completeness):
- `start-instance-in!` now performs three sequential `update-instance-in!` swaps
  on the success path (pre-wait launch-site status; post-launch `:process`/`:pid`
  record; post-wait `:launch-id`/`:endpoint`). Each has a distinct survival
  purpose the design mandates (status/`:started-at` and `:process` must be
  recorded *before* the throwing wait so the failure-path `catch` and a later
  `stop-started-instance-in!` can observe/reap them). The registry is a single
  in-memory handle and `start` is an infrequent op, so the extra functional
  swaps are not a structural performance concern. Within design tolerance.
- The IR2 failure path leaves the `:failed` instance with a (now-destroyed)
  `:process` on its runtime-handle and does not `remove-instance-in!` it. A
  subsequent `stop` would `.destroy` an already-dead process (guarded by
  `.isAlive`, no-op) — benign. Failed-instance cleanup/removal is pre-existing
  behaviour and out of this task's scope.
- The freshness gate remains intentionally fail-open on a TOCTOU stat miss
  (`fresh?` short-circuits when `(nil? mtime-ms)`), consistent with the design's
  "pre-launch removal guarantees correctness even when the gate is lenient"
  (Q2/A1 defence-in-depth). Already recorded in pass 2; unchanged.

No new actionable issue. Implementation is correct, matches design + architecture,
and is fully covered by no-mock state/return tests. All prior review threads
(IR1, IR2, TR1–5, TS1–10, PA1–4, PSI1) are resolved and checked.

PASS_STATUS: REVIEW_COMPLETE

## Test review — coverage (ψ, 2026-06-04, task-test-review)

Skill: task-test-review (`well_formed(tests) ∧ ∀b∈behaviour(design).∃t.covers(t,b)
∧ ∀d∈infra_deps. injectable ∧ nullable ∧ ¬mock ∧ ¬stub`). Independent re-read of
`config.clj`, `ops.clj`, `started.clj` and the `config/ops/started_test` suites.
State: `--focus project-nrepl.{started,ops,config}-test` 15 tests/111 assertions
green; no `with-redefs`/`reify`/`mock`/`stub` anywhere (verified by grep) — all
infra deps are injected real seams (`fake-process` `Process` proxy, fn
`:process-launcher`/`:nrepl-connector`, file-backed `.nrepl-port`, real
`update-instance-in!`/`ensure-instance-in!`). Tests are well-formed and assert
state/return values, not interactions.

**Coverage gap (one, actionable, non-duplicated): TR6.** The Q1 acceptance
criterion includes "out-of-range or non-integer values throw a `:phase :validate`
`ex-info`". That validation is unit-tested in `config_test`
(`resolved-start-readiness-timeout-ms-test`), and the *valid* config→`ops/start`
→opts→`start-instance-in!` thread is pinned by `start-config-timeout-threading-test`
(TR3, 90000). But **no test drives an out-of-range/non-integer configured
`:start-readiness-timeout-ms` through `ops/start`** to assert the `:phase
:validate` throw surfaces at the user-facing op boundary. `ops/start` calls
`(resolved-start-readiness-timeout-ms cfg)` *unguarded* (no try/catch) before
launch, so a regression wrapping/swallowing the validation throw, mis-reading the
config key, or silently coercing an invalid value would pass every current test
(config_test exercises the fn directly; threading test only uses a valid value;
`start-test` only hits the missing-start-command path). This is the negative-path
sibling of TR3 at the same boundary — the user-misconfiguration surface where the
validation actually fires (e.g. a user sets `:start-readiness-timeout-ms 999`).

All other behaviours covered: raised 120000 default (TR1), config→ops valid
thread (TR3), deadline `:started-readiness` (TR5), stale-port `:started-stale-port`
deadline + IR1 exit + A2 instant payload (TR5/TS10), pre-launch removal +
fresh-accept, gate reject/accept, `:readiness-timeout-ms` payload projection
(TR2/AMB3), `:started-at` launch-instant provenance (TS4), IR2 process reap,
A1 attach/shared-discovery stale-acceptance (TR4), startup-failure projection.
No mocks/stubs; infra deps fully nullable.

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## TR6 follow-up — invalid configured timeout `:phase :validate` through `ops/start` (2026-06-04)

Sole newly-added actionable item (added by test-review pass `cc71e3e2b`; all
prior TR1–TR5/IR1/IR2/PA1–PA4/PSI1/TS1–TS10 already checked).

Added `ops_test.clj` `start-invalid-config-timeout-test` — the negative-path
sibling of `start-config-timeout-threading-test` (TR3) at the same `ops/start`
boundary. `ops/start` calls `(resolved-start-readiness-timeout-ms cfg)`
**unguarded** (no try/catch) before launch, so an out-of-range / non-integer
configured `:start-readiness-timeout-ms` throws `:phase :validate` straight out
of the op. The test writes a project `.psi/project.edn` with a valid
`:start-command` plus an invalid timeout in two `testing` blocks:

- out-of-range (`999`): asserts `thrown-with-msg?` on `#"range 1000-600000"`
  **and** `(:phase (ex-data …)) = :validate`;
- non-integer (`"120000"`): asserts `(:phase (ex-data …)) = :validate`.

No launcher/connector seam is seeded — the validation throw precedes launch.
Coverage-only; production verified by inspection (no production/doc/CHANGELOG
change). A regression swallowing/wrapping the throw, mis-reading the config key,
or silently coercing the value would otherwise pass `config_test` (exercises the
fn directly), TR3 (valid value), and `start-test` (missing-start-command).

Verified: ops-test 5 tests/26 assertions green (+1 test/+3 assertions over the
prior 4/23); `clojure -M:test --focus unit` RC=0; clj-kondo 0/0;
clj-paren-repair Success; `bb commit-check:file-lengths` exit 0; ops_test 158
lines (< 800).

🔁 PATTERN: a happy-path threading test (TR3) at a config→op boundary leaves the
*negative* validation path (invalid configured value) uncovered when the op
calls the validating resolver unguarded — the throw fires at the op boundary,
not just the resolver unit (`config_test`), so a wrap/swallow/coerce regression
escapes both the unit test and the valid-value threading test. Pin both the
valid threading and the invalid-validation surfacing at the boundary that owns
the user-misconfiguration error.

PASS_STATUS: REVIEW_COMPLETE

---

## Test review (task-test-review) — pass 7

Reviewed tests against `λ review_tests`: well-formedness, behaviour coverage
of design acceptance criteria, and no-mocks infra-dep injection.

✅ Well-formed / no-mocks: confirmed. All infra seams are real injectables —
`started-launcher!`/`fake-process` (real `java.lang.Process` proxy),
`fake-connector` transport, file-backed `.nrepl-port`, real `resolve-config`
reading on-disk `.psi/project.edn`. No `with-redefs`/mock/stub/spy anywhere
(grep clean). Shared `test_support` helpers single-source ceremony without
hiding intent.

✅ Coverage already strong across acceptance criteria: raised 120000 default
(TR1), config→ops→opts threading (TR3) + invalid-validate surfacing (TR6),
`instance-payload` projection (TR2), stale-port deadline + exit-with-stale +
fresh-accept gate (gate-test), plain `:started-readiness` deadline reproduction
(TR5), A2 diagnostic instants on every `:started-stale-port` path (TS10), IR2
process reaping, A1 attach/shared-discovery stale-acceptance separation (TR4).

❌ TR7 (new actionable gap): the stale-gate **poll-continuation accept** path is
untested. The design states "A present-but-too-old port is treated as
not-yet-ready: the poll loop continues until the gate passes or the deadline
fires." Current gate tests cover stale→continue→**deadline rejection** and
fresh→**immediate accept** (first poll), but not stale→continue→**subsequent
fresh accept** — a port observed too-old on an early poll that is then replaced
by a fresh one and accepted on a later poll. The production `(if (and endpoint
fresh?) accept (do …recur))` loop makes too-old a *soft* not-yet-ready that
recurs; a regression turning a too-old port into a *hard* immediate rejection
(short-circuiting the loop) would pass every current test (deadline-rejection
and first-poll-accept both still green). This is the defence-in-depth
continuation behaviour the design names explicitly. Modest scope: one
`wait-for-started-endpoint!` unit case (injected `:launched-at`; a port written
stale then, on a later poll, replaced/touched fresh — e.g. via a launcher-less
fixture that ages the file then `touch-fresh!`s it after the first poll, or a
short poll interval with a background mtime bump) asserting the endpoint is
ultimately accepted (not rejected). Coverage-only; production already correct.

🔁 PATTERN: a soft "continue polling" branch needs both its terminal outcomes
pinned — the deadline-rejection *and* the eventual-accept-after-continuation —
or a regression collapsing the soft branch into a hard immediate reject escapes
a suite that only tests the immediate-accept and the deadline-reject endpoints.

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## Test-review follow-up execution (TR7) — 2026-06-03

✅ TR7 resolved (coverage-only). Added `wait-for-started-endpoint-stale-port-gate-test`
case "stale port observed early is accepted on a later poll once it becomes
fresh (TR7)" in `started_test.clj`. Arrange: an **alive** `fake-process` (so the
process-exited short-circuit never fires), injected `:launched-at`,
`:poll-interval-ms 10`, `:timeout-ms 2000`. Seed a too-old `.nrepl-port` via
`age-file-back!` so the first poll(s) reject it as not-yet-ready; a `future`
`touch-fresh!`es the **same** port after a 60 ms delay (≥6 poll intervals) so a
later poll passes the mtime gate. Assert the returned
`{:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}` map — the ultimate
**accept**, not a deadline rejection. The `future` is dereffed in a `finally` to
join the background thread.

🎯 Discrimination: pins production's `(if (and endpoint fresh?) accept (…recur))`
soft-continue branch. A regression turning too-old into a *hard* immediate
`:started-stale-port` short-circuit would throw on the very first poll (before
the `future` fires) and fail the accept assertion, while the existing
deadline-rejection (`:started-stale-port` on timeout) and first-poll-accept
(`touch-fresh!` pre-write) cases would both stay green — closing the
escape window the test-review flagged.

✅ Verification: `clojure -M:test --focus psi.project-nrepl.started-test` → 3
tests/48 assertions, 0 failures (+1 assertion over pass-7's 47);
`clojure -M:test --focus unit` RC=0 (full unit suite green, no regressions);
clj-kondo 0 errors/0 warnings on `started_test.clj`; clj-paren-repair Success;
`bb commit-check:file-lengths` exit 0 (`started_test.clj` 305 lines, < 800).
No production / doc / CHANGELOG change (coverage-only).

PASS_STATUS: FOLLOW_UP_COMPLETE

---

## Test review (task-test-review) — pass 8

Reviewed tests against `λ review_tests`: well-formedness, behaviour coverage of
design acceptance criteria, no-mocks infra injection. Re-verified pass-7/TR7
claims hold and the full focused suite is green (started/ops/config/attach:
18 tests, 133 assertions, 0 failures). Well-formed/no-mocks confirmed (grep
clean: no `with-redefs`/mock/stub/spy). Production strings + gate logic in
`started.clj` match the pinned diagnostics.

❌ TR8 (new actionable gap): the IR2 process-reaping fix has **two** distinct
mechanisms by design — (a) the failure-path `catch` reaps the alive process via
the outer `launched-process` volatile, and (b) the launched `:process`/`:pid`
is recorded onto the runtime-handle **pre-wait** (moved off the post-wait
success update) "so both the readiness-failure `catch` and a later
`stop-started-instance-in!` can reap it" (design IR2 / impl L1447-1460). Only
mechanism (a) is tested: the IR2 case asserts `@destroyed*` only, which the
`catch` sets directly. A regression that drops the **pre-wait `:process` record**
(reverting it to the post-wait success path) while keeping the volatile-based
catch reap would pass `@destroyed*` and every current test, yet would silently
re-orphan any process that survives the catch — because `stop-started-instance-in!`
reads `:process` from the runtime-handle and would find it absent on a
failure-path instance. Compounding this, `stop-started-instance-in!`'s own
reaping branch (`(when (and process (.isAlive process)) (.destroy process))`)
has **no test at all**. Modest scope, coverage-only (production already correct):
either extend the IR2 case to also assert `(get-in instance [:runtime-handle
:process])` (and/or `:pid`) is present on the failed instance after the throw
(observed via a `status`/`instance-in` read of the failure-path instance), or
add a `stop-started-instance-in!` no-mocks case (alive `fake-process` with a
`:destroyed*` atom seeded on the runtime-handle `:process`) asserting stop
`.destroy`s it and removes the instance — pinning the second half of the IR2
contract the design mandates.

🔁 PATTERN: a fix with two stated mechanisms (immediate reap + recorded handle
for deferred reap) needs both pinned — testing only the immediate path lets a
regression collapse it to one mechanism while the suite stays green, re-opening
the exact leak the fix closed for the deferred path.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test-review follow-up execution — TR8 (ψ, 2026-06-04)

Executed the sole newly-added actionable item (TR8, added by task-test-review
pass 8). Coverage-only (production already correct: the pre-wait `:process`/`:pid`
record onto the runtime-handle and `stop-started-instance-in!`'s reaping branch
both exist and are verified by inspection); no production/doc/CHANGELOG change.

TR8 pins the **second half** of the IR2 process-reaping contract, which the
existing IR2 case left uncovered. Implemented *both* design options:

- (a) Extended the IR2 `start-instance-in-test` case ("reaps the alive launched
  process on the readiness-failure path (IR2)") to also read the failure-path
  instance via `instance-in` and assert `(get-in instance [:runtime-handle
  :process])` is present (non-nil) and `:pid` = 4321. This pins the **pre-wait**
  `:process`/`:pid` record: a regression reverting to the post-wait
  success-only update would keep `@destroyed*` green (the `catch` still reaps via
  the outer `launched-process` volatile) yet re-orphan any process that survives
  the catch, because `stop-started-instance-in!` reads `:process` from the
  runtime-handle and would find it absent on a failure-path instance.

- (b) Added `stop-started-instance-in-test` "reaps the alive recorded process and
  removes the instance (TR8)" — the previously-**untested**
  `stop-started-instance-in!` reaping branch
  (`(when (and process (.isAlive process)) (.destroy process))`). Seeds a
  `:started` instance (`ensure-instance-in!`) whose runtime-handle carries an
  alive `fake-process` (a `:destroyed*` atom) + `:pid 4321`, calls
  `stop-started-instance-in!`, and asserts `@destroyed*` is true (the process was
  reaped) and `instance-in` returns nil (the instance was removed). No mocks:
  real `Process` proxy seam; `disconnect-instance-in!` is a no-op without a
  seeded transport.

Verified: clj-paren-repair Success; clj-kondo 0/0
(`components/project-nrepl/test/psi/project_nrepl/started_test.clj`); `started-test`
**4 tests/52 assertions** green (+1 deftest, +5 assertions over pass-3's 47);
full `clojure -M:test --focus unit` green; `bb commit-check:file-lengths` RC=0.

🔁 PATTERN: a fix with two stated mechanisms (immediate `catch` reap via an outer
volatile + a recorded durable handle for deferred reap by `stop`) needs both
pinned — testing only the immediate path lets a regression collapse it to one
mechanism while the suite stays green, re-opening the exact leak the fix closed
for the deferred path. Cover both the producing record (pre-wait `:process`
present on the failure instance) and the consuming reaper
(`stop-started-instance-in!` destroys + removes).

PASS_STATUS: REVIEW_COMPLETE

## Test-review pass 9 (task-test-review, ψ, 2026-06-04)

Applied task-test-review (`well_formed(tests) ∧ ∀b∈behaviour(design).∃t.covers
∧ ∀d∈infra_deps.injectable ∧ nullable ∧ ¬mock ∧ ¬stub`) to the full task test
surface (started_test, ops_test, config_test, attach_test, test_support).

Behaviour→test coverage (complete): raised 120 s default (TR1); configurable
timeout end-to-end through ops/start (TR3) + invalid-config `:phase :validate`
through ops/start (TR6) + range bounds [1000 600000] at fn level (config-test);
pre-launch `.nrepl-port` removal + mtime-gate rejection `:started-stale-port` +
soft-continue poll-accept (TR7) + exit-with-stale-port preserves distinction
(IR1) + fresh-accept by construction; A2 diagnostic instants on
`:last-error → :data` (PA4/TS10); `:readiness-timeout-ms` instance-payload
projection (TR2/AMB3); `:started-at` = launch-instant provenance (PA2/TS4); IR2
reap on failure path + pre-wait `:process`/`:pid` record + `stop` reap (TR8a/b);
attach-mode behaviour-preserving + no started gate; shared `read-dot-nrepl-port`
mode-agnostic (TR4); happy path unchanged.

No-mocks discipline holds: real `java.lang.Process` proxy (`fake-process`), real
on-disk config files (`write-{user,project,local}-config!`), real temp dirs
(`with-temp-dir`), injectable `:process-launcher`/`:nrepl-connector` seams. No
`with-redefs`, stubs, or mocks anywhere. State/output assertions only; no
interaction assertions.

Determinism: `started-test` 4 tests/52 assertions green across 3 consecutive
runs (the timing-sensitive TR7 poll-continuation case is stable); full
`clojure -M:test --focus unit` RC=0.

No new actionable test issues. The pass-8 TR8 item (second half of the IR2 reap
contract) was the last actionable gap and is resolved by both the IR2-case
extension (pre-wait `:process`/`:pid` on the failure instance) and the new
`stop-started-instance-in-test` covering the previously-untested `stop` reap
branch. Tests are well-formed and behaviour-complete.

PASS_STATUS: REVIEW_COMPLETE
