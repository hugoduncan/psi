# 226 — Multi-prompt workflow steps — Plan

## Approach

Add an ordered **prompt-queue** to `:session` steps. Both authoring forms
(`:contributions`/`:prompt-workflow` and the new `:prompts`) normalize at IR time
into **one** internal prompt-queue representation; the runtime drives a single
queue path, and single-prompt is the genuine N=1 degenerate (no second path).
The turn primitive (`execute-actor-turn!` → `execute-session-turn!` against a
persistent child session id) is unchanged; multi-prompt only loops it N times
against the same session and shapes IR/materialization/recording/routing around
that loop.

The change decomposes into vertical slices, each shippable and test-backed,
ordered so the unified single-prompt path lands first (proving N=1 equivalence)
before N>1, addressing, resume, and abort paths are layered on.

### Key decisions (from design.md, already resolved)

- **One unified queue path** (D2): `:contributions` → one **unnamed** group;
  `:prompts` → ordered **named** groups. Single-prompt = N=1 degenerate. No
  back-compat path; behaviour preservation is a *consequence*.
- **Normalization ownership (P1) — single owner per the workflow-runtime
  boundary.** The authored-form → normalized-prompt-queue **transform** is owned
  by the **workflow-loader** (`compiler.clj`): it resolves each authored prompt
  body (`:prompt-workflow` relative `.md` via the existing path rules, or inline
  `:contributions`) into a canonical prompt-group and emits the ordered internal
  prompt-queue (`:contributions`/`:prompt-workflow` → one unnamed group;
  `:prompts` → named groups). The **workflow-runtime** (`ir.clj`) owns only the
  **schema** of that normalized prompt-queue, its semantic validation, and the
  output surfaces — it consumes normalized IR and does **not** transform authored
  forms. This matches the existing split (the compiler already lowers
  `:prompt-workflow` and markdown bodies into canonical `:contributions`; `ir.clj`
  defines/validates the normalized schema) and `λ workflow_runtime_boundary`
  (loader compiles authored policy → IR; runtime is generic mechanism over
  normalized IR). `λone_way`: one owner for the transform — no "compiler +/or
  IR".
- **Turn boundary unchanged**: each group reuses
  `materialize-step-session-conversation` + `split-step-session-conversation`;
  the first group loads shared sources on turn 1, later groups run against the
  same live child session (E1 — no `:preload` field).
- **Per-prompt records in the canonical progression substrate** (A3/F1): the
  step emits **one** post-drain `:pending-actor-result` carrying the step-level
  rollup plus, for named groups only, ordered per-prompt turn records keyed by
  `:name`. The unnamed group contributes only the step-level rollup (C3).
- **Resume-from-progression** (F1): `ai/generate` is an async effect that
  suspends the run; N prompts = N suspend points **inside one statechart step**.
  On resume the queue-driving loop continues at the next un-run prompt from
  recorded per-prompt progression; a prompt with an existing turn record is never
  re-submitted (no `ai/generate` re-fire). The post-drain route (Q5) is reached
  only after every prompt has a recorded turn.
- **Output surfaces** (AC-3): step-level `:final-llm-reply` = last prompt;
  step-level `:transcript` = accumulated across turns; per-prompt
  `:final-llm-reply`/`:transcript` are turn-local, addressed via
  `{:step s :prompt p :output k}`. Step-level structured `:outputs` binds the
  **final** turn (unchanged granularity). The step's yielded value is unchanged
  (text from step-level `:final-llm-reply`); `:prompt` is `:output`-only, never
  `:yield`.
- **Source-ref `:prompt` discriminator** (A2/E3/G2): optional `:prompt` key on
  the canonical `{:step s :output k}` ref, resolved uniformly on the shared
  substrate. Compile-time IR validation rejects `:prompt` when the target is
  non-session, single-prompt, an unknown group, a non-text key, a `:yield` ref,
  or the **same step being assembled** (sibling-group ref) — except the step's
  own **post-drain `:judge`**, which is carved out (resolves after the drain).
- **Abort paths**: intermediate error ⇒ `:failed`, naming the failing prompt,
  routing skipped, prior per-prompt records retained, failing prompt leaves no
  record (AC-5/G1). Cancellation between prompts ⇒ terminal `:cancelled`, routing
  skipped, completed records retained (AC-6/B5).

### Touch points (grounded in current code)

- `components/workflow-runtime/src/psi/workflow_runtime/ir.clj` — **schema +
  validation + surfaces only** (consumes normalized IR; does not transform
  authored forms, P1): the normalized prompt-queue session-step **schema**
  (`:prompts` shape, internal queue shape), source-ref schema (`:prompt` key),
  `semantic-errors`/`ref-errors` validation,
  `step-output-surfaces`/`step-output-value` (per-prompt surfaces).
- `components/workflow-loader/src/psi/workflow_loader/compiler.clj` — **owns the
  authored-form → normalized-queue transform** (P1): compile `:prompts` (each
  group `:prompt-workflow` xor `:contributions`) into the normalized internal
  queue, and lower the existing `:prompt-workflow` / markdown-session /
  `:contributions` forms into the unnamed-group degenerate.
- `components/workflow-step-materialization/src/psi/workflow_step_materialization/core.clj`
  — materialize/split **per prompt-group** (the turn primitive is reused per
  group, not once per step).
- `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime/step_execution.clj`
  — drive the queue: per-turn `execute-actor-turn!`, record per-prompt result,
  emit one post-drain `:pending-actor-result`; abort on error/cancel.
- `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime.clj`
  — `:else` (session) branch: resume-from-progression (consult recorded
  per-prompt progression, continue at next un-run prompt), suspend/resume inside
  the one statechart step.
- `components/workflow-runtime/src/psi/workflow_runtime/progression_recording.clj`
  — record/read per-prompt turn records under the step's attempt.
- `components/workflow-runtime/src/psi/workflow_runtime/statechart.clj` — confirm
  the single acting→(judging)→record-result step topology still holds (one step,
  N internal turns); no per-prompt statechart states.
- `doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md` — author-facing
  `:prompts` form, per-prompt addressing, `:prompt` source-ref, drain/route.

## Risks

- **R1 — Resume/suspend boundary inside one statechart step (F1).** The hardest
  part: locating the queue-driving loop relative to the async `ai/generate`
  suspend so resume re-enters the step and consults progression rather than
  restarting the queue. Mitigation: land the unified single-prompt path first
  (Slice 1) to keep the existing single-suspend behaviour byte-identical, then
  add N>1 with explicit progression-driven re-entry **in Slice 3** (the in-run
  suspend/resume drain), with **Slice 5** adding only the process-restart/replay
  resume case (P4), backed by a mid-queue resume test. If the statechart-runtime
  suspend boundary cannot host
  an internal loop cleanly, fall back to recording progression and re-entering
  the same acting state per turn — still one logical step, N attempts-internal
  turns — and reconcile with the "one attempt / one route" invariant.
- **R2 — Per-prompt records vs the single `:pending-actor-result` envelope.**
  Recording N turn records while emitting one post-drain result must not break
  the existing `record-actor-result`/`record-step-result` contract or judged-step
  routing. Mitigation: keep the envelope shape additive (step-level rollup
  unchanged; per-prompt records nested under a new key only for named groups).
- **R3 — Source-ref `:prompt` validation completeness.** The `:prompt`
  discriminator threads through invoke args, contributions, template vars, and
  delegated context uniformly; missing a call site leaves an unvalidated path.
  Mitigation: extend the single shared `source-ref-schema` + `ref-errors` so all
  call sites inherit validation; add the post-drain-judge carve-out at the one
  judge-ref site.
- **R4 — N=1 equivalence regressions.** Routing single-prompt through the new
  queue must behave exactly as today. Mitigation: Slice 1 is explicitly the
  unified path with the full existing session-step test suite green before any
  N>1 work. **Equivalence baseline (P3):** the comparand for "treat any diff as a
  defect" is a committed **asserted-shape characterization test** (not a brittle
  full-content golden snapshot) that pins the single-prompt `execute-session-step!`
  `:pending-actor-result` envelope — the presence/shape of
  `:final-llm-reply`/`:text`/`:transcript` and the structured `:outputs` keys for
  a representative single-prompt session step. The test is added to (or alongside)
  `statechart_runtime/step_execution_test.clj` and **committed first**, passing
  against the current (pre-refactor) code; the unified-path refactor must keep it
  green unchanged. Any required change to the asserted envelope shape is a defect.
  This test — not merely "suite green unchanged" — is the Slice-1 done-gate
  comparand.
- **R5 — Structured output on the final turn only.** The existing single
  `:outputs` entry must bind the last turn, not every turn. Mitigation: request
  structured output only on the final group's turn in the queue driver.
  **Final-turn detection (P5).** "Final turn" is a **static IR/position
  property** — the last group in the ordered normalized IR prompt-queue
  (`= last index`), derivable from the IR alone. It is **orthogonal to** the
  progression-driven *selection* of the next un-run prompt: the no-counter rule
  governs only *which un-run prompt runs next* (read from recorded progression,
  never an in-memory counter), whereas *whether the selected group is the last
  one* is decided by comparing the selected group's static queue position to the
  queue length. Final-turn detection therefore needs no counter and does not
  violate the no-counter rule; structured `:outputs` is requested only when the
  selected group is the last position in the IR queue.

## Slice order

1. **Unified single-prompt queue path (N=1 degenerate).** The compiler
   (workflow-loader `compiler.clj`) normalizes
   `:contributions`/`:prompt-workflow` → one unnamed prompt-group (P1); `ir.clj`
   (workflow-runtime) owns only the normalized-queue schema/validation/surfaces.
   Materialization and `execute-session-step!` drive a length-1 internal queue
   producing the existing envelope. No grammar surface change. Acceptance: the
   committed asserted-shape envelope characterization test (P3/R4) green
   **unchanged** is the Slice-1 done-gate comparand — not merely "suite green
   unchanged" — **and** the **session-step suite** green (AC-2). **Session-step
   suite scope (P7):** the Slice-1 done-gate "session-step suite" is the
   workflow-runtime `step_execution_test.clj` namespace
   (`psi.workflow-runtime.statechart-runtime.step-execution-test`) — the
   session-step execution tests that house the characterization test — which must
   be green-unchanged to gate Slice 1. This is **distinct from** Final
   verification's broader three-component Scry run (workflow-runtime +
   workflow-loader + workflow-step-materialization); the Slice-1 gate is the
   focused session-step namespace, not the full three-suite run.
2. **`:prompts` grammar + IR normalization (named groups).** Add `:prompts`
   schema (ordered named groups; group-internal `:prompt-workflow` xor
   `:contributions`), compiler support, and IR validation: step-level
   `:contributions`/`:prompt-workflow` **xor** `:prompts` (both ⇒ IR error),
   empty `:prompts` error, one-element valid, duplicate group-name error, group
   xor error (B3/B4/E2). Normalize to the same internal queue; named groups carry
   `:name`.
3. **Sequential N-turn drain (in-run suspend/resume) + per-prompt records.**
   Because each turn is an async `ai/generate` that suspends the run (design F1:
   N prompts = N suspend points inside one statechart step), advancing prompt _n_
   → _n+1_ **already requires** an in-run suspend/resume re-entry that consults
   recorded progression to pick the next un-run prompt. **Slice 3 therefore builds
   that in-run suspend/resume drain mechanism**: the queue driver, on each
   re-entry of the acting state, reads recorded per-prompt progression and submits
   the next **un-run** prompt (driven by progression, not an in-memory counter),
   never re-firing a completed turn within the live run; records each named
   group's turn result in the progression substrate; emits one post-drain
   `:pending-actor-result` with step-level rollup + per-prompt records (A3/C3).
   Slice 5 then adds **only** the process-restart/replay resume case on top of
   this mechanism (P4). The driver requests structured `:outputs` only on the
   group at the **last static position** in the ordered IR queue (P5 final-turn
   detection — an IR/position property, orthogonal to the no-counter
   next-un-run selection). Acceptance (independently testable): in one live run, N
   turns execute in author order, the driver selects the next un-run prompt from
   recorded progression (assert via a **progression-state probe** — the concrete
   observable is the recorded per-prompt turn-record set under the step's attempt
   read back through `progression_recording.clj` (the same substrate the driver
   consults to pick the next un-run prompt), **not** a turn count: the test reads
   which prompts already have a recorded turn and asserts the next submission is
   the lowest-position un-run group), drain reached only after all turns, one
   post-drain result, named-turn introspectability (AC-1, parts of AC-3).
4. **Per-prompt output surfaces + `:prompt` source-ref + validation.** Step-level
   vs per-prompt `:final-llm-reply`/`:transcript` (B2); `:prompt` discriminator
   on the shared source-ref schema; uniform resolution; compile-time validation
   for all invalid cases incl. same-step sibling refs (E3) and the post-drain
   judge carve-out (G2); `:prompt` confined to `:output` (B1). Acceptance:
   per-prompt addressing + its validation (AC-3, AC-4).
5. **Resume-from-progression across process-restart/replay (F1).** Slice 3
   already lands the in-run progression-driven drain; **Slice 5 adds only the
   process-restart / event-log-replay resume case** and proves its idempotency
   (P4): a resume that re-enters the step from persisted progression after a
   process restart, and a replay of the event log, both continue at the next
   un-run prompt and never re-fire a completed turn's `ai/generate`; post-drain
   route reached only after all turns recorded. Acceptance (independently
   testable, distinct from Slice 3's live-run acceptance): a mid-queue resume
   reconstructed from persisted progression / replayed event log runs only the
   un-run prompts and reproduces the same per-prompt records with zero re-fired
   `ai/generate` effects (AC-7). **Non-re-fire observable (P8):** the primary
   measurement for "zero re-fire" — shared by Slice 3 (live drain) and Slice 5
   (restart/replay resume) — is the **count of `ai/generate` effects emitted at
   the dispatch/effect boundary**, captured through the test effect seam:
   exactly one emission per un-run prompt and **zero** emissions for any prompt
   that already has a recorded turn. The corroborating progression observable is
   that **no second turn record is written** (no progression mutation) for an
   already-recorded prompt. Both slices assert the same emitted-effect-count
   observable; Slice 5 additionally asserts it across a reconstructed/replayed
   state.
6. **Abort paths.** Intermediate-turn error ⇒ `:failed` naming the failing
   prompt, routing skipped, prior records retained, no failing record (AC-5/G1);
   inter-prompt cancellation ⇒ terminal `:cancelled`, routing skipped, completed
   records retained (AC-6/B5).
7. **Docs consolidation + changelog + coherence.** Final coherence pass over the
   incrementally-written author-facing docs (cross-link, fill gaps), the CHANGELOG
   `[Unreleased] Added` entry (user-visible grammar capability), and the
   AC-1..AC-8 TraceID coverage check. **Not** the first place docs appear (see the
   docs-cadence note below).

**Author-facing docs cadence (P2) — incremental per slice.** Author-facing
grammar docs (`doc/workflow-grammar*.md`) are updated in the slice that
introduces each author-visible surface, per the change_chain "update spec per
change" and `coherence` (artifacts stay consistent at all times). Slice 7 is a
final consolidation/coherence + changelog slice, **not** the sole doc-authoring
slice. Per-slice doc ownership:

- **Slice 1** — *no author-facing grammar-doc edit*: the unification is internal
  (N=1 unified queue path); there is no new author-visible surface. The Slice-1
  "spec" updated by the change_chain is the equivalence characterization test
  (P3), not author docs.
- **Slice 2** — `doc/workflow-grammar.md`: the `:prompts` author form, step-level
  `:contributions`/`:prompt-workflow` xor `:prompts`, group-internal
  `:prompt-workflow` xor `:contributions`, name-uniqueness/empty/one-element
  rules.
- **Slice 3** — `doc/workflow-grammar-concepts.md`: drain/route semantics and
  per-prompt turn records (named-only).
- **Slice 4** — `doc/workflow-grammar-concepts.md`: per-prompt output surfaces,
  the `:prompt` source-ref discriminator + its validation rules + the post-drain
  judge carve-out.
- **Slice 5** — `doc/workflow-grammar-concepts.md`: the resume-from-progression
  contract.
- **Slice 6** — `doc/workflow-grammar-concepts.md`: abort/cancellation outcomes
  across the queue.
- **Slice 7** — consolidation + cross-linking, CHANGELOG entry, TraceID coherence
  check (no new surface introduced here).

Each slice follows the change_chain: update spec (author-facing grammar docs
**for the surface that slice introduces**, per the cadence above) → tests
(IR-validation + runtime) → code → review/simplify → docs → verify coherence.
Lint (`clj-kondo`) + `clj-paren-repair` after edits; run the relevant Scry suites
per slice; commit per slice.
