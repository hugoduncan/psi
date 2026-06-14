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
before N>1, addressing, abort paths, and resume are layered on.

### Key decisions (from design.md, already resolved)

- **One unified queue path** (D2): IR normalizes `:contributions` → one
  **unnamed** group; `:prompts` → ordered **named** groups. Single-prompt = N=1
  degenerate. No back-compat path; behaviour preservation is a *consequence*.
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

- `components/workflow-runtime/src/psi/workflow_runtime/ir.clj` — session-step
  schema (`:prompts` shape, internal normalized queue), source-ref schema
  (`:prompt` key), `semantic-errors`/`ref-errors` validation,
  `step-output-surfaces`/`step-output-value` (per-prompt surfaces).
- `components/workflow-loader/src/psi/workflow_loader/compiler.clj` —
  compile `:prompts` (each group `:prompt-workflow` xor `:contributions`) into
  the normalized internal queue; extend the existing `:prompt-workflow` /
  markdown-session compilation to the unnamed-group degenerate.
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
  add N>1 with explicit progression-driven re-entry (Slice 5) backed by a
  mid-queue resume test. If the statechart-runtime suspend boundary cannot host
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
  N>1 work; treat any diff as a defect.
- **R5 — Structured output on the final turn only.** The existing single
  `:outputs` entry must bind the last turn, not every turn. Mitigation: request
  structured output only on the final group's turn in the queue driver.

## Slice order

1. **Unified single-prompt queue path (N=1 degenerate).** IR normalizes
   `:contributions`/`:prompt-workflow` → one unnamed prompt-group; materialization
   and `execute-session-step!` drive a length-1 internal queue producing the
   existing envelope. No grammar surface change. Acceptance: full existing
   session-step suite green (AC-2).
2. **`:prompts` grammar + IR normalization (named groups).** Add `:prompts`
   schema (ordered named groups; group-internal `:prompt-workflow` xor
   `:contributions`), compiler support, and IR validation: empty `:prompts`
   error, one-element valid, duplicate group-name error, group xor error (B3/B4/
   E2). Normalize to the same internal queue; named groups carry `:name`.
3. **Sequential N-turn drain + per-prompt records.** Queue driver runs prompt
   _n+1_ after _n_ in the same session; records each named group's turn result in
   the progression substrate; emits one post-drain `:pending-actor-result` with
   step-level rollup + per-prompt records (A3/C3). Acceptance: ordering, drain,
   N-turn introspectability (AC-1, parts of AC-3).
4. **Per-prompt output surfaces + `:prompt` source-ref + validation.** Step-level
   vs per-prompt `:final-llm-reply`/`:transcript` (B2); `:prompt` discriminator
   on the shared source-ref schema; uniform resolution; compile-time validation
   for all invalid cases incl. same-step sibling refs (E3) and the post-drain
   judge carve-out (G2); `:prompt` confined to `:output` (B1). Acceptance:
   per-prompt addressing + its validation (AC-3, AC-4).
5. **Resume-from-progression (F1).** Queue-driving loop consults recorded
   per-prompt progression on resume and continues at the next un-run prompt; no
   re-fire of completed turns; post-drain route reached only after all turns
   recorded. Acceptance: mid-queue resume runs only un-run prompts; resume
   idempotency (AC-7).
6. **Abort paths.** Intermediate-turn error ⇒ `:failed` naming the failing
   prompt, routing skipped, prior records retained, no failing record (AC-5/G1);
   inter-prompt cancellation ⇒ terminal `:cancelled`, routing skipped, completed
   records retained (AC-6/B5).
7. **Docs.** `doc/workflow-grammar.md` + `-concepts.md`: `:prompts` form,
   group-internal xor, per-prompt surfaces, `:prompt` source-ref + validation,
   drain/route, resume contract (AC-8). Changelog entry (user-visible grammar
   capability).

Each slice follows the change_chain: update spec (grammar docs/examples as
needed) → tests (IR-validation + runtime) → code → review/simplify → docs →
verify coherence. Lint (`clj-kondo`) + `clj-paren-repair` after edits; run the
relevant Scry suites per slice; commit per slice.
