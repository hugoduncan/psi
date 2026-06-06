# Plan

## Approach

A framework-level criterion-text correction in the `reduce-incidental-complexity`
emitter, plus knowledge/doc sync. No Gordian behaviour change, no renumbering.

The design is fully settled (all design-review loops closed; autonomy note confirms the
remaining work is the mechanical emitter/knowledge edit). This plan turns that into an
ordered, behaviour-preserving change to three artifacts and their content-lock tests.

Concrete edit targets (verified against the live tree):

1. **Emitter** — `.psi/workflows/reduce-incidental-complexity.edn`, the
   `select-and-create` step `:text`, **step 6** "Objective acceptance criteria". The
   current "Net burden (A2 — \"touched units\" defined)" bullet emits the broken net-sum
   gate (`sum after < sum before`). Replace **only that bullet** with:
   - the per-unit **A2a/A2b** form (pure inequalities `after(u) < B`, `B := before(target)`
     read from the committed `before-local.json`; no `θ`/`ε`), and
   - the "How A2 is mechanically checked" deterministic procedure (line-insensitive
     `(ns, var, arity)` grouping, `before-max(k)`, physical-row `after(u)`, multiset
     change filter for `T`, line-bearing single-row target exclusion).
   The **A5** (Burden reduction), **A3** (Architectural no-regression gate), Phase-0/
   tests-GREEN, blast-radius, and minimality criteria are left **unchanged** except where
   wording must reference the new A2. The emitter's existing non-sequential numbering
   (A5, A2, A3) is preserved.

2. **Content-lock tests** —
   `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj`,
   `reduce-incidental-complexity-test` (the "select-and-create prompt preserves … contracts"
   block). Two assertions currently lock the net-sum A2 wording and **will break**:
   - `"after total is strictly less than the before total"`
   - `"the set is computed from the metric, not from the diff/touched files"`
   Replace these with assertions that lock the new A2a/A2b wording (e.g. the `after(u) < B`
   ceiling, `before-max`, the per-physical-row / line-insensitive-key phrasing). Leave the
   A5/A3/blast-radius/baseline assertions intact.

3. **Knowledge page** — `mementum/knowledge/gordian-net-sum-burden-gate-sub-additivity.md`
   (status `active`). Per acceptance 4:
   - record the framework-level fix has **landed** (update "Action for future sessions"
     item 1 and the "Status / ratification" section, which currently call the fix
     un-filed);
   - mark the page's proposed residual anchor `after(s) < after(target)` **superseded by**
     the committed-baseline ceiling `after(u) < B` (one-line rationale: the residual
     `after(target)` is a contestable recompute, `B` is an immutable published anchor);
   - correct the page's **A1** target-reduction label to the live **A5** (both in "The
     genuine intent" and the empirical-confirmation `A1 … PASSED` line).

4. **Skill** — `.psi/skills/incidental-complexity-finder/SKILL.md`. Grep for any net-sum /
   A2 restatement is **empty** (verified), so acceptance 3 is satisfied by confirmation;
   only edit if a restatement is found.

## Risks

- **Content-lock test coupling.** Editing the emitter `:text` necessarily breaks the two
  net-sum assertions. Mitigation: update them in the same slice as the emitter edit so the
  workflow-loader suite stays green; do not delete coverage — re-point it at the new A2.
- **EDN well-formedness.** The `:text` is a single large escaped string; an unbalanced
  quote/paren breaks loading. Mitigation: run `clj-paren-repair` after the edit and load
  via the workflow-loader test (`load-edn-only`) before proceeding.
- **Accidental scope creep into A5/A3/numbering.** Acceptance 2 forbids altering other
  criteria or renumbering. Mitigation: confine the diff to the single A2 bullet; diff-review
  the step text before commit.
- **Knowledge-page drift.** The page is `active` and cited by the design; leaving the
  `after(target)`/A1 form would leave it documenting a different gate than landed.
  Mitigation: acceptance 4 explicitly gates this — supersede + relabel in place.

## Slice order

1. **Emitter A2 correction + content-lock test update** (acceptance 1, 2) — the core
   vertical slice: corrected A2 text in the EDN, EDN loads, content-lock tests re-pointed
   and green.
2. **Skill alignment confirmation** (acceptance 3) — confirm absent (or align if found).
3. **Knowledge-page reconciliation** (acceptance 4) — fix-landed + supersede + A1→A5.
4. **Verification / dry read-through** (acceptance 5) — workflow-loader suite green; a dry
   read-through (or generated sample) showing the new A2 is satisfiable by a genuine
   decomplecting extraction and still rejects a relocated/inverted extraction.
