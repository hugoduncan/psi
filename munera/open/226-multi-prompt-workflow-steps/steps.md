# 226 — Multi-prompt workflow steps — Steps

Checklist grouped by slice (see plan.md). Each item is independently executable
and verifiable. Tick with a sha/decision note as completed. Run `clj-kondo` +
`clj-paren-repair` after edits; run the relevant Scry suite per slice; commit per
slice.

## Slice 1 — Unified single-prompt queue path (N=1 degenerate)

- [ ] Characterize current single-prompt behaviour: add a **committed
  asserted-shape characterization test** (in/alongside
  `statechart_runtime/step_execution_test.clj`) pinning the single-prompt
  `execute-session-step!` `:pending-actor-result` envelope shape
  (`:final-llm-reply`/`:text`/`:transcript`/structured `:outputs` keys) — not a
  full-content golden snapshot. **Commit it first**, green against current
  (pre-refactor) code; this is the R4 equivalence-baseline comparand (P3).
- [ ] Define the internal normalized prompt-queue representation in `ir.clj`
  (ordered vector of prompt-groups; group has optional `:name`, body =
  materialized contributions/prompt-workflow). Single unnamed group for
  `:contributions`/`:prompt-workflow`.
- [ ] Normalize `:contributions`/`:prompt-workflow` session steps into a
  length-1 internal queue at **compile time in `compiler.clj`** (workflow-loader
  owns the authored-form → normalized-queue transform, P1; `ir.clj` owns only the
  normalized-queue schema/validation), preserving the existing canonical
  `:session :contributions` shape downstream.
- [ ] Add a per-group materialization entry point in
  `workflow_step_materialization/core.clj` (reuse
  `materialize-step-session-conversation` + `split-step-session-conversation`
  per group; length-1 queue yields today's single prompt).
- [ ] Refactor `execute-session-step!` to drive a length-1 internal queue
  producing the **identical** `:pending-actor-result` envelope (no behaviour
  change). Single suspend point unchanged.
- [ ] Slice-1 done-gate: the committed envelope characterization test (P3) is
  green **unchanged** after the unified-path refactor, **and** the **session-step
  suite** is green unchanged (AC-2 N=1 equivalence as a consequence of the
  unified path). **Session-step suite scope (P7):** the gating suite is the
  workflow-runtime `step_execution_test.clj` namespace
  (`psi.workflow-runtime.statechart-runtime.step-execution-test`) that houses the
  characterization test — **not** the broader three-component Scry run reserved
  for Final verification. Any change to the asserted envelope shape ⇒ defect.
- [ ] `clj-kondo` clean; commit Slice 1.

## Slice 2 — `:prompts` grammar + IR normalization (named groups)

- [ ] Add the `:prompts` session-step schema to `ir.clj`: ordered vector of named
  prompt-groups; each group `{:name … (:prompt-workflow XOR :contributions)}`.
- [ ] Add step-level precedence validation: `:contributions`/`:prompt-workflow`
  **xor** `:prompts` (both ⇒ IR error).
- [ ] Add `:prompts` validation: empty `:prompts` ⇒ error; one-element ⇒ valid
  (B3); duplicate prompt-group `:name` within a step ⇒ error, names may repeat
  across steps (B4).
- [ ] Add group-internal precedence validation: `:prompt-workflow` **xor**
  `:contributions` within a group; both ⇒ error, neither ⇒ error (E2).
- [ ] Compile `:prompts` in `compiler.clj`: each group resolves its
  `:prompt-workflow` (relative .md, existing path rules) or `:contributions` into
  the normalized internal queue; named groups carry `:name`.
- [ ] IR-validation tests: empty/one-element/duplicate-name/group-xor/step-xor
  cases (red→green).
- [ ] Docs (P2): `doc/workflow-grammar.md` — `:prompts` author form, step-level
  `:contributions`/`:prompt-workflow` xor `:prompts`, group-internal
  `:prompt-workflow` xor `:contributions`, name-uniqueness/empty/one-element rules.
- [ ] `clj-kondo` clean; commit Slice 2.

## Slice 3 — Sequential N-turn drain (in-run suspend/resume) + per-prompt records

Slice 3 builds the **in-run** suspend/resume drain mechanism (P4): because each
turn is an async `ai/generate` that suspends the run, advancing _n_ → _n+1_
re-enters the acting state and consults recorded progression to pick the next
un-run prompt. Slice 5 adds only the process-restart/replay resume case on top.

- [ ] Extend the queue driver in `execute-session-step!` to run prompt _n+1_
  after prompt _n_ completes, against the **same** child session id (first group
  loads shared sources; later groups rely on live-session memory, E1). On each
  re-entry of the acting state, select the next **un-run** prompt from recorded
  per-prompt progression (progression-driven, **not** an in-memory counter);
  never re-fire a completed turn within the live run.
- [ ] Add per-prompt turn-record recording in `progression_recording.clj` under
  the step's attempt (ordered, keyed by `:name` for named groups only; unnamed
  group records only the step-level rollup, C3).
- [ ] Emit **one** post-drain `:pending-actor-result` carrying the step-level
  rollup plus ordered per-prompt records for named groups (A3); reconcile with
  the existing `record-actor-result`/`record-step-result` path (one route, Q5).
- [ ] Request structured `:outputs` on the **final** turn only (R5/AC-3).
  Final-turn detection (P5) uses the **static IR queue position** — the group at
  the last index of the ordered normalized prompt-queue — derivable from the IR
  alone and **orthogonal** to the progression-driven next-un-run selection (the
  no-counter rule governs only *which un-run prompt runs next*, never *whether
  the selected group is last*). No in-memory counter is used for either.
- [ ] Runtime tests (Slice-3 independent acceptance, P4): in one **live** run, N
  turns execute in author order; assert the driver selects the next un-run prompt
  from recorded progression via a **progression-state probe** (not turn count) —
  the probe's concrete observable (P6) is the **recorded per-prompt turn-record
  set under the step's attempt, read back through `progression_recording.clj`**
  (the same substrate the driver consults): the test reads which prompts already
  have a recorded turn and asserts the next submission is the lowest-position
  un-run group; drain reached only after all turns; one post-drain result; each
  named turn record introspectable (S4); N=1 unnamed still rollup-only.
- [ ] Assert non-re-fire (P8) within the live drain: the **count of
  `ai/generate` effects emitted at the dispatch/effect boundary** (captured via
  the test effect seam) is exactly one per un-run prompt and **zero** for any
  prompt that already has a recorded turn; corroborate with **no second turn
  record / no progression mutation** for an already-recorded prompt.
- [ ] Confirm in `statechart.clj` that the single
  acting→(judging)→record-result step topology still holds with the N-turn drain
  (one statechart step, N internal turns); assert **no per-prompt statechart
  states** are introduced (plan Touch point `statechart.clj`).
- [ ] Docs (P2): `doc/workflow-grammar-concepts.md` — drain/route semantics and
  per-prompt turn records (named groups only).
- [ ] `clj-kondo` clean; commit Slice 3.

## Slice 4 — Per-prompt output surfaces + `:prompt` source-ref + validation

- [ ] Implement step-level surfaces (`:final-llm-reply` = last prompt;
  `:transcript` = accumulated) vs per-prompt turn-local surfaces
  (`:final-llm-reply`/`:transcript` = that group's turn slice, B2) in
  `step-output-surfaces`/`step-output-value`.
- [ ] Add the optional `:prompt` discriminator to `source-ref-schema` /
  `step-output-ref-schema` in `ir.clj` (key on `{:step s :output k}`).
- [ ] Resolve `{:step s :prompt p :output k}` uniformly across the shared
  substrate (invoke args, contributions source items, template vars, delegated
  context) via the existing source-resolution path — no per-call-site code.
- [ ] Extend `ref-errors` with `:prompt`-selector validation: invalid when target
  step is non-session, single-prompt, unknown group `p`, non-text key `k`
  (structured/`:result`), a `:yield` ref (B1 — `:prompt` is `:output`-only), or
  the **same step being assembled** (sibling-group ref, E3).
- [ ] Carve out the step's own **post-drain `:judge`** from the same-step invalid
  rule: its `{:step s :prompt p :output k}` refs are permitted (resolves after
  drain, all turns recorded, G2/AC-4).
- [ ] IR-validation tests for every invalid case + the post-drain-judge carve-out
  + back-compat (no-`:prompt` ref → step-level surface).
- [ ] Runtime test: a no-`:prompt` ref against a multi-prompt step hits the
  step-level surface; a `:prompt` ref hits the group's turn-local surface.
- [ ] Docs (P2): `doc/workflow-grammar-concepts.md` — per-prompt output surfaces,
  the `:prompt` source-ref discriminator + its validation rules + the post-drain
  judge carve-out.
- [ ] `clj-kondo` clean; commit Slice 4.

## Slice 5 — Resume-from-progression across process-restart/replay (F1)

Slice 3 already lands the in-run progression-driven drain; Slice 5 adds **only**
the process-restart / event-log-replay resume case and proves its idempotency
(P4). The suspend/resume boundary inside the single statechart step is unchanged
from Slice 3 — this slice exercises and hardens it across reconstructed state.

- [ ] Confirm the `statechart_runtime.clj` session (`:else`) branch resume path
  reconstructs queue position **purely** from persisted per-prompt progression on
  process-restart re-entry (no reliance on in-memory loop state surviving the
  restart); continue at the next **un-run** prompt; never re-submit a prompt whose
  turn record exists (no `ai/generate` re-fire).
- [ ] Runtime test (Slice-5 independent acceptance, P4): a mid-queue resume
  reconstructed from persisted progression runs only the un-run prompts
  (resume-from-progression idempotency, AC-7); completed turns not re-fired.
- [ ] Verify replay path: replaying the event log reproduces the same per-prompt
  records without re-firing completed `ai/generate` effects. Assert non-re-fire
  with the **same observable as Slice 3 (P8)**: the **count of `ai/generate`
  effects emitted at the dispatch/effect boundary** is **zero** for any prompt
  with an existing turn record across the reconstructed/replayed state;
  corroborate with no second turn record / no progression mutation for an
  already-recorded prompt.
- [ ] Docs (P2): `doc/workflow-grammar-concepts.md` — the resume-from-progression
  contract.
- [ ] `clj-kondo` clean; commit Slice 5.

## Slice 6 — Abort paths

- [ ] Intermediate-turn error ⇒ stop queue, step `:failed` with payload naming
  the failing prompt, routing skipped; prior per-prompt records retained, failing
  prompt leaves **no** record (AC-5/G1).
- [ ] Inter-prompt cancellation ⇒ terminal `:cancelled` (distinct from
  `:failed`), routing skipped, completed per-prompt records retained +
  introspectable, in-flight turn aborted per existing cancellation contract
  (AC-6/B5).
- [ ] Runtime tests: intermediate-failure abort + retained prior records;
  inter-prompt cancellation outcome + retained records; both skip routing.
- [ ] Docs (P2): `doc/workflow-grammar-concepts.md` — abort/cancellation outcomes
  across the queue (`:failed` vs `:cancelled`, retained records, routing skipped).
- [ ] `clj-kondo` clean; commit Slice 6.

## Slice 7 — Docs consolidation + changelog + coherence

Author-facing grammar docs are written **incrementally** in the slice that
introduces each surface (P2; see plan.md "Author-facing docs cadence"). Slice 7
consolidates them — it does not first-author them.

- [ ] Consolidation pass over the incrementally-written `doc/workflow-grammar.md`
  + `doc/workflow-grammar-concepts.md` content (Slices 2–6): cross-link sections,
  fill any gaps, verify the `:prompts` form / group-internal xor / per-prompt
  surfaces / `:prompt` source-ref + validation + post-drain-judge carve-out /
  drain-route / resume contract / abort + cancellation outcomes (`:failed` vs
  `:cancelled`, retained records, routing skipped) are all present and
  consistent.
- [ ] CHANGELOG `[Unreleased] Added`: multi-prompt `:session` step capability
  (ordered `:prompts` queue, per-prompt addressing).
- [ ] Verify coherence across meta/spec/tests/code/docs (TraceID for AC-1..AC-8).
- [ ] `clj-kondo` clean; commit Slice 7.

## Final verification

- [ ] Run the full workflow-runtime + workflow-loader + workflow-step-
  materialization Scry suites green.
- [ ] Confirm AC-1..AC-8 each have covering tests (ordering, drain, N=1
  equivalence, per-prompt addressing + validation, intermediate-failure abort,
  inter-prompt cancellation, resume-from-progression idempotency, docs).

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

- [ ] P9 — Define which component Scry suite(s) gate Slices 2–6 in plan.md
  (change_chain note) + steps.md (header): the per-slice "relevant Scry suite" is
  unnamed for Slices 2–6 even though the work spans multiple components (Slice 2
  edits both workflow-loader `compiler.clj` and workflow-runtime `ir.clj`; Slices
  3–6 also touch workflow-step-materialization + progression-recording). State, per
  slice or as a per-component rule, which Scry suite(s) must be green to gate each
  non-Slice-1 slice — distinct from Slice 1's focused `step_execution_test.clj`
  gate (P7) and Final verification's three-suite run.
- [ ] P10 — Specify the **final/last-turn** error abort semantics in Slice 6,
  reconciled with AC-5/AC-2: Slice 6 covers only the "intermediate-turn error"
  path, leaving undefined whether a last-prompt turn error (N>1) and the N=1
  single-turn error follow the same `:failed`-naming-the-prompt abort (routing
  skipped, prior records retained) or the pre-existing single-prompt failure
  route. State the outcome for a final/last-turn error and for the N=1 degenerate.
- [ ] P11 — Define "shared sources" in steps.md Slice 3 (and plan.md), since the
  design forbids any step-level shared `:contributions`/preamble/`:preload`:
  clarify that "first group loads shared sources" means the session's first-turn
  materialized conversation (first group body + session-level system/skill/tool
  content) persisting in the live child session for later groups — not a
  prohibited step-level shared preamble — so the wording and the
  no-step-level-preamble rule do not appear to contradict.
