# Steps

## Slice 1 — Emitter A2 correction + content-lock test update (acceptance 1, 2)

- [x] Locate the step-6 "Net burden (A2 — \"touched units\" defined)" bullet inside the
      `select-and-create` step `:text` in
      `.psi/workflows/reduce-incidental-complexity.edn`.
- [x] Replace **only that bullet** with the corrected A2: the per-unit **A2a** (new pieces
      genuine: every physical after-row `u` with new key `before-max(k)=0` satisfies
      `after(u) < B`) and **A2b** (no ceiling breach: every physical after-row `u` with
      `0 < before-max(k) < B` satisfies `after(u) < B`; keys with `before-max(k) >= B`
      exempt), with `B := before(target)` read from the committed `before-local.json`
      (NOT a recomputed `after(target)`); pure inequalities, no `θ`/`ε`.
- [x] Inline the "How A2 is mechanically checked" deterministic procedure into the bullet
      (or an adjacent emitted paragraph): read `B`; recompute `bb gordian local --json`;
      group both JSONs by line-insensitive `k = (ns, var, arity)`; define `before-max(k)`;
      form `T` via order-insensitive multiset change comparison (never a sum); exclude only
      the target's own line-bearing physical row; assert A2a then A2b as numeric `<`.
- [x] Confirm the emitted A5 (Burden reduction), A3 (gate `--fail-on …
      --max-new-medium-findings 0`), Phase-0/tests-GREEN, blast-radius, and minimality
      criteria are byte-unchanged except wording that references the new A2; numbering
      preserved (A5, A2, A3 — no renumbering, no A1/A4).
- [x] Verify EDN delimiters/format are intact (bb `edn/read-string` round-trips; the
      single-bullet `edit` preserved the surrounding string — no `clj-paren-repair` needed).
- [x] Update the two literal net-sum string content-lock assertions (lines 299/301) in
      `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj`
      (`reduce-incidental-complexity-test`, "select-and-create prompt preserves … contracts"):
      removed `"after total is strictly less than the before total"` and
      `"the set is computed from the metric, not from the diff/touched files"`; added
      assertions locking the new A2 wording (`Net burden (A2 — relocation guard, per-unit)`,
      `line-insensitive key `k = (ns, var, arity)``, `physical after-row `u``, `before-max(k)`,
      `satisfies `after(u) < B``, `NOT a recomputed `after(target)``).
- [x] Run the workflow-loader suite (`reduce-incidental-complexity-test` +
      `task-209-workflow-set-loads-together-test`); confirmed loads succeed and all
      content-lock assertions are green (3 tests, 196 assertions, 0 fail/0 error).
- [x] `clj-kondo --lint` the touched test file; 0 errors / 0 warnings.
- [x] **PA1 (plan/steps ambiguity):** handled the *third* net-sum-bound content-lock
      assertion (line-295 `identified by `(ns, var, arity, line)``). Removed it and replaced
      it with the line-insensitive `(ns, var, arity)` A2 key lock
      (`line-insensitive key `k = (ns, var, arity)``); the line-bearing phrase is not
      preserved against the new A2. The adjacent **A5** line-294 lock
      `"keyed by `(ns, var, arity, line)`"` is left intact.
- [x] Commit: `⊨ reduce-incidental-complexity: replace net-sum A2 with per-unit A2a/A2b gate`.

## Slice 2 — Skill alignment confirmation (acceptance 3)

- [x] Grep `.psi/skills/incidental-complexity-finder/SKILL.md` (and any other `.psi/skills/`
      file) for a net-sum / A2 restatement.
- [x] No restatement found (grep over `.psi/skills/` for net-sum / A2 / touched-units is
      empty) → "confirmed absent"; no edit needed. Acceptance 3 satisfied.

## Slice 3 — Knowledge-page reconciliation (acceptance 4)

- [x] In `mementum/knowledge/gordian-net-sum-burden-gate-sub-additivity.md`, updated "Action
      for future sessions" item 1 and the "Status / ratification" section to record that the
      framework-level emitter fix has **landed** (task 215).
- [x] In "The genuine intent, correctly expressed", marked the proposed residual anchor
      `∀ s ∈ (after-units \ before-units): after(s) < after(target)` **superseded by** the
      committed-baseline ceiling `after(u) < B` (`B := before(target)` from `before-local.json`),
      with the one-line rationale (residual `after(target)` is a contestable recompute; `B` is
      an immutable published anchor).
- [x] Corrected the page's **A1** target-reduction label to the live **A5** — in "The genuine
      intent" (twice) and the empirical line.
- [x] Commit: `🔄 update: gordian-net-sum-burden-gate-sub-additivity` (fix landed; A2 form +
      A1→A5 reconciled with the emitter).

## Slice 4 — Verification / dry read-through (acceptance 5)

- [x] Re-ran the workflow-loader suite to confirm the corrected EDN still loads and all
      content locks are green (3 tests / 196 assertions / 0 fail / 0 error).
- [x] Dry read-through of the emitted Phase-1 criteria: the new A2 is **satisfiable** by a
      genuine decomplecting extraction (task-214: seam `start-server-quietly` lcc
      `0.8220 < B ≈ 6.0154` → A2a PASS) and still **rejects** a relocated/inverted
      extraction (a new seam with `after(u) >= B`, or an existing below-ceiling sibling
      pushed to `after(u) >= B`, fails A2a/A2b). Recorded in `implementation.md`.
- [x] All five acceptance criteria met. Residual follow-up (out of scope): a dedicated
      `bb gordian` A2 join-and-compare subcommand to replace the spelled-out agent-run
      procedure.

## Plan/steps review follow-ups — inconsistencies

- [x] **PI1:** Reconcile plan.md's self-contradictory count. The Risks section
      "Content-lock test coupling" still says "breaks **the two** net-sum assertions"
      while "Concrete edit targets" #2 says "**Three** assertions … will break". Update
      the Risks bullet to three (or "all three").
- [x] **PI2:** Align this slice-1 primary content-lock step (currently "Update **the two**
      net-sum content-lock assertions") with plan.md's three-assertion framing — either
      fold the line-295 `identified by …` key assertion into it, or explicitly scope it to
      "the two literal net-sum strings (lines 299/301)" and cross-reference the PA1 step
      for the line-295 key assertion.
- [x] **PI3:** Fix the stale plan cross-reference in the PA1 step: it says "Correct the
      plan's 'Two assertions … will break' to three", but plan.md's concrete-targets
      section already reads "Three"; the only surviving "two" is the Risks bullet (PI1).
      Re-point the PA1 count-correction at the Risks bullet, or drop it as already done.

## Implementation review follow-ups (ψ)

- [x] **RI1:** Reconcile the `B` identification key between `design.md` and the landed
      emitter. design.md "How A2 is mechanically checked" **step 1** (design.md:145–147)
      keys `B := before(target)` by the **line-insensitive** `(ns, var, arity)`, but the
      emitter (`.psi/workflows/reduce-incidental-complexity.edn`, A2 step 1) locates `B` by
      its **line-bearing** `(ns, var, arity, line)` identity (the row A5 governs). The
      line-bearing form is the well-defined one (line-insensitive `B` is ambiguous for the
      51-row `execute-effect!` defmethod case the design foregrounds) and matches the
      design's own target-exclusion rule + A5. Update design.md step 1 (and its
      parenthetical "A2's chosen identity") to the line-bearing `B` lookup that landed, with
      a one-line rationale; leave the line-insensitive `(ns, var, arity)` **grouping** for
      `before-max(k)`/`T` (step 3) unchanged — only `B`'s own lookup key is at issue.

- [x] **RI3:** Add a `[Unreleased]` CHANGELOG entry for the A2 gate correction. The
      change_chain requires a CHANGELOG entry for user-visible `bug_fix`/`behaviours`
      changes, and the project already logs `reduce-incidental-complexity` behaviour
      changes (CHANGELOG line 12 workflow intro; line 19 task-212 test-net-gate hardening).
      Task 215 changes the same workflow's emitted Phase-1 A2 acceptance from the provably
      unsatisfiable net-sum gate (`sum after < sum before`) to the sound per-unit A2a/A2b
      relocation-guard ceiling (`after(u) < B`, target reduction governed by A5) — a
      bug_fix/behaviour change with no CHANGELOG entry. Add a `[Unreleased]` entry
      (Changed or Fixed) describing the corrected gate, consistent with the line-19
      precedent. (Task Scope omitted CHANGELOG, mirroring the RI2 `doc/workflows.md`
      omission; not a deliberate exclusion.)

- [x] **RI2:** Sync `doc/workflows.md` to the landed per-unit A2. The
      `reduce-incidental-complexity` Phase-1 acceptance paragraph (~line 709) still says
      "net burden across the metric-derived touched set strictly decreases" — the
      superseded net-sum gate. Rewrite that clause to describe the per-unit A2a/A2b
      relocation-guard ceiling (`after(u) < B`, `B := before(target)` from
      `before-local.json`; target reduction governed by A5), matching the landed emitter
      (`change_chain` doc-sync; the task Scope omitted this file). Verify no other `doc/`
      or `README.md` sentence restates the net-sum form (the two CHANGELOG entries describe
      the workflow generally and are unaffected).

## Test review follow-ups (ψ, task-test-review)

- [x] **TR-T1:** Add a regression guard for the defining "no sum" invariant in
      `reduce-incidental-complexity-test` ("select-and-create prompt preserves … contracts").
      The three removed net-sum locks left no guard against re-introducing a sum into A2.
      Add a positive `(is (.contains select-text "never a sum"))` (and/or
      `"must NOT sum normalized per-unit burdens"`) lock, and/or a negative
      `(is (not (.contains select-text "sum after < sum before")))` /
      `(is (not (.contains select-text "after total is strictly less than the before total")))`
      assertion, so a future edit cannot silently restore the sub-additive net-sum gate.
- [x] **TR-T2:** Lock the A2a/A2b branch structure + the exemption clause. Add
      content-locks for `"A2a (new pieces are genuine"`, `"A2b (no collateral ceiling breach"`,
      and the exemption `"before-max(k) >= B"` / `"EXEMPT"` wording, so a drift dropping the
      `>= B` exemption branch (which keeps the gate satisfiable / well-posed) is caught.
- [x] **TR-T3:** Lock the line-bearing single-row target exclusion (shared-key hole
      closure). Add content-locks for `"remove ONLY the target's own physical row"`,
      `"never the whole"` (the `(ns, var, arity)` group), and `"siblings STAY in"`, so a
      regression to whole-key-group exclusion (the documented relocation hole) cannot pass
      the suite. After adding TR-T1..TR-T3 locks, re-run the workflow-loader suite
      (`reduce-incidental-complexity-test` + `task-209-workflow-set-loads-together-test`)
      and `clj-kondo --lint` the test file to confirm green + clean.

## Test review re-pass follow-ups (ψ, task-test-review)

- [x] **TR-T4:** Add a regression guard for the pure-inequality / no-margin (θ/ε removed)
      invariant in `reduce-incidental-complexity-test` ("select-and-create prompt preserves
      … contracts"). The existing `satisfies `after(u) < B`` lock does NOT guard against a
      reintroduced margin (`after(u) < B + θ` still contains it). Add a positive lock on the
      `with no margin (no slack threshold, no jitter buffer)` wording (and/or `pure per-unit
      inequalities`), so re-adding a tunable threshold/slack — the undefined-buffer surface
      the design forbids — cannot pass green. This is the analogue of the TR-T1 no-sum guard
      for the other half of the redesign's soundness pair (no sum, no margin).
- [x] **TR-T5:** Lock the line-bearing `B` lookup (the RI1 reconciliation). Nothing
      currently locks that `B` itself is located line-bearingly; only the line-insensitive
      grouping key and the A5 line-bearing key are locked. Add a content-lock on `located by
      its line-bearing `(ns, var, arity, line)`` (the `B`-lookup phrasing), so a regression
      reverting `B`'s lookup to the line-insensitive `(ns, var, arity)` key — reopening the
      51-row `execute-effect!` defmethod ambiguity RI1 fixed — cannot pass the suite. Keep it
      distinguished from the already-locked line-insensitive grouping key
      (`line-insensitive key `k = (ns, var, arity)``). After adding TR-T4/TR-T5, re-run the
      workflow-loader suite (`reduce-incidental-complexity-test` +
      `task-209-workflow-set-loads-together-test`) and `clj-kondo --lint` the test file to
      confirm green + clean.

## Test review re-pass follow-ups (ψ, task-test-review)

- [x] **TR-T6:** Add a regression guard for the objective / deterministic-numeric-procedure
      (¬agent-judgement) invariant in `reduce-incidental-complexity-test`
      ("select-and-create prompt preserves … contracts"). The design's Constraints +
      Acceptance require A2 be *objective* (concrete numeric comparisons), and the emitter
      frames the check as "a deterministic numeric procedure over two JSON artifacts — the
      same KIND of objective check as A3, not agent judgement". Nothing currently locks this:
      a reword into a judgement-based check would silently reintroduce the subjectivity the
      redesign removes and stay green. Add a content-lock on `the same KIND of objective
      check as A3` (and/or `not agent judgement` / `a deterministic numeric procedure over
      two JSON artifacts`).
- [x] **TR-T7:** Lock the order-insensitive multiset `T`-formation (¬per-line-pairing). The
      design forms `T` by an order-insensitive multiset comparison ("an order-insensitive set
      comparison — never a sum"; `before-max` is "not a sum, not a per-line pairing") so the
      non-unique 51-row `execute-effect!` defmethod key stays well-posed. The TR-T1 `never a
      sum` lock catches only a sum-regression; a regression to a **per-line pairing** join
      (not a sum, but breaking non-unique-key handling) is uncaught. Add a content-lock on
      `an order-insensitive set comparison` (and/or `not a per-line pairing`). After adding
      TR-T6/TR-T7, re-run the workflow-loader suite (`reduce-incidental-complexity-test` +
      `task-209-workflow-set-loads-together-test`) and `clj-kondo --lint` the test file to
      confirm green + clean.
