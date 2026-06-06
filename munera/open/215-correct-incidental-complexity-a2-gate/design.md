# Correct the `reduce-incidental-complexity` A2 acceptance gate

## Intent

The `reduce-incidental-complexity` workflow emits a generated refactor task whose
Phase-1 acceptance criterion **A2 ("net burden")** is **provably unsatisfiable by any
behaviour-preserving decomplecting extraction** — it structurally forbids the very
refactor the workflow selects for. This task corrects the *emitter* so every future
incidental-complexity task carries a sound, well-posed A2.

This is a framework-level fix at the source, not a per-task workaround. The defect was
diagnosed and proven while closing task 214 (`214-simplify-start-nrepl`); see
`mementum/knowledge/gordian-net-sum-burden-gate-sub-additivity.md` (status `active`).

## Why A2 is broken (root cause)

The emitted A2 requires, over the metric-touched set `T = {u | before(u) != after(u)}`:

    sum_{u in T} after(u) < sum_{u in T} before(u)

Gordian's per-unit per-dimension transform is `log1p-over-scale` — **concave**, `f(0)=0`,
hence **sub-additive**: `f(b1) + f(b2) >= f(b1 + b2)`. Decomplection *is* extraction *is*
splitting one unit's raw burden across two units, so the **summed normalized** burden
*rises* even when raw burden is conserved or reduced. A2 therefore grows precisely when
the refactor does the right thing.

It is a category error: comprehension burden is **local** (per-unit — a reader
understands one unit at a time). Summing normalized per-unit burdens across the target
plus its extracted seam **double-counts decomplection's benefit as a cost**.

Empirical confirmation (task 214, target `start-nrepl!`/4): the Pareto-optimal seam-only
extraction netted **+0.3565** (A2 FAIL) while the target's own burden dropped **−7.7%**
(`6.0154 → 5.5499`, A1 PASS). The seam's 7 raw deps re-normalized to `0.773`, cancelling
almost the entire target dependency reduction purely from concavity.

## What A2 is genuinely for

A2's legitimate purpose is to reject **superficial / inverted extraction** — "I reduced
the target's burden by relocating the tangle somewhere else" — in two forms:

1. Pushing the tangle into a **new** named seam.
2. Pushing the tangle into an **existing** caller/sibling.

The other intents A2 was conflated with are already covered:

- "Reduce the target's local comprehension burden" → **A1** (target's own `lcc-total`
  strictly decreases).
- "No architectural regression" → **A3** (`gordian gate` new cycles / high / medium).
- "Minimal, local change" → **A5** (blast radius).

So A2 must be re-scoped to *only* its relocation guard, expressed per-unit.

## Proposed corrected A2 (per-unit, no sum)

Let `B := before(target)` — the original target unit's `lcc-total`, read from the
committed `before-local.json` baseline (an immutable, already-published anchor; NOT a
recomputed `after(target)`). Units are identified by the line-insensitive key
`(ns, var, arity)`; newly-created units take `before(u) := 0` (carried over from the
existing baseline-identity conventions).

Over the metric-touched set `T` (same metric-derived set as today, minus the target
itself, which A1 already governs):

- **A2a — new units are genuine pieces, not a relocated tangle.** Every newly-introduced
  unit `n` (i.e. `before(n) = 0`) satisfies `after(n) < B`. Each extracted seam is
  strictly simpler than the *original whole* it was carved from.

- **A2b — no collateral inflation of existing units.** Every pre-existing modified unit
  `m` (`before(m) > 0`, `m != target`) satisfies BOTH:
  - **no substantial increase:** `after(m) <= before(m) * (1 + θ)` for a small fixed
    `θ` (proposed default `θ = 0.10`; the exact value is a design-review parameter), AND
  - **no new ceiling breach:** if `before(m) < B` then `after(m) < B`. (A unit already
    `>= B` before the refactor — through no fault of this change — is exempt from the
    ceiling and bound only by the no-substantial-increase clause, so A2b never becomes
    unsatisfiable for a legitimately-touched large sibling.)

The target unit itself is intentionally excluded from A2 — its reduction is A1's job;
including it here would re-conflate the two.

### Why this is sound and well-posed

- **No sum → immune to the sub-additivity defect.** Splitting a tangle of burden `B`
  into pieces each `< B` is exactly what decomplection does; A2a can always be satisfied
  by a genuine split.
- **Stable anchor.** `B` comes from the committed `before-local.json`, so the gate does
  not depend on a contestable recomputed value.
- **Gaming-resistant within the full suite.** Fragmenting into many tiny units raises the
  *target's own* dependency / working-set burden → fails **A1**; helper-chasing →
  flagged by **A3**; scope sprawl → fails **A5**. A2 need not (and must not) re-police
  total burden.
- **Objective.** Every clause is a concrete numeric comparison against committed
  baselines; no "with margin"-style undefined buffer.

### Open design-review parameters (resolve in plan/design loop, do not leave ambiguous)

1. The substantial-increase threshold `θ` (proposed `0.10`). Pick one concrete value.
2. Whether A2b's no-substantial-increase clause should also admit a small absolute slack
   `ε` to absorb global-recompute jitter for units whose source was not edited but whose
   burden shifted (the metric-derived `T` includes such units by design today).
3. Confirm the line-insensitive `(ns, var, arity)` identity + `before(u) := 0`
   conventions are stated once and reused (already present in the current emitter text).

## Scope / blast radius

- Primary edit: the A2 criterion text the workflow emits into each generated
  `design.md` — `.psi/workflows/reduce-incidental-complexity.edn`, the
  `select-and-create` step's `:text` (the "Net burden (A2 — ...)" bullet under
  "Objective acceptance criteria").
- Check for and align any A2 description in
  `.psi/skills/incidental-complexity-finder/SKILL.md` and the
  `reduce-incidental-complexity` skill, if either restates the net-sum form.
- Update `mementum/knowledge/gordian-net-sum-burden-gate-sub-additivity.md`: once the
  emitter is corrected, record that the framework-level fix has landed (its "Action for
  future sessions" item 1 and "Status / ratification" section currently say the fix is
  un-filed).
- Out of scope: re-opening or re-running task 214; changing Gordian's transform; the
  human ratification of 214's in-place A2′ redefinition (separate human gate). This task
  fixes the *template* going forward; 214 remains as-is.

## Constraints

- No behaviour change to Gordian itself; this is a criterion-text correction in the
  workflow emitter (+ knowledge/doc sync).
- The corrected A2 must be objective (concrete numeric comparisons against committed
  baselines), well-posed (no unsatisfiable case for genuine decomplection), and
  internally consistent with A1/A3/A5.
- Keep the unit-identity and baseline-absent (`before(u) := 0`) conventions consistent
  with the rest of the emitted contract.

## Acceptance

1. `.psi/workflows/reduce-incidental-complexity.edn` no longer emits the net-sum A2; it
   emits the per-unit A2a/A2b form (with the chosen `θ`), and the workflow EDN still
   reads/loads correctly.
2. No other emitted criterion (A1, A3, A4, A5) is altered except wording needed to
   reference the new A2 consistently; A2 no longer governs the target unit (A1 does).
3. Any A2 restatement in the relevant skill files is aligned (or confirmed absent).
4. The knowledge page reflects that the framework-level fix has landed.
5. A dry read-through (or a generated sample task) shows the new A2 is satisfiable by a
   genuine extraction and still rejects a relocated/inverted extraction.

## Autonomy note

Diagnosis is fully grounded (proof + task-214 empirics in the knowledge page). The
specific corrected criterion (anchor choice, `θ`, ceiling conditionality) is the design
surface to settle in the review loop before implementation.
