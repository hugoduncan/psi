# Steps

## Slice 1 — Emitter A2 correction + content-lock test update (acceptance 1, 2)

- [ ] Locate the step-6 "Net burden (A2 — \"touched units\" defined)" bullet inside the
      `select-and-create` step `:text` in
      `.psi/workflows/reduce-incidental-complexity.edn`.
- [ ] Replace **only that bullet** with the corrected A2: the per-unit **A2a** (new pieces
      genuine: every physical after-row `u` with new key `before-max(k)=0` satisfies
      `after(u) < B`) and **A2b** (no ceiling breach: every physical after-row `u` with
      `0 < before-max(k) < B` satisfies `after(u) < B`; keys with `before-max(k) >= B`
      exempt), with `B := before(target)` read from the committed `before-local.json`
      (NOT a recomputed `after(target)`); pure inequalities, no `θ`/`ε`.
- [ ] Inline the "How A2 is mechanically checked" deterministic procedure into the bullet
      (or an adjacent emitted paragraph): read `B`; recompute `bb gordian local --json`;
      group both JSONs by line-insensitive `k = (ns, var, arity)`; define `before-max(k)`;
      form `T` via order-insensitive multiset change comparison (never a sum); exclude only
      the target's own line-bearing physical row; assert A2a then A2b as numeric `<`.
- [ ] Confirm the emitted A5 (Burden reduction), A3 (gate `--fail-on …
      --max-new-medium-findings 0`), Phase-0/tests-GREEN, blast-radius, and minimality
      criteria are byte-unchanged except wording that references the new A2; numbering
      preserved (A5, A2, A3 — no renumbering, no A1/A4).
- [ ] Run `clj-paren-repair .psi/workflows/reduce-incidental-complexity.edn` and re-read the
      file to confirm delimiters/format are intact.
- [ ] Update the two net-sum content-lock assertions in
      `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj`
      (`reduce-incidental-complexity-test`, "select-and-create prompt preserves … contracts"):
      remove `"after total is strictly less than the before total"` and
      `"the set is computed from the metric, not from the diff/touched files"`; add
      assertions locking the new A2 wording (e.g. the `after(u) < B` ceiling phrase,
      `before-max`, and the line-insensitive `(ns, var, arity)` / per-physical-row phrasing).
- [ ] Run the workflow-loader suite (`reduce-incidental-complexity-test` +
      `task-209-workflow-set-loads-together-test`); confirm `load-edn-only` succeeds and all
      content-lock assertions are green.
- [ ] `clj-kondo --lint` the touched test file; fix any findings.
- [ ] **PA1 (plan/steps ambiguity):** handle the *third* net-sum-bound content-lock
      assertion the plan's "two assertions" count omits. In
      `reduce-incidental-complexity-test` the line-295 assertion
      `(is (.contains select-text "identified by `(ns, var, arity, line)`"))` locks text
      emitted **only** by the net-sum A2 bullet and will also break on the A2 replacement.
      Remove it or re-point it at the new line-insensitive `(ns, var, arity)` A2 key; do
      **not** preserve the line-bearing phrase against the new A2. Explicitly leave the
      adjacent **A5** line-294 lock `"keyed by `(ns, var, arity, line)`"` intact (A5 keeps
      its line-bearing key). Correct the plan's "Two assertions … will break" to three.
- [ ] Commit: `⊨ reduce-incidental-complexity: replace net-sum A2 with per-unit A2a/A2b gate`.

## Slice 2 — Skill alignment confirmation (acceptance 3)

- [ ] Grep `.psi/skills/incidental-complexity-finder/SKILL.md` (and any other `.psi/skills/`
      file) for a net-sum / A2 restatement.
- [ ] If a restatement is found, align it to the per-unit A2a/A2b form and commit; if absent
      (current state), record "confirmed absent" in `implementation.md` — no edit needed.

## Slice 3 — Knowledge-page reconciliation (acceptance 4)

- [ ] In `mementum/knowledge/gordian-net-sum-burden-gate-sub-additivity.md`, update "Action
      for future sessions" item 1 and the "Status / ratification" section to record that the
      framework-level emitter fix has **landed** (task 215).
- [ ] In "The genuine intent, correctly expressed", mark the proposed residual anchor
      `∀ s ∈ (after-units \ before-units): after(s) < after(target)` **superseded by** the
      committed-baseline ceiling `after(u) < B` (`B := before(target)` from `before-local.json`),
      with the one-line rationale (residual `after(target)` is a contestable recompute; `B` is
      an immutable published anchor).
- [ ] Correct the page's **A1** target-reduction label to the live **A5** — in "The genuine
      intent" ("already captured by A1") and the empirical line ("A1 (the target unit's own
      lcc-total) PASSED").
- [ ] Commit: `🔄 update: gordian-net-sum-burden-gate-sub-additivity` (fix landed; A2 form +
      A1→A5 reconciled with the emitter).

## Slice 4 — Verification / dry read-through (acceptance 5)

- [ ] Re-run the workflow-loader suite to confirm the corrected EDN still loads and all
      content locks are green.
- [ ] Dry read-through of the emitted Phase-1 criteria (or a generated sample task design):
      verify the new A2 is **satisfiable** by a genuine decomplecting extraction
      (each seam `after(u) < B`), citing the task-214 figures (seam lcc `0.8220 < B ≈ 6.0154`),
      and still **rejects** a relocated/inverted extraction (a seam or sibling whose
      `after(u) ≥ B`).
- [ ] Record the dry read-through outcome in `implementation.md`.
- [ ] Confirm all five acceptance criteria are met; note any residual follow-up (e.g. the
      out-of-scope dedicated `bb gordian` A2 subcommand).
