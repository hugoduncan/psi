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
