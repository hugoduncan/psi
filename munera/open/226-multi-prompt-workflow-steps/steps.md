# 226 — Multi-prompt workflow steps — Steps

Checklist grouped by slice (see plan.md). Each item is independently executable
and verifiable. Tick with a sha/decision note as completed. Run `clj-kondo` +
`clj-paren-repair` after edits; run the relevant Scry suite per slice; commit per
slice.

**Per-slice Scry gating (P9):** the "relevant Scry suite" for each slice is the
Scry suite of **every component that slice edits** (see plan.md "Per-slice Scry
gating"): Slice 1 = workflow-runtime + workflow-loader +
workflow-step-materialization (Slice 1 edits all three — `ir.clj`/
`step_execution.clj`, `compiler.clj`, `core.clj`, PI9; the focused
`step_execution_test.clj` namespace is the N=1-equivalence done-gate comparand,
P7, not a narrower gate); Slice 2 = workflow-loader + workflow-runtime; Slice 3 =
workflow-runtime (edits `step_execution.clj` + `progression_recording.clj`;
`core.clj` materialization lands in Slice 1, not Slice 3, PI9/PI10); Slices 4–6 =
workflow-runtime. Distinct from Final verification's full three-component run by
the latter's AC-1..AC-8 coverage confirmation, not by a different suite set.

## Slice 1 — Unified single-prompt queue path (N=1 degenerate)

- [x] Characterize current single-prompt behaviour: add a **committed
  asserted-shape characterization test** (in/alongside
  `statechart_runtime/step_execution_test.clj`) pinning the single-prompt
  `execute-session-step!` `:pending-actor-result` envelope shape
  (`:final-llm-reply`/`:text`/`:transcript`/structured `:outputs` keys) — not a
  full-content golden snapshot. **Commit it first**, green against current
  (pre-refactor) code; this is the R4 equivalence-baseline comparand (P3).
  (`single-prompt-session-step-envelope-characterization-test`, committed first,
  16 assertions green pre-refactor.)
- [x] Define the internal normalized prompt-queue representation in `ir.clj`
  (ordered vector of prompt-groups; group has optional `:name`, body =
  materialized contributions/prompt-workflow). Single unnamed group for
  `:contributions`/`:prompt-workflow`. (`prompt-group-schema`/`prompt-queue-schema`
  + `session-step-prompt-queue` derivation + `valid-prompt-queue?`; covered by
  `session-step-prompt-queue-derivation-test` + `prompt-queue-schema-test`.)
- [x] Normalize `:contributions`/`:prompt-workflow` session steps into a
  length-1 internal queue at **compile time** (workflow-loader
  owns the authored-form → normalized-queue transform, P1; `ir.clj` owns only the
  normalized-queue schema/validation), preserving the existing canonical
  `:session :contributions` shape downstream. **Deviation (see
  implementation.md):** the authored-form → canonical-IR transform that nests
  config under `:session` lives in workflow-runtime `target_ir_compiler.clj`, not
  workflow-loader `compiler.clj`. For the N=1 degenerate the canonical IR already
  carries `:session :contributions`, so the length-1 queue is *derived* from it by
  `ir/session-step-prompt-queue` (no compiler change needed yet); `:prompts` →
  named-group compilation lands in Slice 2 in `target_ir_compiler.clj`.
  (Done as a *consumed* derivation: the `:step/enter` session branch in
  `statechart_runtime.clj` now derives the length-1 queue via
  `ir/session-step-prompt-queue` and materializes it per-group — see the
  refactor item below.)
- [x] Add a per-group materialization entry point in
  `workflow_step_materialization/core.clj` (reuse
  `materialize-step-session-conversation` + `split-step-session-conversation`
  per group; length-1 queue yields today's single prompt).
  (`materialize-prompt-group-conversation` over a shared
  `materialize-contributions-conversation` primitive; covered by
  `materialize-prompt-group-conversation-matches-single-prompt-materialization-test`.)
- [x] Refactor `execute-session-step!` to drive a length-1 internal queue
  producing the **identical** `:pending-actor-result` envelope (no behaviour
  change). Single suspend point unchanged. **Deviation (see implementation.md):**
  for the N=1 degenerate the queue *driving* is realized at the materialization
  site (`statechart_runtime.clj` `:step/enter` session branch), which now derives
  the length-1 queue via `ir/session-step-prompt-queue` and materializes the
  single group via the injected
  `:materialize-workflow-prompt-group-conversation-fn` (→
  `materialize-prompt-group-conversation`), then splits into the same prompt.
  `execute-session-step!` is unchanged — it already executes exactly one turn and
  emits the identical envelope; for N=1 there is nothing to loop. The N>1 in-run
  drive loop lands in Slice 3.
- [x] Slice-1 done-gate: the committed envelope characterization test (P3) is
  green **unchanged** after the unified-path refactor, **and** the **three edited
  components' Scry suites** are green unchanged — workflow-runtime
  (`ir.clj`/`step_execution.clj`/`statechart_runtime.clj`) + workflow-loader
  (`compiler.clj`) + workflow-step-materialization (`core.clj`), since Slice 1
  edits all three (P9/PI9) — (AC-2 N=1 equivalence as a consequence of the
  unified path). Verified: step-execution-test + ir-test 18/195 green
  (characterization unchanged); workflow-runtime + workflow-loader suites green
  (sole failure `workflow-definitions-test/task-lifecycle-test` is pre-existing
  and unrelated — a stale `task-lifecycle.edn` step-structure assertion, fails
  identically with the changes stashed); agent-session end-to-end session-step
  suites (session-integration / execution / statechart-runtime / execution-resume)
  142/29 green proving the `:step/enter` wiring is behaviour-preserving;
  workflow-step-materialization core-test 8/16 green.
  **Equivalence comparand scope (P7):** the focused workflow-runtime
  `step_execution_test.clj` namespace
  (`psi.workflow-runtime.statechart-runtime.step-execution-test`) that houses the
  characterization test is the specific N=1-equivalence **comparand** within the
  workflow-runtime suite — **not** a narrower substitute for the per-component
  green requirement; it is distinct from Final verification's three-component run
  by the latter's AC-1..AC-8 coverage confirmation, not by a different suite set.
  Any change to the asserted envelope shape ⇒ defect.
- [x] `clj-kondo` clean; commit Slice 1.

## Slice 2 — `:prompts` grammar + IR normalization (named groups)

- [x] Add the `:prompts` session-step schema to `ir.clj`: ordered vector of named
  prompt-groups; each group `{:name … (:prompt-workflow XOR :contributions)}`.
  (`session-spec-schema` now carries optional `:contributions`/`:prompts` —
  `:prompts` reuses `prompt-queue-schema` (`[:vector {:min 1} prompt-group-schema]`).
  Group bodies are canonical `:contributions` post-compile; group-internal
  `:prompt-workflow` is an authored-form key resolved in workflow-loader.)
- [x] Add step-level precedence validation: `:contributions`/`:prompt-workflow`
  **xor** `:prompts` (both ⇒ IR error). (`session-prompt-queue-errors` →
  `:session-contributions-and-prompts`; the `:prompt-workflow` half is caught at
  the workflow-loader before it resolves to `:contributions`. Neither present ⇒
  `:session-without-prompt-source`.)
- [x] Add `:prompts` validation: empty `:prompts` ⇒ error (structural via
  `prompt-queue-schema` `{:min 1}`); one-element ⇒ valid (B3); duplicate
  prompt-group `:name` within a step ⇒ error (`:duplicate-prompt-group-name`),
  names may repeat across steps (B4); unnamed `:prompts` group ⇒
  `:unnamed-prompt-group`.
- [x] Add group-internal precedence validation: `:prompt-workflow` **xor**
  `:contributions` within a group; both ⇒ error, neither ⇒ error (E2). (In
  workflow-loader `compiler.clj` `compile-prompt-group`, where `:prompt-workflow`
  exists before resolution.)
- [x] Compile `:prompts` in `compiler.clj`: each group resolves its
  `:prompt-workflow` (relative .md, existing path rules) or `:contributions` into
  the normalized internal queue; named groups carry `:name`. (workflow-loader
  `compile-prompts-step`/`compile-prompt-group` resolve group `:prompt-workflow`
  → group `:contributions`; workflow-runtime `target_ir_compiler.clj`
  `compile-prompt-group` lowers each authored group into canonical IR under
  `:session :prompts`.)
- [x] IR-validation tests: empty/one-element/duplicate-name/group-xor/step-xor
  cases (red→green). (`ir_test/session-prompts-grammar-validation-test`,
  `target_ir_compiler_test/compile-target-multi-prompt-session-workflow-test`,
  `compiler_test/compile-edn-prompts-step-test`.)
- [x] Docs (P2): `doc/workflow-grammar.md` — `:prompts` author form, step-level
  `:contributions`/`:prompt-workflow` xor `:prompts`, group-internal
  `:prompt-workflow` xor `:contributions`, name-uniqueness/empty/one-element rules.
- [x] `clj-kondo` clean; commit Slice 2.

## Slice 3 — Sequential N-turn drain (in-run suspend/resume) + per-prompt records

Slice 3 builds the **in-run** suspend/resume drain mechanism (P4): because each
turn is an async `ai/generate` that suspends the run, advancing _n_ → _n+1_
re-enters the acting state and consults recorded progression to pick the next
un-run prompt. Slice 5 adds only the process-restart/replay resume case on top.

**"Shared sources" defined (P11, design E1).** Throughout Slice 3, "first group
loads shared sources" means the **first turn's materialized conversation** — the
first prompt-group's body materialized **together with** the session-level
system/skill/tool content — submitted on turn 1 and then **persisting in the live
child session** so later groups see it via conversation memory. It is **not** a
step-level shared `:contributions`/preamble/`:preload` (the step-level
`:contributions` xor `:prompts` rule forbids one, and the grammar has no
`:preload` field). So "first group loads shared sources" and the
no-step-level-preamble rule do **not** contradict: the sharing mechanism is
live-session conversation memory, not an authored shared preamble.

- [x] Extend the queue driver in `execute-session-step!` to run prompt _n+1_
  after prompt _n_ completes, against the **same** child session id (first group
  loads shared sources; later groups rely on live-session memory, E1). On each
  re-entry of the acting state, select the next **un-run** prompt from recorded
  per-prompt progression (progression-driven, **not** an in-memory counter);
  never re-fire a completed turn within the live run. **DONE:** added
  `drive-session-prompt-queue!` (loops the shared per-turn primitive
  `execute-session-turn-outcome`, extracted from `execute-session-step!`), wired
  at the `:step/enter` `:else` branch behind `(some :name prompt-queue)` (named
  multi-prompt queues drive; the unnamed N=1 degenerate keeps calling
  `execute-session-step!` byte-identically). Group 0 uses the pre-split
  `:step/enter` prompt; later groups materialize+split against the live session
  via the ctx materialize/split fns. Selection reads
  `next-un-run-prompt-group` from live `state*` each loop iteration; recording
  through `update-state-if-live!` advances the next read.
- [x] Add per-prompt turn-record recording in `progression_recording.clj` under
  the step's attempt (ordered, keyed by `:name` for named groups only; unnamed
  group records only the step-level rollup, C3). Added `record-prompt-group-turn`
  (idempotent on static queue `:index` — no re-append of an already-recorded turn,
  upholding F1 non-re-fire), plus the progression-state probe readers
  `prompt-group-turn-records` / `recorded-prompt-group-indices` and the
  progression-driven selector `next-un-run-prompt-group` (lowest un-run static
  position from recorded indices, `:final?` = last position; nil when drained).
  Records land on the latest attempt under `:prompt-group-turns`; reads resolve the
  latest attempt **map** by index (`latest-attempt` returns the attempts vector,
  a pre-existing quirk). Covered by
  `prompt-group-turn-record-substrate-test` + `next-un-run-prompt-group-test`
  (9/46 progression-recording green). This is the substrate the driver item (above)
  consults for next-un-run selection.
- [x] Emit **one** post-drain `:pending-actor-result` carrying the step-level
  rollup plus ordered per-prompt records for named groups (A3); reconcile with
  the existing `record-actor-result`/`record-step-result` path (one route, Q5).
  **DONE:** `post-drain-envelope` builds the final turn's envelope with the
  accumulated `:transcript` and the ordered per-prompt records under
  `:prompt-group-outputs`; emitted once via `record-actor-pending!` →
  `:actor/done`. Tests assert exactly one terminal event (one statechart-visible
  route, Q5).
- [x] Request structured `:outputs` on the **final** turn only (R5/AC-3).
  Final-turn detection (P5) uses the **static IR queue position** — the group at
  the last index of the ordered normalized prompt-queue — derivable from the IR
  alone and **orthogonal** to the progression-driven next-un-run selection (the
  no-counter rule governs only *which un-run prompt runs next*, never *whether
  the selected group is last*). No in-memory counter is used for either.
  **DONE:** `turn-opts`/`turn-structured-entry` attach only when
  `(:final? group)`; the upfront request-validity gate (P13a) runs before turn 1.
  Non-final turns exclude the declared structured key from surface resolution
  (`surface-step-def`) so an absent structured value cannot throw.
- [x] Runtime tests (Slice-3 independent acceptance, P4): in one **live** run, N
  turns execute in author order; assert the driver selects the next un-run prompt
  from recorded progression via a **progression-state probe** (not turn count) —
  the probe's concrete observable (P6) is the **recorded per-prompt turn-record
  set under the step's attempt, read back through `progression_recording.clj`**
  (the same substrate the driver consults): the test reads which prompts already
  have a recorded turn and asserts the next submission is the lowest-position
  un-run group; drain reached only after all turns; one post-drain result; each
  named turn record introspectable (S4); N=1 unnamed still rollup-only.
  **DONE:** `drive-session-prompt-queue-runs-named-turns-in-order-test` (order +
  introspectable records + one post-drain) and
  `drive-session-prompt-queue-resume-skips-recorded-prompts-test` (a pre-recorded
  index 0 re-entry runs only index 1 — progression-state probe).
- [x] Assert non-re-fire (P8) within the live drain: the **count of
  `ai/generate` effects emitted at the dispatch/effect boundary** (captured via
  the test effect seam) is exactly one per un-run prompt and **zero** for any
  prompt that already has a recorded turn; corroborate with **no second turn
  record / no progression mutation** for an already-recorded prompt.
  **DONE:** the `:workflow-execute-actor-turn-fn` seam counts turn calls; the
  in-order test asserts exactly N calls (one per prompt), and the resume test
  asserts 1 call (zero for the pre-recorded index).
- [x] Confirm in `statechart.clj` that the single
  acting→(judging)→record-result step topology still holds with the N-turn drain
  (one statechart step, N internal turns); assert **no per-prompt statechart
  states** are introduced (plan Touch point `statechart.clj`). **DONE (confirm-only,
  no edit):** the drain loops synchronously **inside** the one `:step/enter`
  acting action and emits exactly one terminal `:pending-actor-result`/event, so
  the statechart sees one acting→done identical to single-prompt — no new states.
  The in-order test's `(= 1 (count @event-queue*))` locks the single-route shape.
- [x] Docs (P2): `doc/workflow-grammar-concepts.md` — drain/route semantics and
  per-prompt turn records (named groups only). **DONE:** added the "Drain and
  routing" subsection to `doc/workflow-grammar.md` (where the multi-prompt grammar
  section lives) covering drain-before-route, last-reply/accumulated-transcript,
  final-turn structured output, and named-only per-prompt records.
- [x] `clj-kondo` clean; commit Slice 3.

**Slice 3 deviations.** (1) Driver lives in a dedicated
`drive-session-prompt-queue!`, not by overloading `execute-session-step!` — both
share the extracted per-turn primitive `execute-session-turn-outcome`, so there
is one turn path (N=1 degenerate stays byte-identical; the loop is orchestration
only). (2) Because `execute-actor-turn!` is **synchronous** here (returns a
result directly, not an async suspend), the in-run drain is a synchronous loop;
it still consults recorded progression each iteration (progression-driven, no
counter), so the resume contract holds — Slice 5 only adds the
process-restart/replay re-entry on top. (3) Later-group `preloaded-messages`
(multi-message groups) are not re-injected mid-session — later groups submit only
their split `prompt` (the common `:prompt-workflow`/single-user-message case has
empty preloaded-messages); revisit if a multi-message later group is needed.
(4) Basic abort dispositions (`:failed`/`:blocked`/`:cancelled`) are wired in the
driver but their full retained-records/naming semantics land in Slice 6.

## Slice 4 — Per-prompt output surfaces + `:prompt` source-ref + validation

- [x] Implement step-level surfaces (`:final-llm-reply` = last prompt;
  `:transcript` = accumulated) vs per-prompt turn-local surfaces
  (`:final-llm-reply`/`:transcript` = that group's turn slice, B2) in
  `step-output-surfaces`/`step-output-value`.
  - **Deviation**: step-level surfaces were already built in Slice 3's
    `post-drain-envelope` (last reply + accumulated transcript). `step-output-value`
    needed **no** change — per-prompt resolution reuses it over the named group's
    turn-local outputs map (`step-output-value nil {:outputs turn-local} k`). The
    actual resolution lives in `workflow-step-materialization/source_resolution.clj`,
    not `step_execution.clj` (plan touch-point listed the latter; the shared
    source-ref resolution substrate is in materialization).
- [x] Add the optional `:prompt` discriminator to `source-ref-schema` /
  `step-output-ref-schema` in `ir.clj` (key on `{:step s :output k}`).
- [x] Resolve `{:step s :prompt p :output k}` uniformly across the shared
  substrate (invoke args, contributions source items, template vars, delegated
  context) via the existing source-resolution path — no per-call-site code.
  (Added one `:prompt` clause to `resolve-source-ref`, ordered before the
  step-level `:output` clause; all call sites inherit it.)
- [x] Extend `ref-errors` with `:prompt`-selector validation: invalid when target
  step is non-session, single-prompt, unknown group `p`, non-text key `k`
  (structured/`:result`), a `:yield` ref (B1 — `:prompt` is `:output`-only), or
  the **same step being assembled** (sibling-group ref, E3).
  - **Deviation**: the `:yield`+`:prompt` invalid case is enforced **structurally**
    (`:prompt` is on `step-output-ref-schema` only, which requires `:output`), so
    a `:prompt`+`:yield` ref cannot pass `source-ref-schema` — `unreachable >
    forbidden`, no dedicated semantic error needed.
  - Split `step-source-refs` into `step-body-source-refs` + `step-judge-source-refs`
    so `ref-errors` receives a `judge?` flag (precise carve-out) instead of the
    pre-existing imprecise flat-list heuristic.
- [x] Carve out the step's own **post-drain `:judge`** from the same-step invalid
  rule: its `{:step s :prompt p :output k}` refs are permitted (resolves after
  drain, all turns recorded, G2/AC-4).
- [x] IR-validation tests for every invalid case + the post-drain-judge carve-out
  + back-compat (no-`:prompt` ref → step-level surface). (`prompt-source-ref-validation-test`)
- [x] Runtime test: a no-`:prompt` ref against a multi-prompt step hits the
  step-level surface; a `:prompt` ref hits the group's turn-local surface.
  (`resolve-prompt-discriminated-per-prompt-surface-test`)
- [x] Docs (P2): `doc/workflow-grammar-concepts.md` — per-prompt output surfaces,
  the `:prompt` source-ref discriminator + its validation rules + the post-drain
  judge carve-out.
- [x] `clj-kondo` clean; commit Slice 4.

## Slice 5 — Resume-from-progression across process-restart/replay (F1)

Slice 3 already lands the in-run progression-driven drain; Slice 5 adds **only**
the process-restart / event-log-replay resume case and proves its idempotency
(P4). The suspend/resume boundary inside the single statechart step is unchanged
from Slice 3 — this slice exercises and hardens it across reconstructed state.

- [x] Confirm the `statechart_runtime.clj` session (`:else`) branch resume path
  reconstructs queue position **purely** from persisted per-prompt progression on
  process-restart re-entry (no reliance on in-memory loop state surviving the
  restart); continue at the next **un-run** prompt; never re-submit a prompt whose
  turn record exists (no `ai/generate` re-fire). **DONE (confirm-only, no edit):**
  the `:else` branch re-invokes `drive-session-prompt-queue!`, whose loop reads
  `next-un-run-prompt-group` from `(:state* ctx)` (the persisted canonical atom)
  **each iteration** — there is no in-memory queue counter, so a fresh
  post-restart process consults only persisted progression. **Finding (see
  implementation.md):** because `execute-actor-turn!` is **synchronous** (the
  Slice-3 R1 fallback), the entire N-turn drain completes inside one `:step/enter`
  action — the statechart never suspends mid-drain, and `:workflow/resume` fires
  only from `:blocked` (a new attempt). So statechart-level mid-drain restart is
  not an occurring path; the realized resume mechanism is the per-iteration
  progression re-read, exercised here against a reconstructed state*.
- [x] Runtime test (Slice-5 independent acceptance, P4): a mid-queue resume
  reconstructed from persisted progression runs only the un-run prompts
  (resume-from-progression idempotency, AC-7); completed turns not re-fired.
  (`drive-session-prompt-queue-reconstructs-position-from-persisted-progression-test`:
  fresh state*/ctx with indices 0+1 recorded ⇒ only index 2 fires; prior records
  retained verbatim; reaches `:actor/done`.)
- [x] Verify replay path: replaying the event log reproduces the same per-prompt
  records without re-firing completed `ai/generate` effects. Assert non-re-fire
  with the **same observable as Slice 3 (P8)**: the **count of `ai/generate`
  effects emitted at the dispatch/effect boundary** is **zero** for any prompt
  with an existing turn record across the reconstructed/replayed state;
  corroborate with no second turn record / no progression mutation for an
  already-recorded prompt.
  (`drive-session-prompt-queue-replay-fully-recorded-fires-zero-turns-test`:
  fully-recorded reconstructed state* ⇒ 0 turn-calls, records untouched, drains
  straight to `:actor/done`. The P8 observable is the `:workflow-execute-actor-turn-fn`
  seam call-count — zero for already-recorded prompts.)
- [x] Docs (P2): the resume-from-progression contract. **DONE:** added the
  "Resume and idempotency" subsection to `doc/workflow-grammar.md` (alongside the
  Slice-3 "Drain and routing" subsection where the multi-prompt grammar lives;
  per the Slice-3 deviation, multi-prompt doc content is consolidated in
  `workflow-grammar.md` rather than split into `-concepts.md`).
- [x] `clj-kondo` clean; commit Slice 5.

## Slice 6 — Abort paths

- [x] Intermediate-turn error ⇒ stop queue, step `:failed` with payload naming
  the failing prompt, routing skipped; prior per-prompt records retained, failing
  prompt leaves **no** record (AC-5/G1). **DONE (already wired in the Slice-3
  driver; Slice 6 = covering test):**
  `drive-session-prompt-queue-intermediate-turn-error-fails-naming-prompt-test`.
- [x] **Final/last-turn error + N=1 error semantics (P10), reconciled with
  AC-5/AC-2.** **DONE:**
  `drive-session-prompt-queue-final-turn-error-fails-naming-prompt-test` (last
  prompt of a 3-queue errors ⇒ `:failed` naming index 2, indices 0/1 retained, no
  index-2 record, routing skipped). The N=1 degenerate failure path
  (`execute-session-step!`, no prompt name) is covered by the pre-existing
  single-prompt failure tests. A turn error at **any** queue position — including
  the **final (last) prompt** of an N>1 queue — follows the **same** `:failed`
  abort as the
  intermediate case: the drain never completes successfully, so the post-drain
  route is **not** reached (routing skipped), per-prompt records for prompts
  completed before the failing one are retained and introspectable, and the
  failing (last) prompt leaves **no** record — identified only by the `:failed`
  payload. "Intermediate" in AC-5 is therefore "any non-completing turn", not
  "strictly not-last". For the **N=1 degenerate** (unnamed `:contributions`
  group): the same unified `:failed` abort applies, but with no named group the
  `:failed` payload names no prompt (no `:name`), so the outcome is byte-
  equivalent to today's pre-existing single-prompt failure route — preserving the
  AC-2 N=1 equivalence (no separate single-prompt failure path is retained).
- [x] Inter-prompt cancellation ⇒ terminal `:cancelled` (distinct from
  `:failed`), routing skipped, completed per-prompt records retained +
  introspectable, in-flight turn aborted per existing cancellation contract
  (AC-6/B5). **DONE:**
  `drive-session-prompt-queue-inter-prompt-cancellation-test`.
- [x] **In-flight (mid-turn) cancellation record disposition (P12), reconciled
  with AC-6/AC-5.** A cancellation arriving **while a prompt's turn is in flight**
  yields the **same** terminal `:cancelled` outcome (routing skipped) as the
  inter-prompt case — one cancellation outcome regardless of whether the cancel
  lands between turns or mid-turn. The interrupted in-flight prompt leaves **no**
  completed turn record (symmetric with AC-5's failing prompt "leaves no
  record"); only prompts that **completed before** the cancel are retained and
  introspectable. The in-flight turn is aborted per the existing cancellation
  contract. Make the cancellation path's in-flight-record disposition as explicit
  as AC-5 makes the failure path's: completed-before ⇒ retained;
  interrupted-in-flight ⇒ no record.
- [x] **Structured-output `:blocked` across the drain (P13), reconciled with
  AC-3/AC-5.** **DONE:** upfront invalid-request block covered by Slice-3's
  `drive-session-prompt-queue-blocks-upfront-on-invalid-structured-request-test`;
  final-turn `:unsupported-structured-output` block after N−1 turns covered by
  `drive-session-prompt-queue-final-turn-structured-output-blocked-test` (terminal
  `:blocked`, index-0 record retained, blocking final prompt leaves no record,
  routing skipped). Add `:blocked` (`:actor/blocked`) as a third terminal non-success
  outcome alongside `:failed`/`:cancelled`, for three structured-output reasons:
  (i) invalid structured-output **request** — checked **upfront before turn 1**
  (static / turn-independent, fail-fast: zero turns run, zero per-prompt records);
  (ii) `:unsupported-structured-output` and (iii) `:invalid-structured-output` —
  both **final-turn-only**, since structured `:outputs` is requested on the final
  turn alone (P5). A **final-turn block after N−1 turns ran** yields terminal
  `:blocked` (distinct from `:failed`/`:cancelled`), **routing skipped** (no
  successful post-drain result), **prior N−1 completed per-prompt records retained
  + introspectable** (symmetric with AC-5/AC-6), and the **blocking final prompt
  leaves no completed turn record** (symmetric with AC-5's failing prompt / P12's
  interrupted-in-flight). For the **N=1 degenerate** the `:blocked` outcome is
  byte-equivalent to today's single-prompt blocked path (no named group ⇒ no
  prompt name; zero records either way), preserving AC-2 equivalence.
- [x] Runtime tests: intermediate-failure abort + retained prior records;
  inter-prompt cancellation outcome + retained records; **in-flight (mid-turn)
  cancellation: same `:cancelled` outcome + completed-before records retained +
  interrupted prompt leaves no record (P12)**; **structured-output `:blocked`
  (P13): upfront invalid-request block before turn 1 (zero turns/records); a
  final-turn `:unsupported-structured-output`/`:invalid-structured-output` block
  after N−1 turns ⇒ terminal `:blocked` + prior N−1 records retained +
  introspectable + blocking final prompt leaves no record**; all skip routing.
  **DONE:** new namespace
  `step_execution_drive_prompt_queue_abort_test.clj` (4 tests: intermediate
  `:failed`, final-turn `:failed`, inter-prompt/in-flight `:cancelled`,
  final-turn structured-output `:blocked`); P13 upfront block reuses the Slice-3
  test.
- [x] Docs (P2): abort/cancellation/blocked outcomes across the queue (`:failed`
  vs `:cancelled` vs `:blocked`, retained records, routing skipped). **DONE:**
  "Abort, cancellation, and blocked outcomes" subsection in
  `doc/workflow-grammar.md` (consolidated with the multi-prompt grammar there per
  the Slice-3 doc-placement deviation).
- [x] `clj-kondo` clean; commit Slice 6.

## Slice 7 — Docs consolidation + changelog + coherence

Author-facing grammar docs are written **incrementally** in the slice that
introduces each surface (P2; see plan.md "Author-facing docs cadence"). Slice 7
consolidates them — it does not first-author them.

- [x] Consolidation pass over the incrementally-written `doc/workflow-grammar.md`
  + `doc/workflow-grammar-concepts.md` content (Slices 2–6): cross-link sections,
  fill any gaps, verify the `:prompts` form / group-internal xor / per-prompt
  surfaces / `:prompt` source-ref + validation + post-drain-judge carve-out /
  drain-route / resume contract / abort + cancellation + blocked outcomes
  (`:failed` vs `:cancelled` vs `:blocked`, retained records, routing skipped) are
  all present and consistent. **DONE:** grammar.md carries the `:prompts` form,
  precedence/validation, drain-route, resume-and-idempotency, and abort/cancel/
  blocked subsections; concepts.md carries the per-prompt source-ref + validation
  + judge carve-out. Added a cross-link from grammar.md's per-prompt-record bullet
  to concepts.md's *Per-prompt output surfaces*; concepts.md already cross-links
  back to "the grammar reference".
- [x] CHANGELOG `[Unreleased] Added`: multi-prompt `:session` step capability
  (ordered `:prompts` queue, per-prompt addressing). **DONE.**
- [x] Verify coherence across meta/spec/tests/code/docs (TraceID for AC-1..AC-8).
  **DONE (see Final verification below).**
- [x] `clj-kondo` clean; commit Slice 7.

## Final verification

- [x] Run the full workflow-runtime + workflow-loader + workflow-step-
  materialization Scry suites green. **DONE:** workflow-runtime 129 tests / 709
  assertions green; workflow-step-materialization 26 / 54 green; workflow-loader
  53 green with the sole `workflow-definitions-test/task-lifecycle-test` failure
  **confirmed pre-existing and unrelated** (a stale `task-lifecycle.edn`
  step-structure assertion: fails identically — 4 passed / 16 failed — at the
  session-start commit `cf3f43d9f`, before any 226 multi-prompt work, verified in
  a throwaway base worktree).
- [x] Confirm AC-1..AC-8 each have covering tests. **DONE (TraceID):**
  - **AC-1** ordering/same-session/sequential —
    `drive-session-prompt-queue-runs-named-turns-in-order-test`.
  - **AC-2** N=1 equivalence — `single-prompt-session-step-envelope-characterization-test`
    + the `execute-session-step!` single-turn suite (unnamed degenerate).
  - **AC-3** output surfaces (step-level last/accumulated, per-prompt turn-local,
    structured on final turn, yield unchanged) — in-order test +
    `drive-session-prompt-queue-requests-structured-output-on-final-turn-only-test`
    + `resolve-prompt-discriminated-per-prompt-surface-test`.
  - **AC-4** route once after drain + judge per-prompt carve-out — in-order test
    (`(= 1 (count @event-queue*))`, one `:actor/done`) + `prompt-source-ref-validation-test`.
  - **AC-5** intermediate-failure abort (+ structured `:blocked`, PI12) —
    `drive-session-prompt-queue-intermediate-turn-error-fails-naming-prompt-test`,
    `...-final-turn-error-fails-naming-prompt-test`,
    `...-final-turn-structured-output-blocked-test`,
    `...-blocks-upfront-on-invalid-structured-request-test`.
  - **AC-6** inter-prompt/in-flight cancellation —
    `drive-session-prompt-queue-inter-prompt-cancellation-test`.
  - **AC-7** resume-from-progression idempotency —
    `...-reconstructs-position-from-persisted-progression-test` (the single
    progression-skip test after the TS-4 fold of `...-resume-skips-recorded-prompts-test`),
    `...-replay-fully-recorded-fires-zero-turns-test`.
  - **AC-8** docs + IR-validation + runtime coverage — `session-prompts-grammar-validation-test`
    (ir-prompts-test) + `compile-edn-prompts-step-test` + the runtime suites above
    + `doc/workflow-grammar.md` / `-concepts.md`.
  The `:blocked` terminal outcome is preservation-only (an existing
  `execute-session-step!` `:actor/blocked` outcome preserved across the drain),
  traced under AC-3 (structured-output viability) + AC-5 (abort disposition: routing
  skipped, prior records retained, blocking prompt leaves no record), not a
  separate AC (PI12).

## Plan-review follow-ups (ambiguity, pass 1)

- [x] P1 — Decide and state in plan.md (Slice 1/2 + Touch points) which component
  owns prompt-queue normalization: `:contributions`/`:prompt-workflow` → unnamed
  group and `:prompts` → named groups as workflow-loader compilation
  (`compiler.clj`) vs workflow-runtime IR shaping (`ir.clj`). Replace the
  "`compiler.clj` +/or `ir.clj`" with a single owner per the workflow-runtime
  boundary; `λone_way`.
- [x] P2 — Clarify in plan.md (Slice order note + Slice 7) whether author-facing
  `doc/workflow-grammar*.md` is updated incrementally per slice (change_chain
  "spec") or consolidated in Slice 7; if incremental, state what each earlier
  slice documents; if Slice 7-only, reconcile with the per-slice "update spec
  (grammar docs)" change_chain wording.
- [x] P3 — Specify the Slice-1 equivalence-baseline artifact: name the concrete
  mechanism (committed characterization/snapshot test pinning the
  `:pending-actor-result` envelope shape, or an asserted-shape test) that R4's
  "treat any diff as a defect" compares against, and make it the Slice-1
  done-gate comparand rather than only "suite green unchanged".
- [x] P4 — Disambiguate the Slice 3 / Slice 5 boundary: state whether Slice 3
  builds the in-run suspend/resume drain (Slice 5 adds only process-restart/replay
  resume) or whether the resume-from-progression mechanism must land with Slice 3;
  give each slice an independently testable acceptance for the shared
  N-suspend-point mechanism (design F1).

## Plan-review follow-ups (inconsistency, pass 1)

- [x] PI1 — Reword plan.md Slice 1 ("IR normalizes
  `:contributions`/`:prompt-workflow` → one unnamed prompt-group") to attribute
  the authored-form → normalized-queue transform to the **compiler
  (workflow-loader `compiler.clj`)**, consistent with the resolved P1 ownership
  decision, the Touch points split, and steps Slice 1 (`ir.clj` owns only
  schema/validation/surfaces, not the transform).
- [x] PI2 — Update plan.md Slice-order Slice 1 acceptance ("full existing
  session-step suite green") to cite the committed asserted-shape envelope
  characterization test as the Slice-1 **done-gate comparand**, matching R4
  ("not merely 'suite green unchanged'") and steps Slice 1's done-gate.
- [x] PI3 — Add the step-level `:contributions`/`:prompt-workflow` **xor**
  `:prompts` validation (both ⇒ IR error) to plan.md Slice 2's IR-validation
  enumeration, so plan Slice 2 covers the same step-level xor that steps Slice 2
  (and its step-xor test case) already schedules.
- [x] PI4 — Reorder the plan.md Approach layering narrative ("before N>1,
  addressing, abort paths, and resume are layered on") to reflect the actual
  slice order — addressing (Slice 4) → resume (Slice 5) → abort (Slice 6), i.e.
  "addressing, resume, and abort paths".
- [x] PI5 — Add the abort/cancellation outcomes (`:failed` vs `:cancelled`,
  retained records, routing skipped) introduced by Slice 6 into the Slice 7
  consolidation verify-list, so the Slice-7 coherence checklist covers every
  per-slice author-facing doc surface.
- [x] PI6 — Add a steps item (Slice 3 or 5) confirming the single
  acting→(judging)→record-result statechart topology / no per-prompt statechart
  states (plan Touch point `statechart.clj`), or drop that touch point if it is
  subsumed by the `statechart_runtime.clj` resume-branch step.

## Plan-review follow-ups (ambiguity, pass 2)

- [x] P5 — State in plan.md (Slice 3 / R5) and steps.md Slice 3 how the queue
  driver identifies the **final** group for structured-output gating, reconciled
  with the no-counter rule: final-turn detection uses the static queue position
  (last group in the ordered normalized IR queue), independent of the
  progression-driven *selection* of the next un-run prompt (which forbids an
  in-memory counter). Make explicit that "final turn" is an IR/position property,
  not progression/counter state.
- [x] P6 — Define the "progression-state probe" test observable in plan.md
  Slice-order 3 and steps.md Slice 3: name the concrete observable the probe
  reads (e.g. recorded per-prompt progression entries under the step's attempt in
  `progression_recording.clj`, a runtime introspection surface, or the
  `:pending-actor-result` records map), so "assert via a progression-state probe,
  not turn count" is executable as written.
- [x] P7 — Define the scope of "full existing session-step suite" in the Slice-1
  done-gate (plan.md:154 / steps.md:35): name which test namespaces/suite
  (`step_execution_test.clj` only, the whole workflow-runtime suite, or all three
  component Scry suites) must be green-unchanged to gate Slice 1, distinct from
  the Final-verification three-suite run.
- [x] P8 — Name the concrete observable used to assert "no `ai/generate` re-fire"
  / "zero re-fired `ai/generate` effects" across Slice 3 (live drain) and Slice 5
  (restart/replay resume): emitted-`ai/generate`-effect count at the
  dispatch/effect boundary, a generate-seam invocation probe, or absence of a
  second turn record / progression mutation for an already-recorded prompt — so
  the idempotency acceptance for both slices has an enforceable measurement.

## Plan-review follow-ups (inconsistency, pass 2)

- [x] PI7 — Reconcile the in-run `statechart_runtime.clj` `:else`-branch
  resume-from-progression ownership between plan.md and steps.md. plan Touch
  points (plan.md:89) assign the `:else` branch the in-run "consult progression,
  continue at next un-run prompt" work and plan Slice 3 says Slice 3 *builds* the
  in-run suspend/resume drain, but steps Slice 3 has no `statechart_runtime.clj`
  item (it places next-un-run selection in `execute-session-step!`) and the only
  steps `statechart_runtime.clj` item is Slice 5's restart-scoped *confirm*.
  Either add a Slice 3 steps item building the in-run `statechart_runtime.clj`
  `:else`-branch resume-from-progression re-entry (aligning file-ownership wording
  with plan Touch points), or scope the plan `statechart_runtime.clj` touch point
  to the restart/replay re-entry only (matching steps Slice 5) so in-run
  next-un-run selection ownership is stated once, consistently, in both files.
- [x] PI8 — Resolve the dangling "Final verification" reference: steps.md has a
  `## Final verification` section running the full three-component Scry suites,
  and plan.md:170–172 references "Final verification's broader three-component
  Scry run", but plan.md defines no Final-verification slice/step (its slices stop
  at Slice 7 with only per-slice Scry guidance). Add a Final-verification entry to
  plan.md (full workflow-runtime + workflow-loader + workflow-step-materialization
  Scry run + AC-1..AC-8 coverage confirm) mirroring steps.md, so plan's "Final
  verification" references resolve to a defined plan step.

## Plan-review follow-ups (ambiguity, pass 3)

- [x] P9 — Define which component Scry suite(s) gate Slices 2–6 in plan.md
  (change_chain note) + steps.md (header): the per-slice "relevant Scry suite" is
  unnamed for Slices 2–6 even though the work spans multiple components (Slice 2
  edits both workflow-loader `compiler.clj` and workflow-runtime `ir.clj`; Slices
  3–6 also touch workflow-step-materialization + progression-recording). State, per
  slice or as a per-component rule, which Scry suite(s) must be green to gate each
  non-Slice-1 slice — distinct from Slice 1's focused `step_execution_test.clj`
  gate (P7) and Final verification's three-suite run.
- [x] P10 — Specify the **final/last-turn** error abort semantics in Slice 6,
  reconciled with AC-5/AC-2: Slice 6 covers only the "intermediate-turn error"
  path, leaving undefined whether a last-prompt turn error (N>1) and the N=1
  single-turn error follow the same `:failed`-naming-the-prompt abort (routing
  skipped, prior records retained) or the pre-existing single-prompt failure
  route. State the outcome for a final/last-turn error and for the N=1 degenerate.
- [x] P11 — Define "shared sources" in steps.md Slice 3 (and plan.md), since the
  design forbids any step-level shared `:contributions`/preamble/`:preload`:
  clarify that "first group loads shared sources" means the session's first-turn
  materialized conversation (first group body + session-level system/skill/tool
  content) persisting in the live child session for later groups — not a
  prohibited step-level shared preamble — so the wording and the
  no-step-level-preamble rule do not appear to contradict.

## Plan-review follow-ups (inconsistency, pass 3)

- [x] PI9 — Reconcile Slice 1's per-component Scry gate (P9) with the three
  components Slice 1 actually edits, and fix the `core.clj` slice mis-attribution.
  steps Slice 1 edits workflow-runtime (`ir.clj`/`step_execution.clj`),
  workflow-loader (`compiler.clj` single-prompt normalization), and
  workflow-step-materialization (`core.clj` per-group materialization entry
  point), but P9 scopes Slice 1's gate to the workflow-runtime
  `step_execution_test.clj` namespace only — leaving the `compiler.clj` and
  `core.clj` edits ungated by their own suites despite the per-component rule
  ("gate on every component the slice edits"). Plan P9 also lists "the per-group
  materialization in `core.clj`" among **Slice 3's** edits and gates
  workflow-step-materialization for Slice 3, though steps adds that `core.clj`
  entry point in **Slice 1** and Slice 3 has no `core.clj` item. Reconcile by
  either (a) attributing the `core.clj` (workflow-step-materialization) and
  `compiler.clj` (workflow-loader) edits + their suite gating to **Slice 1** and
  removing the `core.clj` attribution from Slice 3, or (b) explicitly justifying
  Slice 1's focused-namespace gate exemption and relocating the `core.clj`
  materialization edit to the slice P9 claims owns it.
- [x] PI10 — Reword the plan P9 Slice-3 "edits" enumeration to stop listing
  confirm-only / no-edit touch points as edits. plan P9 Slice 3 says it "edits
  `step_execution.clj`/`statechart_runtime.clj`/`progression_recording.clj`/`statechart.clj`",
  but the plan Touch points scope `statechart_runtime.clj` for Slice 3 to "needs
  **no** next-un-run-selection logic here; Slice 5 confirms …" and `statechart.clj`
  to a "**confirm** … topology … no per-prompt statechart states" verification,
  and steps Slice 3 has no `statechart_runtime.clj` item and only a confirm item
  for `statechart.clj`. Separate the genuinely-edited Slice-3 files
  (`step_execution.clj`, `progression_recording.clj`) from confirm-only touch
  points so "edits" names only files the slice changes.

## Plan-review follow-ups (ambiguity, pass 4)

- [x] P12 — Specify the **in-flight (mid-turn) cancellation** outcome and record
  disposition in Slice 6 (plan.md + steps.md), reconciled with AC-6/AC-5. AC-6
  covers only cancellation **between** prompts, but steps Slice 6 adds "in-flight
  turn aborted per existing cancellation contract", introducing the case where
  cancellation arrives while a prompt's turn is in flight. State (a) that a
  mid-turn cancellation yields the **same** terminal `:cancelled` outcome
  (routing skipped) as an inter-prompt cancellation, and (b) that the interrupted
  in-flight prompt leaves **no** completed turn record — symmetric with AC-5's
  explicit failing-prompt "leaves no record" rule — so only prompts completed
  before cancellation are retained + introspectable. Make the cancellation path's
  in-flight-record disposition as explicit as AC-5 makes the failure path's.

## Plan-review follow-ups (ambiguity, pass 5)

- [x] P13 — Specify the structured-output `:blocked` outcome across the
  multi-prompt drain (plan Slice 3/Slice 6 + steps Slice 6, reconciled with
  AC-3/AC-5). `execute-session-step!` has a third non-success outcome besides
  `:error`/`:failed` and `:cancelled`: `:actor/blocked`, raised for (i) an
  invalid structured-output **request** (`(:ok? request-result)` false, a static
  pre-turn check today), (ii) `:unsupported-structured-output` (resolved model
  cannot do structured output), and (iii) `:invalid-structured-output` (final
  reply fails validation). With "structured `:outputs` on the final turn only"
  (P5), state (a) **when** structured-output viability is checked in the drain —
  the upfront static request-validity / blocked check before turn 1 (fail-fast,
  no wasted turns) vs deferred to the final turn — and (b) the `:blocked`
  disposition when the **final** turn blocks after N−1 turns already ran: the
  step outcome (terminal `:blocked`, distinct from `:failed`/`:cancelled`),
  whether routing is skipped, and whether prior completed per-prompt records are
  retained + introspectable (symmetric with AC-5/AC-6). Add `:blocked` to Slice
  6's abort-path enumeration (currently only `:failed`/`:cancelled`) with a
  covering test.

## Plan-review follow-ups (inconsistency, pass 4)

- [x] PI11 — Reword plan.md P9 ("Per-slice Scry gating") **Slice 5** so it stops
  listing confirm-only / read-only files as the slice's gating work-files,
  matching steps Slice 5 and PI10's same-file resolution for Slice 3. plan P9
  Slice 5 reads "(resume/replay re-entry in `statechart_runtime.clj` +
  `progression_recording.clj`)" — framing both files like the "(edits …)"
  parentheticals of Slices 2/4/6 — but steps Slice 5 has **no** production-edit
  item (all "Confirm …"/runtime-test/"Verify …"/docs, with the boundary
  "unchanged from Slice 3"), plan Touch points scope `statechart_runtime.clj` as
  the confirm-only re-entry boundary needing **no** edit (PI7/PI10), and the
  `progression_recording.clj` per-prompt recording lands in **Slice 3**
  (read-only in Slice 5). So the same `statechart_runtime.clj` is "confirm-only,
  no edit" in plan P9 Slice 3 but a plain work-file in plan P9 Slice 5. Reconcile
  by marking `statechart_runtime.clj` as the confirm-only re-entry boundary and
  `progression_recording.clj` as read-only (recording added in Slice 3) in the
  Slice-5 enumeration, and — if Slice 5 edits no production code — state that its
  gate is the workflow-runtime suite for the added resume/replay tests.

## Plan-review follow-ups (inconsistency, pass 5)

- [x] PI12 — Give the P13 `:blocked` terminal outcome (Slice 6) a design AC and a
  Final-verification AC-1..AC-8 coverage home, or explicitly trace it under an
  existing AC. P13 added `:blocked` (`:actor/blocked`) as a **third** terminal
  non-success outcome (plan R6 + Slice 6; steps Slice 6 enumeration + dedicated
  `:blocked` runtime-test item + Slice 7 verify-list), but design.md still
  enumerates only `:failed` (AC-5) and `:cancelled` (AC-6) as queue non-success
  outcomes, and the Final-verification AC-1..AC-8 coverage list (plan.md:409,
  steps.md:276) still names only the original seven areas + docs — **no
  `:blocked` entry**. So the Slice 6 `:blocked` covering test traces to no AC and
  the AC-1..AC-8 TraceID/coherence gate would pass without verifying it (the same
  defect-shape PI9/PI10/PI11 fixed for other surfaces, re-introduced by P13).
  Resolve by **either** (a) adding a design AC for the `:blocked` outcome
  (routing skipped, prior records retained, blocking prompt leaves no record) —
  or extending AC-5 — **and** adding a `:blocked` entry to the Final-verification
  AC-1..AC-8 coverage enumeration in **both** plan.md and steps.md, **or** (b)
  stating explicitly in plan/steps that `:blocked` is preservation-only and its
  Slice 6 test is traced under an existing AC (e.g. AC-3 structured-output / AC-5
  abort), naming it in the Final-verification coverage parenthetical so the
  TraceID check accounts for it.

## Implementation-review follow-ups (pass 1)

- [x] R-1 — Reconcile design.md (source of truth) with the synchronous-drain
  reality. **DONE:** added a "Realized vs. target" implementation note to Intent,
  marked the F1 Architecture-alignment bullet "TARGET, not yet realized
  (synchronous drain)", and reworded AC-7 as a structural progression guard
  validated via reconstructed `state*` (not an occurring async restart). Captured
  the `post-drain-envelope` current-invocation-accumulator caveat as the explicit
  blocker for any future async F1. spec↔code coherence now holds; a future reader
  is told the async suspend/resume + process-restart resume is the target, while
  the realized guarantee is the per-iteration progression re-read.

- [x] R-2 — Close the later-group multi-message `:contributions` gap. **DONE via
  option (c)** (document + covering test). Chose (c) over (a)/(b): (a) IR
  validation cannot robustly reject a multi-message later group because the
  message count is only known at runtime materialization (sources can expand a
  single contribution into multiple messages) — a static guard would have false
  negatives; (b) re-injecting preloaded messages mid-session was deliberately
  deferred (Slice-3 deviation 3) and is a larger change than the gap warrants
  (nothing currently authors a multi-message later group; the common
  `:prompt-workflow` form is single-message). Extracted the inline `:step/enter`
  later-group lambda into a named, documented `step-execution/later-group-turn-prompt`
  (docstring states the single-submission limitation), wired the production path
  to it, documented the limitation in `doc/workflow-grammar.md`
  ("Later-group single-submission limitation"), and added two covering tests
  (`later-group-turn-prompt-single-message-test`,
  `later-group-turn-prompt-drops-multi-message-preload-test`) exercising the real
  `split-step-session-conversation`.

- [x] R-3 — Add `components/workflow-step-materialization/test` to the
  `:test-paths` alias (deps.edn). **DONE.** The focused scry runner is
  `clojure -M:test-paths -m scry.cli` (bb.edn:176), so the materialization tests
  (`core-test`/`source-resolution-test`) were off its classpath. Added the path;
  verified both namespaces now load and pass under the focused runner (25 tests /
  50 assertions). In-scope tooling fix for 226 (the task's own materialization
  tests).

## Implementation-review follow-ups (pass 2)

- [x] R-4 — Reconcile `doc/workflow-grammar.md` "Resume and idempotency" with the
  R-1-reconciled design. The section still presents async/process-restart/
  event-log-replay mid-drain re-entry as a realized runtime path ("On every
  re-entry of the step — an ordinary in-run advance, a process restart, or an
  event-log replay — the driver reads …"), but design.md (source of truth) marks
  the async suspend/resume + restart/replay resume as **TARGET, not yet realized**
  (synchronous drain; AC-7 validated against a reconstructed `state*`, not an
  occurring restart). Reword the doc to (a) describe the realized guarantee as the
  structural progression-reconstruction guard (position read from recorded
  per-prompt turn records, never an in-memory counter) and (b) qualify the
  process-restart/event-log-replay framing as the not-yet-realized async target
  rather than an occurring path — matching design.md so user docs do not assert a
  capability the reconciled spec marks unrealized. Also verify
  `doc/workflow-grammar-concepts.md` (*Per-prompt output surfaces*) carries no
  parallel overclaim.
  **DONE:** reworded the "Resume and idempotency" section — realized guarantee now
  stated as the structural progression guard (per-iteration re-read of recorded
  per-prompt turn records, never a counter; idempotency validated against a
  reconstructed queue state), and a *Realized vs. target* block demotes async
  turn-completion resume / process restart / event-log replay mid-drain re-entry
  to the not-yet-realized F1 target (synchronous drain today). Verified
  `doc/workflow-grammar-concepts.md` *Per-prompt output surfaces* carries no
  parallel restart/replay overclaim (no edit needed). Docs-only.

## Implementation-review follow-ups (pass 3)

- [x] R-5 — Reconcile the design's "one unified runtime path / not a separately
  maintained path / no drift" claim (AC-2 design.md:92-93; "drives one queue
  path" design.md:156; Architecture "no drift" design.md:219) with the two-driver
  reality: `statechart_runtime.clj` dispatches on `(some :name prompt-queue)` to
  `drive-session-prompt-queue!` (named) vs. `execute-session-step!` (unnamed N=1),
  which duplicate the disposition→`record-actor-pending!` control flow. Choose
  **either** (a) reword the design so the unification is at the turn-primitive
  level (`execute-session-turn-outcome`) and the N=1 degenerate uses a distinct
  thin driver because the unnamed group records no progression record (so it
  cannot drive the progression-based drain) — the R-1-style spec↔code
  reconciliation — **or** (b) unify so the N=1 unnamed case also flows through
  `drive-session-prompt-queue!`, removing the duplicated disposition handling.
  **DONE via option (a)** (see implementation.md "Implementation-review follow-up
  execution (pass 3)"). Rejected (b): routing N=1 through
  `drive-session-prompt-queue!` would break the AC-2 byte-identical envelope (the
  drain's `post-drain-envelope` wraps `:prompt-group-outputs`/accumulated
  `:transcript`) and is structurally blocked by the unnamed group recording no
  per-prompt progression record (C3). Reworded AC-2, Grammar step-level
  precedence, and the Architecture-alignment unified-path bullet to state the
  unification at the shared per-turn primitive (`execute-session-turn-outcome`),
  with two thin drivers differing only in disposition orchestration. Design-only
  edit; code already realizes the reconciled claim;
  `doc/workflow-grammar.md` already frames it at mechanism level (no overclaim).

## Implementation-review follow-ups (pass 4)

- [x] R-6 — Remove or reconcile the dead, divergent `execute-actor-step!`
  (`step_execution.clj:443`). It is never referenced anywhere in the repo, yet its
  `:else` session branch calls only `execute-session-step!` (single-turn N=1),
  with no awareness of the named multi-prompt `drive-session-prompt-queue!`
  dispatch that lives inline in `statechart_runtime.clj` (`(some :name
  prompt-queue)`). As a second, stale session-dispatch site it directly
  contradicts this task's "one path / no drift" thesis — if ever wired it would
  run `:prompts` steps as a single turn, dropping every later prompt. Resolve by
  **either** (a) deleting the unused `execute-actor-step!` (and its session
  branch), **or** (b) if a public step-dispatch entry point is wanted, routing its
  session branch through the same `(some :name prompt-queue)` dispatch the live
  `:step/enter` path uses, so the two cannot drift. Confirm no caller/test depends
  on it first (currently none).
  **DONE via option (a)** (deleted). Confirmed dead first: `grep` across all
  `.clj`/`.cljc`/`.cljs` (excluding stale `target/classes`) found only the
  definition — no caller, no test; `git log -S` showed it introduced unused by the
  component-extraction commit (#72). Chose (a) over (b): (b) would resurrect a
  public step-dispatch entry point nothing wants, re-creating the very second
  dispatch site whose drift R-6 flags (λone_way; `addition > modification` does
  not apply to dead code). The three helpers it called
  (`invoke-step-runtime-result`, `apply-invoke-step-result`,
  `execute-session-step!`) are still live via the inlined dispatch in
  `statechart_runtime.clj`, so deletion removes only the divergent wrapper. Single
  live session-dispatch site now remains (`statechart_runtime.clj` `:step/enter`
  `(some :name prompt-queue)`), upholding the "one path / no drift" thesis.
  `clj-kondo` clean; workflow-runtime suite 131/711 green.

## Implementation-review follow-ups (pass 5)

- [x] R-7 — Add a per-iteration pre-turn cancellation checkpoint to
  `drive-session-prompt-queue!` (`step_execution.clj`). The drain loop currently
  runs each prompt's full turn (`execute-session-turn-outcome` →
  `execute-actor-turn!`) before any `stopped?` check, so a cancellation arriving
  **between** prompts still fires the next prompt's `ai/generate` + tool loop to
  completion before the run stops — wasteful/side-effectful for a cancelled
  workflow and asymmetric with the N=1 `execute-session-step!` pre-turn `stopped?`
  check. Add a `(stopped?)` checkpoint at the **top of each loop iteration**
  (before `next-un-run-prompt-group` selection / turn-prompt construction / turn
  execution) that enqueues `:workflow/cancel` and exits, so the queue stops
  cleanly between prompts without firing an extra turn (cooperative-cancellation,
  225-lineage; realizes P12's between-prompts "queue stops"). Add a covering test:
  a cancellation observed between turns enqueues `:workflow/cancel` with **zero**
  additional turn-fn/`ai/generate` invocations and no post-drain
  `:pending-actor-result`; completed prior records retained.
  **DONE:** wrapped the drain `loop` body in a top-of-iteration `(if (stopped?)
  (enqueue :workflow/cancel) …)` checkpoint that runs **before**
  `next-un-run-prompt-group` selection and turn construction — symmetric with the
  N=1 `execute-session-step!` pre-turn check. Covering test
  `drive-session-prompt-queue-between-prompt-cancellation-checkpoint-test`
  (abort-test ns): a cancellation observable only after prompt 0's record is
  written (`stopped? = (seq (recorded-indices …))`) fires **exactly one** turn
  (without R-7 it would be 2 — prompt 1's turn runs before the old post-turn
  check), enqueues `:workflow/cancel`, leaves no `:pending-actor-result`, routing
  skipped, index-0 record retained. `clj-kondo` clean; workflow-runtime suite
  132 tests / 716 assertions green.

- [x] R-8 — Update the stale design.md `## Status` line ("Design complete; ready
  for planning") to reflect implementation-complete / under implementation review,
  matching plan.md's authoritative "Implementation complete" so a reader landing
  on design.md first is not misled. Low-severity coherence fix.
  **DONE:** `## Status` now reads "Implementation complete; under implementation
  review." (capability-only note preserved; 227-dependency note preserved).

## Test-review follow-ups (pass 1)

- [x] T-1 — Convert the Slice-1
  `single-prompt-session-step-envelope-characterization-test`
  (`step_execution_test.clj`) from `with-redefs` stubbing of
  `turn-execution/execute-actor-turn!` to injecting the nullable
  `:workflow-execute-actor-turn-fn` ctx seam (the same seam the
  `drive-session-prompt-queue!` tests use), so the task-introduced test obeys the
  `¬stub ∧ nullable` methodology. Keep the asserted envelope shape unchanged (it
  is the R4 done-gate comparand). Scope to the task-introduced characterization
  test only; the pre-existing `execute-session-step!` with-redefs tests are out
  of scope.
  **DONE:** both `testing` blocks now pass the turn outcome via the ctx seam
  `{:workflow-execute-actor-turn-fn (fn …)}` (first arg to `execute-session-step!`)
  instead of `with-redefs` on `turn-execution/execute-actor-turn!`. Asserted
  envelope shape unchanged — 14 tests / 113 assertions green in
  step-execution-test + ir-prompts-test. Pre-existing `execute-session-step!`
  with-redefs tests left untouched (out of scope); the `turn-execution` alias
  require remains (still used by those tests).
- [x] T-2 — Add a covering test for B1(b) (AC-3): a `:prompt` discriminator on a
  `:yield` ref (`{:step s :prompt p :yield k}`) is invalid. Assert it is rejected
  (structural-errors, since `:prompt` is absent from `step-yield-ref-schema`) in
  `ir_prompts_test/prompt-source-ref-validation-test`, completing the AC-3
  invalid-`:prompt` case coverage (every other invalid case is already tested).
  **DONE + code-correction (see implementation.md "Test-review follow-up
  execution (pass 1)"):** the Slice-4 deviation claimed this case was *already*
  structurally enforced, but `step-output-ref-schema`/`step-yield-ref-schema`
  were **open** malli maps, so a `{:step :prompt :yield}` ref slipped through
  `step-yield-ref-schema` (extra `:prompt` allowed) and was only rejected
  *semantically* as `:prompt-ref-non-text-surface`. Closed both ref map schemas
  (`{:closed true}`) to make the Slice-4 `unreachable > forbidden` claim true:
  the ref now fails `step-output-ref-schema` (no `:output`) **and** the closed
  `step-yield-ref-schema` (extra `:prompt`), so it is rejected structurally with
  empty `:semantic-errors`. Test
  `prompt-source-ref-validation-test` "a `:prompt` discriminator on a `:yield`
  ref is structurally rejected (B1(b))" asserts `false? :valid?` ∧
  `some? :structural-errors` ∧ `[] :semantic-errors`. Updated
  `doc/workflow-grammar-concepts.md` to state the structural rejection. Full
  workflow-runtime suite 132 tests / 719 assertions green; `clj-kondo` clean.

## Test-review follow-ups (pass 2)

- [x] TR-3 — Add a behaviour-coverage assertion for design C3 (AC-2/AC-3): the
  N=1 unnamed `:contributions` `execute-session-step!` envelope carries **no**
  `:prompt-group-outputs` (per-prompt records are named-`:prompts`-only). Add
  `(is (not (contains? outputs :prompt-group-outputs)))` to both `testing` blocks
  of `single-prompt-session-step-envelope-characterization-test`
  (`step_execution_test.clj`), or a focused test, so a refactor leaking
  per-prompt records into the degenerate envelope is caught. Currently the
  absence is asserted nowhere (only the IR-level no-`:name` derivation and the
  presence of step-level keys are pinned).
  **DONE:** added `(is (not (contains? … :prompt-group-outputs)))` to both the
  text block (over the bound `outputs`) and the structured block (over
  `(:outputs payload)`) of
  `single-prompt-session-step-envelope-characterization-test`. Test-only;
  full workflow-runtime suite 132 tests / 721 assertions green (+2). See
  implementation.md "Test-review follow-up execution (pass 2)".
- [x] TR-4 — Re-base the hand-rolled run/attempt `state*` fixtures in
  `step_execution_drive_prompt_queue_test.clj` +
  `step_execution_drive_prompt_queue_abort_test.clj` (`running-attempt-state*`,
  `recorded-turns-state*`) onto the canonical constructors (`create-run` +
  `append-attempt-to-run` + `start-latest-attempt`, as `progression_recording_test`
  / `base-state-with-run` already does), layering recorded `:prompt-group-turns`
  on top for the resume/replay cases. This couples the drive/abort tests to the
  real run/attempt shape the production `latest-attempt`-based readers navigate,
  so a canonical-shape change can't leave the tests green against a stale
  literal while production breaks.
  **DONE:** added two shared canonical builders
  (`canonical-running-run-state` = register + `create-run` +
  `append-attempt-to-run` + `start-latest-attempt`; `canonical-recorded-run-state`
  = the former plus per-prompt `records` recorded via `record-prompt-group-turn`)
  to the workflow-runtime test support ns `step_test_support.clj` rather than
  duplicating across both files (consistency / λone_way). Each file's thin
  `running-attempt-state*` / `recorded-turns-state*` wrappers now atom-wrap the
  shared builders; all call sites unchanged. `clj-kondo` clean; focused run
  34 tests / 190 assertions green; full workflow-runtime suite 132 / 721 green.
  See implementation.md "Test-review follow-up execution (pass 2)".

## Test-review follow-ups (pass 3)

- [x] TR-5 — Re-base the residual hand-rolled literal `state*` in
  `drive-session-prompt-queue-resume-skips-recorded-prompts-test`
  (`step_execution_drive_prompt_queue_test.clj`) onto the canonical
  `recorded-turns-state*` helper (→ `step-test-support/canonical-recorded-run-state`),
  the same TR-4 re-base the other drive/abort fixtures already received. The test
  inlines `(atom {:workflows … :prompt-group-turns [{:index 0 …}]})` instead of
  calling `recorded-turns-state*`; this call site escaped the TR-4 fix, so it can
  drift from the canonical run/attempt shape the production `latest-attempt`
  readers navigate (stale-literal-green while production breaks). Replace the
  literal with `(recorded-turns-state* run-id step-id [{:index 0 :name
  "architecture" :outputs {:final-llm-reply "prior"}}])`. Test-only; re-run the
  workflow-runtime drive-prompt-queue suite green.

## Test-review follow-ups (pass 4)

- [x] TR-6 — Add a `drive-session-prompt-queue!` covering test for the final-turn
  `:invalid-structured-output` block (P13 case iii; AC-3/AC-5). Through the
  multi-prompt drain, case (i) (upfront invalid request) and case (ii)
  (`:unsupported-structured-output`) are covered
  (`...-blocks-upfront-on-invalid-structured-request-test`,
  `...-final-turn-structured-output-blocked-test`), but case (iii) is covered only
  for the N=1 `execute-session-step!` path — no drive-queue test exercises it.
  This is structurally distinct: case (ii) hits the drain's `:blocked` arm as
  `{:disposition :blocked :branch :error}`, whereas case (iii) hits it as
  `{:disposition :blocked :branch :success}` (the `invalid-structured-output?`
  envelope), and the drain's `:blocked` handler branches on exactly that flag
  (`(if (and (= :success (:branch outcome)) (stopped?)) cancel record-blocked)`),
  so the `:branch :success` blocked path through the drain is unverified. Add an
  N>1 drive-queue test whose **final** turn returns a `:status :ok` reply that
  fails structured-output validation (`:branch :success` → `:outcome :blocked` /
  `:invalid-structured-output`): assert terminal `:blocked` with
  `(get-in pending [:payload :blocked :reason])` = `:invalid-structured-output`,
  routing skipped (`:actor/blocked`, no `:actor/done`), prior N−1 records
  retained, blocking final prompt leaves no record — symmetric with the case-(ii)
  `drive-session-prompt-queue-final-turn-structured-output-blocked-test`.
  Test-only; re-run the workflow-runtime drive-prompt-queue-abort suite green.
  **DONE:** added
  `drive-session-prompt-queue-final-turn-invalid-structured-output-blocked-test`
  (abort-test ns) — final turn returns `:status :ok` with `:structured-output
  nil` (⇒ `missing-ai-structured-output-result` ⇒ `:invalid-structured-output`,
  the `:branch :success` blocked path the drain's `:blocked` handler branches on,
  distinct from case (ii)'s `:branch :error`). Asserts terminal `:blocked` /
  `:reason :invalid-structured-output`, `:actor/blocked` (no `:actor/done`),
  index-0 record retained, blocking final prompt leaves no record. Test-only;
  `clj-kondo` clean; focused abort suite 6/29 green; full workflow-runtime suite
  133 tests / 726 assertions green. See implementation.md "Test-review follow-up
  execution (pass 4)".

## Test-review follow-ups (pass 5)

- [x] TR-7 — Add a compiler test for the `:prompts`-on-non-session-step rejection
  guard (`workflow-loader/compiler.clj:226`, `compile-prompts-step`). The guard
  rejects `:prompts` authored on a non-`:session` step with `` "`:prompts` is
  allowed only on `:session` steps" `` — the authoring-time enforcement that
  `:prompts` is `:session`-only (`λone_way`). The symmetric `:prompt-workflow`
  guard (`compiler.clj:156`) is tested (`compiler_test.clj:138`
  "prompt-workflow rejects non-session step usage") but the `:prompts` analog has
  no test, so a regression deleting/weakening the session guard would leave the
  suite green (the IR `:prompt-ref-non-session-step` test is a different path —
  the *ref* discriminator, not the authoring `:prompts` key). Mirror the existing
  `:prompt-workflow` non-session test in `compiler_test.clj`'s
  `compile-edn-prompts-step-test`: compile a workflow whose step carries
  `:prompts` with `:type :delegate` (or `:invoke`) and assert `error` =
  `` "`:prompts` is allowed only on `:session` steps" ``. Test-only; re-run the
  workflow-loader compiler suite green.

## Test-shaper follow-ups (pass 7)

- [x] TS-1 — Consolidate the duplicated multi-prompt drain test fixtures and lift
  the `drive!` keyword-arg helper into `psi.workflow-runtime.step-test-support`,
  then use it from **both** sibling drain namespaces
  (`step_execution_drive_prompt_queue_test.clj` +
  `step_execution_drive_prompt_queue_abort_test.clj`). Today: (1) the helpers
  `running-attempt-state*`, `recording-record-turn-fn`, and
  `assistant-text-message` are defined **verbatim in both files** (they escaped
  the TR-4 state*-literal consolidation into `step-test-support`); (2) only the
  abort file has the `drive!` helper that compresses the long positional
  `drive-session-prompt-queue!` call into a named-key map, while the non-abort
  file repeats that full positional call at all 7 sites — including the magic
  pre-split first-prompt literal `"PROMPT-architecture"` and the inline
  `(fn [group] (str "PROMPT-" (:name group)))` builder, with the
  first-string-equals-`(builder (first prompt-queue))` invariant left implicit.
  Move the three shared fixtures + `drive!` into `step-test-support`, delete the
  per-file duplicates, and rewrite the non-abort call sites to use `drive!`
  (named keys over argument position). Per test-shaper
  `consistent(fixtures) ∧ consistent(test_abstractions) ∧
  helpers_that_compress(ceremony)`. Test-only; no production change; re-run both
  workflow-runtime drive/abort suites green.
  **DONE:** lifted `assistant-text-message`, `running-attempt-state*`,
  `recorded-turns-state*`, `recording-record-turn-fn`, a shared `prompt-builder`,
  and the keyword-arg `drive!` into `step-test-support`. **Made the implicit
  invariant explicit:** the shared `drive!` derives the first group's pre-split
  prompt as `(prompt-builder (first prompt-queue))` instead of the magic
  `"PROMPT-architecture"` literal, so it works for both the `architecture`- and
  `gather`-headed queues (the abort `drive!` had hardcoded the literal — would not
  have served the gather-headed non-abort tests). Both drain namespaces now alias
  the shared helpers (`def ^:private … step-test-support/…`) and invoke the SUT via
  `drive!` named keys; deleted the per-file duplicate defs and the 6 positional
  call sites in the non-abort file; dropped the now-unused `step-execution` require
  from the abort file. `clj-kondo` clean; both drain suites 14 tests / 64
  assertions green; full workflow-runtime suite 133 tests / 726 assertions green.

- [x] TS-2 — Consolidate the canonical OK-turn-result builder shared by the two
  sibling drain test namespaces (test-shaper consistency + economy; a TS-1
  escapee). The per-turn success result map the drain seam returns —
  `{:status :ok :assistant-text (str "reply-" prompt) :execution-result nil
  :assistant-message (assistant-text-message (str "reply-" prompt))}` — is factored
  as a private `ok-turn` helper in
  `step_execution_drive_prompt_queue_abort_test.clj` (lines 16-21) but repeated
  **inline at 3 call sites** in `step_execution_drive_prompt_queue_test.clj`
  (lines 65, 124, 168). Same SUT, same result contract, two spellings across the
  siblings — the `consistent(test_abstractions)` divergence TS-1 closed for the
  other shared fixtures, with `ok-turn` left behind. Lift `ok-turn` into
  `psi.workflow-runtime.step-test-support` (the TS-1 home), have the abort file
  alias it (`def ^:private … step-test-support/ok-turn`) and drop its private def,
  and rewrite the 3 inline drive-file turn closures to return
  `(step-test-support/ok-turn prompt)` while preserving their per-test recording
  side effects (`swap! turn-calls*` / `swap! submitted*`). Leave the degenerate
  `(fn [& _] … {:status :ok})` zero-/no-turn stubs (lines 210, 288) untouched —
  they are intentionally minimal where no turn actually completes. Per test-shaper
  `consistent(test_abstractions) ∧ economical ∧ helpers_that_compress(ceremony)`.
  Test-only; no production change; re-run both workflow-runtime drive/abort suites
  green.
  **DONE:** lifted `ok-turn` into `step-test-support`; the abort file now aliases
  it (`def ^:private ok-turn step-test-support/ok-turn`) and dropped its private
  def; the 3 inline drive-file `execute-turn` closures now return
  `(step-test-support/ok-turn prompt)` keeping their `swap! turn-calls*` /
  `swap! submitted*` recording side effects. The degenerate `(fn [& _] …)` stubs
  and the final-turn structured-output closure (distinct `:execution-result`
  shape) left untouched; the `assistant-text-message` alias stays (still used by
  the structured-output closure). `clj-kondo` clean; both drain suites 14 tests /
  64 assertions green. See implementation.md "Test-shaper follow-up execution
  (pass 8 — TS-2)".

## Test-shaper follow-ups (pass 9)

- [x] TS-3 — Consolidate the divergent progression-record-reading idiom across
  the two sibling drain namespaces (TS-1/TS-2 lineage escapee). `_abort_test.clj`
  defines a private `recorded-indices` helper while
  `step_execution_drive_prompt_queue_test.clj` re-spells
  `(progression-recording/prompt-group-turn-records (get-in @state*
  (progression-recording/run-path run-id)) step-id)` **inline 3×** (in-order,
  reconstructs, replay tests). Lift a shared `prompt-group-records` reader (and
  `recorded-indices` = `(mapv :index (prompt-group-records …))`) into
  `psi.workflow-runtime.step-test-support` (the TS-1/TS-2 home), have
  `_abort_test` alias it (drop its private def), and replace the 3 inline reads in
  `_test`, so the substrate-read has one spelling
  (`consistent(test_abstractions) ∧ economical`). Test-only; no production change;
  re-run both drain suites green.
  **DONE:** lifted `prompt-group-records` + `recorded-indices` into
  `step-test-support` (over the existing `workflow-recording` alias). `_abort_test`
  now aliases `recorded-indices` and dropped both its private `recorded-indices`
  def **and** its now-unused `progression-recording` require. `_test` replaced
  **all 4** inline `prompt-group-turn-records` reads (the 3 named — in-order,
  reconstructs, replay — **plus** the blocks-upfront read the item undercounted,
  so the spelling is genuinely uniform) with
  `step-test-support/prompt-group-records`, and dropped its now-unused
  `progression-recording` require. `clj-kondo` clean; both drain suites green.

- [x] TS-4 — Reconcile the near-duplicate Slice-3 / Slice-5 resume tests
  (`economical ∧ minimal(redundant_tests)`). `drive-session-prompt-queue-resume-skips-recorded-prompts-test`
  (Slice 3) and `drive-session-prompt-queue-reconstructs-position-from-persisted-progression-test`
  (Slice 5) exercise the **identical** mechanism (`drive!` over a freshly-built
  `recorded-turns-state*`), and the Slice-5 test's assertions are a strict
  superset (prior-records-retained-verbatim + reaches `:actor/done`). Because the
  realized drain is synchronous (no in-memory loop state distinguishes "in-run"
  from "restart"; R-1/R-5), the live-vs-restart distinction is documentary, not
  behavioural. Either (a) give the in-run test a genuinely-in-run-only observable
  the restart test cannot assert, or (b) fold it into the restart test (one
  well-named AC-7 progression-skip test), so the two stop being near-duplicates.
  **DONE (option b):** chose folding because the synchronous drain admits **no**
  genuinely-in-run-only observable (option a would manufacture a distinction that
  does not exist behaviourally). Removed the Slice-3
  `drive-session-prompt-queue-resume-skips-recorded-prompts-test`; the Slice-5
  `drive-session-prompt-queue-reconstructs-position-from-persisted-progression-test`
  (whose assertions are a strict superset: prior records retained verbatim +
  reaches `:actor/done`) is now the single, well-named AC-7 progression-skip test.
  Updated the Slice-5 namespace comment to record the fold rationale and updated
  the AC-7 TraceID below. Both drain suites green (132/723 full workflow-runtime).

## Docs-review follow-ups (pass 1)

- [x] DOC-1 — Define the EBNF nonterminals `prompt-name` and `relative-md-path`
  introduced by the `prompt-group` production in `doc/workflow-grammar.md`
  (lines 43–44). Add them to the terminal-definition list (grammar.md:182–199)
  alongside `step-name ::= string` (e.g. `prompt-name ::= string`;
  `relative-md-path ::= string`), so the grammar reference has no dangling
  nonterminals.
  **DONE:** added `prompt-name ::= string` and `relative-md-path ::= string` to
  the terminal-definition list immediately after `step-name ::= string`
  (grammar.md:185–186). Both nonterminals are now referenced (`prompt-group`,
  `session-step`) and defined; no dangling nonterminals remain.
- [x] DOC-2 — Reconcile the `session-step` EBNF production
  (`doc/workflow-grammar.md`:35–40) with the new Step-level precedence prose
  (grammar.md:235) and the implementation: step-level `:prompt-workflow` is a
  valid single-prompt session form (xor `:prompts`, resolves to `:contributions`
  per `compiler.clj:153–165`), but the production shows only
  `(:contributions … | :prompts …)`. Add the step-level `:prompt-workflow`
  alternative to the production so the EBNF and the precedence rule agree.
  **DONE:** the `session-step` production now lists three alternatives —
  `(:contributions [contribution+] | :prompt-workflow relative-md-path |
  :prompts [prompt-group+])` — matching the Step-level precedence prose
  (`:contributions`/`:prompt-workflow` xor `:prompts`) and the compiler, where
  step-level `:prompt-workflow` is allowed only on `:session` steps and resolves
  to `:contributions` (`workflow_loader/compiler.clj` `compile-prompt-workflow-step`).

## Docs-review follow-ups (pass 2)

- [x] DOC-3 — Document the `:prompts` form in the `doc/workflow-ir.md`
  Session-step IR section (workflow-ir.md:132-158). That section currently shows
  only the `:session`+`:contributions` normalized shape and says
  "`:contributions` are ordered and preserved as authored", but the implemented
  feature normalizes both authoring forms at IR time into one internal
  prompt-queue representation (`:contributions` → one unnamed group, `:prompts` →
  named groups; driven by `drive-session-prompt-queue!`). Add a brief note (and/or
  cross-reference to `doc/workflow-grammar.md` *Multi-prompt session steps*) so the
  IR reference reflects that a session step's prompt source may also be a named
  `:prompts` queue, keeping the IR doc complete vs the shipped IR.
  **DONE:** added a "Prompt source: `:contributions` vs `:prompts`" subsection to
  the workflow-ir.md *Session semantics* section describing the IR-time
  normalization into one internal prompt-queue (`:contributions`/`:prompt-workflow`
  → single unnamed group; `:prompts` → ordered named-group queue drained by
  `drive-session-prompt-queue!`), mutual exclusivity, and a cross-reference to
  `doc/workflow-grammar.md` *Multi-prompt session steps (`:prompts`)*. Docs-only.

## Docs-review follow-ups (pass 3)

- [x] DOC-4 — Add the `:prompt` per-prompt discriminator form to the `source-ref`
  EBNF production in `doc/workflow-grammar.md` (lines 108–111). The production
  currently lists only `{:step step-name :output output-key}` and
  `{:step step-name :yield yield-field}`, but the new prose in the same doc
  ("the `:prompt` source-ref discriminator", grammar.md:287–288) and
  `doc/workflow-grammar-concepts.md` (`{:step … :prompt … :output …}` in the
  data-flow surface; `:prompt` as an optional discriminator on `{:step s :output k}`)
  treat `{:step :prompt :output}` as a legal source-ref. Add the alternative
  `{:step step-name :prompt prompt-name :output output-key}` to the production
  (`prompt-name` is already a defined terminal per DOC-1) so the formal grammar
  and the prose agree within the grammar reference.
  **DONE:** added the alternative
  `{:step step-name :prompt prompt-name :output output-key}` to the `source-ref`
  EBNF production in `doc/workflow-grammar.md` between the plain
  `{:step :output}` and `{:step :yield}` alternatives, so the formal grammar now
  matches the `:prompt` source-ref discriminator prose (grammar.md *Per-prompt
  output surfaces* cross-reference) and `workflow-grammar-concepts.md`'s
  `{:step … :prompt … :output …}` data-flow surface. `prompt-name` was already
  defined as a terminal (DOC-1). Docs-only; no dangling nonterminals.

## Code-shaper review follow-ups (pass 1)

- [x] CS-1 — Add dedicated `format-semantic-error` cases (in
  `ir_error_formatting.clj`) for the four session-prompt-queue error types
  emitted by `session-prompt-queue-errors` (`ir.clj`):
  `:session-contributions-and-prompts`, `:session-without-prompt-source`,
  `:unnamed-prompt-group`, `:duplicate-prompt-group-name`. They currently fall
  through to the raw `(raw: …)` fallback (`ir_error_formatting.clj:109`), unlike
  the actionable `:prompt-ref-*` messages added by the same task — inconsistent
  author-facing rendering on the workflow-load error string (`core.clj:42`).
  Render precise messages (duplicate-name case should name the duplicate
  group(s) and the step; both/neither and unnamed-group cases should state the
  xor/naming rule) and add a formatter-level assertion (the error-data is tested
  in `ir_prompts_test.clj:68-100`, but the formatted string is not).
  **DONE:** added four `format-semantic-error` cases before the raw fallback —
  `:session-contributions-and-prompts` / `:session-without-prompt-source` state
  the step name + the one-prompt-source xor rule; `:unnamed-prompt-group` states
  the naming rule; `:duplicate-prompt-group-name` names the step and renders the
  duplicate group(s) via `(pr-str (:duplicate-names err))`. Added four
  formatter-level tests to `compilation_error_format_test.clj` (each asserts the
  step name + constraint text + `:duplicate-names` rendering + **no** `(raw:`
  fallback). 20 tests / 83 assertions green.

- [x] CS-2 — Separate computation from flow control in
  `execute-session-turn-outcome` (`statechart_runtime/step_execution.clj`).
  Extract the `:else` (success) arm's pure OK-envelope computation
  (`surface-step-def`, `raw-outputs`, structured-result validity,
  `normalized-outputs`, `envelope`) into a behaviour-preserving pure helper
  (e.g. `session-turn-ok-envelope`), leaving the disposition `cond` as flow
  control only (`xor(computation, flow_control)`). Makes the OK-envelope shape
  independently testable on the design's named single turn-primitive.
  **DONE:** extracted the success arm verbatim into pure `session-turn-ok-envelope`
  (`step-def`/`execution-session`/`structured-entry` + the turn's
  `assistant-text`/`assistant-message`/`execution-result`/`structured-output`),
  returning the `:branch :success` disposition map. The
  `execute-session-turn-outcome` `cond` `:else` now only dispatches to it — the
  disposition `cond` is flow control only. Behaviour-preserving: step-execution +
  drive-prompt-queue (+ abort) suites 25 tests / 146 assertions green; full
  workflow-runtime suite 136 tests / 736 assertions green; `clj-kondo` clean.

## Code-shaper review follow-ups (pass 2)

- [x] CS-3 — Pass the cohesive turn result as one value, not four scattered
  positionals. `execute-session-turn-outcome`
  (`statechart_runtime/step_execution.clj:259`) destructures the turn result
  `{:keys [status assistant-text failure execution-result assistant-message
  structured-output]}` then re-passes four of those fields positionally to
  `session-turn-ok-envelope` (`:287-290`), whose 7-arg signature (`:198`)
  interleaves step config with four co-members of one value. `consistent(data_shapes)`
  + transposition risk (e.g. swapping `assistant-text`/`assistant-message`, no
  compiler guard). Pass the turn-result map (or a `{:keys ...}` param) so the turn
  fields travel as one named value and `session-turn-ok-envelope` destructures
  locally (arg count 4). Behaviour-preserving, pure helper; re-run
  step-execution + drive-prompt-queue (+ abort) suites and `clj-kondo`.
  **DONE:** `session-turn-ok-envelope` now takes the cohesive `turn-result` map
  (arg count 4: `step-def execution-session structured-entry turn-result`) and
  destructures `{:keys [assistant-text assistant-message execution-result
  structured-output]}` locally — the four turn co-members travel as one named
  value, removing the positional transposition risk.
  `execute-session-turn-outcome` binds the whole turn result as `turn-result`
  (destructuring only `status`/`failure`/`structured-output` for the disposition
  `cond` flow control) and passes `turn-result` straight through on the `:else`
  success arm. Behaviour-preserving, pure helper; `clj-kondo` clean;
  step-execution + drive-prompt-queue (+ abort) suites 25 tests / 146 assertions
  green; full workflow-runtime suite 136 tests / 736 assertions green.

- [x] CS-4 — Use consistent keyword-access idiom for the single-key map read.
  `execute-session-turn-outcome` (`statechart_runtime/step_execution.clj:276-277`)
  reads `(or (get-in structured-output [:reason]) (:reason failure))` — the two
  reads in the same `or` use different idioms for the same single-key access
  (`get-in` one-element path vs keyword access). `consistent(idioms)`. Replace
  `(get-in structured-output [:reason])` with `(:reason structured-output)` so
  both reads share the keyword-access idiom. Behaviour-identical, cosmetic;
  re-run `clj-kondo` + the step-execution suite.
  **DONE:** replaced `(get-in structured-output [:reason])` with
  `(:reason structured-output)` in the `:unsupported-structured-output` blocked
  arm; both reads in the `or` now share the keyword-access idiom. Cosmetic,
  behaviour-identical. `clj-kondo` clean; step-execution-test 12 tests /
  85 assertions green; drive-prompt-queue (+ abort) 13 tests / 61 assertions green.

## Code-shaper review follow-ups (pass 4)

- [x] CS-5 — Name the repeated cancellation-enqueue idiom. The cancel signal
  `(queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})` is
  written verbatim 10 times across `execute-session-step!` and
  `drive-session-prompt-queue!`
  (`statechart_runtime/step_execution.clj:310,327,335,341,406,431,449,460,466,475`)
  — one concept (enqueue the cancel signal, fixed `{}` data) as an unnamed
  repeated literal at every cancellation checkpoint, asymmetric with its sibling
  disposition which IS named once (`record-actor-pending!`). `consistent(idioms)`
  + `locally_comprehensible`: the cancel path should read as a named action and
  the `:workflow/cancel`/`{}` shape should have one point of change. Extract an
  `enqueue-cancel!` helper (in this ns, or `queue/enqueue-cancel!` if shared with
  the `:workflow/cancel` sites in `statechart_runtime.clj`) and call it at each
  checkpoint. Behaviour-preserving, mechanical; re-run `clj-kondo` + the
  step-execution + drive-prompt-queue (+ abort) suites.
  **DONE via `queue/enqueue-cancel!`** (shared location). The `:workflow/cancel`/
  `{}` idiom is repeated in **two** sibling namespaces — `step_execution.clj`
  (10×, the CS-5 checkpoints) **and** `statechart_runtime.clj` (13×) — so the
  named action was placed in their shared `statechart-runtime.queue` ns
  (`enqueue-cancel! [event-queue* working-memory*]`), giving the cancel shape one
  point of change for **both** sharers (the explicit "if shared with the
  `:workflow/cancel` sites in `statechart_runtime.clj`" path; a step-execution-
  local helper would have left the 13 statechart_runtime copies unnamed,
  defeating the single-point-of-change rationale). Replaced all 23 single-line
  `:workflow/cancel {}` enqueues across both files (exact-literal substitution);
  left untouched the distinct `record-actor-pending!` general
  `enqueue-event! … event {}` (step_execution.clj:178), the dynamic-event
  `enqueue-event!` dispatches (`statechart_runtime.clj` `(case pending-kind …)` /
  `(case (:action routing-result) …)` / `:actor/failed` / `(:event data)`), which
  carry non-`:workflow/cancel` events. Behaviour-preserving, mechanical;
  `clj-paren-repair` + `clj-kondo` clean; step-execution + drive-prompt-queue
  (+ abort) suites 25 tests / 146 assertions green; full workflow-runtime suite
  136 tests / 736 assertions green.
