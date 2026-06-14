# 226 — Multi-prompt workflow steps — Steps

Checklist grouped by slice (see plan.md). Each item is independently executable
and verifiable. Tick with a sha/decision note as completed. Run `clj-kondo` +
`clj-paren-repair` after edits; run the relevant Scry suite per slice; commit per
slice.

## Slice 1 — Unified single-prompt queue path (N=1 degenerate)

- [ ] Characterize current single-prompt behaviour: capture the existing
  `execute-session-step!` envelope shape (`:final-llm-reply`/`:text`/
  `:transcript`/structured `:outputs`) under a green run of
  `statechart_runtime/step_execution_test.clj` as the equivalence baseline.
- [ ] Define the internal normalized prompt-queue representation in `ir.clj`
  (ordered vector of prompt-groups; group has optional `:name`, body =
  materialized contributions/prompt-workflow). Single unnamed group for
  `:contributions`/`:prompt-workflow`.
- [ ] Normalize `:contributions`/`:prompt-workflow` session steps into a
  length-1 internal queue at IR/compile time (`compiler.clj` +/or `ir.clj`),
  preserving the existing canonical `:session :contributions` shape downstream.
- [ ] Add a per-group materialization entry point in
  `workflow_step_materialization/core.clj` (reuse
  `materialize-step-session-conversation` + `split-step-session-conversation`
  per group; length-1 queue yields today's single prompt).
- [ ] Refactor `execute-session-step!` to drive a length-1 internal queue
  producing the **identical** `:pending-actor-result` envelope (no behaviour
  change). Single suspend point unchanged.
- [ ] Verify the full existing session-step suite is green unchanged (AC-2 N=1
  equivalence as a consequence of the unified path).
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
- [ ] `clj-kondo` clean; commit Slice 2.

## Slice 3 — Sequential N-turn drain + per-prompt records

- [ ] Extend the queue driver in `execute-session-step!` to run prompt _n+1_
  after prompt _n_ completes, against the **same** child session id (first group
  loads shared sources; later groups rely on live-session memory, E1).
- [ ] Add per-prompt turn-record recording in `progression_recording.clj` under
  the step's attempt (ordered, keyed by `:name` for named groups only; unnamed
  group records only the step-level rollup, C3).
- [ ] Emit **one** post-drain `:pending-actor-result` carrying the step-level
  rollup plus ordered per-prompt records for named groups (A3); reconcile with
  the existing `record-actor-result`/`record-step-result` path (one route, Q5).
- [ ] Request structured `:outputs` on the **final** turn only (R5/AC-3).
- [ ] Runtime tests: author order respected; drain reached only after all turns;
  each named turn record introspectable (S4); N=1 unnamed still rollup-only.
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
- [ ] `clj-kondo` clean; commit Slice 4.

## Slice 5 — Resume-from-progression (F1)

- [ ] In the `statechart_runtime.clj` session (`:else`) branch, on (re-)entry to
  the acting state consult recorded per-prompt progression and continue at the
  next **un-run** prompt; never re-submit a prompt whose turn record exists (no
  `ai/generate` re-fire).
- [ ] Locate the suspend/resume boundary **inside** the single statechart step
  (resume re-enters the step, consults progression, does not restart the queue);
  post-drain route reached only after every prompt has a recorded turn.
- [ ] Runtime test: a mid-queue resume runs only the un-run prompts
  (resume-from-progression idempotency, AC-7); completed turns not re-fired.
- [ ] Verify replay path: replaying the event log reproduces the same per-prompt
  records without re-firing completed `ai/generate` effects.
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
- [ ] `clj-kondo` clean; commit Slice 6.

## Slice 7 — Docs + changelog

- [ ] `doc/workflow-grammar.md`: author-facing `:prompts` form, group-internal
  `:prompt-workflow` xor `:contributions`, step-level vs `:prompts` precedence.
- [ ] `doc/workflow-grammar-concepts.md`: per-prompt output surfaces, `:prompt`
  source-ref discriminator + validation rules + post-drain-judge carve-out,
  drain/route semantics, resume-from-progression contract.
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
