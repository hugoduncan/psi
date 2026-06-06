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
(`6.0154 → 5.5499`, A5 PASS — target-reduction). The seam's 7 raw deps re-normalized to `0.773`, cancelling
almost the entire target dependency reduction purely from concavity.

## What A2 is genuinely for

A2's legitimate purpose is to reject **superficial / inverted extraction** — "I reduced
the target's burden by relocating the tangle somewhere else" — in two forms:

1. Pushing the tangle into a **new** named seam.
2. Pushing the tangle into an **existing** caller/sibling.

The other intents A2 was conflated with are already covered by the **existing emitted
criteria, using their actual current labels** (the live emitter labels target reduction
**A5**, net burden **A2**, gate **A3**; there is no A1 and no A4, and blast radius is an
unnumbered prose criterion — see "Criterion taxonomy" below):

- "Reduce the target's local comprehension burden" → **A5** ("Burden reduction" — the
  target's own `lcc-total` strictly decreases versus `before-local.json`).
- "No architectural regression" → **A3** (`gordian gate` new cycles / high / medium).
- "Minimal, local change" → the **unnumbered blast-radius / minimality** criteria
  ("Blast radius: …" prose plus "The change is minimal, local, and decomplecting").

So A2 must be re-scoped to *only* its relocation guard, expressed per-unit.

### Criterion taxonomy (anchor to the live emitter; renumbering out of scope)

This task corrects **only the A2 criterion text** in place; it keeps every other emitted
criterion's existing label. The live emitter (`.psi/workflows/reduce-incidental-complexity.edn`,
`select-and-create` step) emits, under "Objective acceptance criteria":

| Emitted label | Concern |
| --- | --- |
| **A5** | "Burden reduction" — target's own `lcc-total` decreases vs `before-local.json` |
| **A2** | "Net burden" — *the broken net-sum gate this task replaces* |
| **A3** | "Architectural no-regression" — `gordian gate --fail-on … --max-new-medium-findings 0` |
| *(unnumbered)* | Phase-0 + existing tests GREEN |
| *(unnumbered)* | "The change is minimal, local, and decomplecting" |
| *(unnumbered, step-6 prose)* | "Blast radius: the target unit PLUS minimal helpers" |

The emitter's numbering is intentionally left non-sequential (A5, A2, A3) and unchanged:
renumbering the whole contract is a separate, broader change that would touch criteria
this task is constrained not to alter. All references in this design use these actual
labels.

## Proposed corrected A2 (per-unit, no sum)

Let `B := before(target)` — the original target unit's `lcc-total`, read from the
committed `before-local.json` baseline (an immutable, already-published anchor; NOT a
recomputed `after(target)`). Units are identified by the line-insensitive key
`(ns, var, arity)`; newly-created units take `before(u) := 0` (carried over from the
existing baseline-identity conventions).

Over the metric-touched set `T` (same metric-derived set as today, minus the target
itself, which **A5** already governs):

- **A2a — new units are genuine pieces, not a relocated tangle.** Every newly-introduced
  unit `n` (i.e. `before(n) = 0`) satisfies `after(n) < B`. Each extracted seam is
  strictly simpler than the *original whole* it was carved from.

- **A2b — no collateral ceiling breach in existing units.** Every pre-existing modified
  unit `m` (`before(m) > 0`, `m != target`) with `before(m) < B` satisfies
  `after(m) < B`. (A unit already `>= B` before the refactor — through no fault of this
  change — is exempt from the ceiling; relocating into an already-oversized sibling
  cannot make it cross a threshold it already exceeds, and any genuine architectural
  worsening of such a unit is caught by **A3**.)

The target unit itself is intentionally excluded from A2 — its reduction is **A5**'s job;
including it here would re-conflate the two.

### Pure inequalities — no tunable margins (θ / ε removed)

A2a and A2b are stated as **pure inequalities against the original target's burden `B`**,
with no `θ` slack and no `ε` jitter buffer. This is deliberate and is the resolution of
the earlier open parameters:

- A non-zero margin is **not architecturally necessary**. The only motivation for a
  margin was global-recompute *jitter* on units whose source was not edited but whose
  `dependency` / `working-set` burden shifts because `local` is recomputed globally. The
  ceiling form `after(m) < B` is **jitter-immune by construction**: jitter is a small
  perturbation, while crossing the original target's whole-tangle burden `B` is a large
  move that only a genuine relocation produces. Small upward jitter that does not cross
  `B` is harmless to the relocation guard, so no slack is required to tolerate it.
- A tunable `θ` (and optional `ε`) would reintroduce exactly the
  `"with margin"`-style **undefined buffer** this design's soundness goal forbids, plus a
  configuration/drift surface that fights `λone_way`. Dropping them keeps A2 a one-way,
  parameter-free objective gate.
- The previously-proposed "no substantial increase" clause is therefore **dropped**. Its
  anti-relocation role is fully subsumed: relocation into an existing sibling is only a
  defect when that sibling approaches the original tangle's size — which is precisely the
  ceiling `after(m) < B` — while architectural worsening of an already-large sibling is
  **A3**'s job and the target's required improvement is **A5**'s.

### How A2 is mechanically checked (enforceable, objective, agent-run)

A2a/A2b are **concrete deterministic numeric comparisons over two committed/recomputed
JSON artifacts** — not agent judgement. The procedure (run from the worktree root):

1. `B := before(target)` — the target's `lcc-total` in
   `munera/open/NNN-slug/before-local.json`, keyed by the line-insensitive
   `(ns, var, arity)` (A2's chosen identity — see note below).
2. Recompute after burdens: `bb gordian local --json` (bare, no `--sort`).
3. **Group both JSONs by the line-insensitive key `k = (ns, var, arity)`.** A2's
   *atomic unit* is the **physical defunit row** (one source occurrence — its own
   `after(u)` burden); the key `k` is used only to *pair* before/after rows for
   classification and exemption, never to merge after-rows into one comparison. For each
   key `k` define `before-max(k)` := the maximum `lcc-total` among before-rows carrying
   `k` (`0` if `k` has no before-row). `before-max` — not a sum and not a per-line pairing
   — is the only before-side quantity the **A2a/A2b pass/fail inequalities** consume
   (step 4's change filter is a separate, *non-load-bearing* row selection — see below),
   which makes the check well-defined even when `k` is non-unique (see the defmethod note
   below).
4. Form `T`: a physical after-row `u` with key `k` is in `T` iff `k`'s burdens changed —
   i.e. `k` is **new** (has no before-row) **or** the **multiset of `lcc-total` values over
   `k`'s before-rows differs from the multiset over `k`'s after-rows** (an order-insensitive
   set comparison — never a sum). `T`-membership is **not load-bearing for soundness**:
   including an unchanged row would be harmless, because an untouched row has
   `after(u) = before(u) ≤ before-max(k)`, so it auto-satisfies A2a/A2b when
   `before-max(k) < B` and is exempt when `before-max(k) ≥ B`. `T` is therefore a
   reporting/efficiency filter confining the gate to genuinely touched units; it
   deliberately uses **no sum**, so the sub-additive sum this redesign eliminates never
   re-enters even the row selection (this is what reconciles step 4 with step 3's
   "`before-max` is the only before-side quantity" claim: the pass/fail inequalities use
   only `before-max`; the multiset filter is order-insensitive set membership, not a
   burden total).

   **Target exclusion.** Remove **only the target's own physical row** from `T`. The
   target is identified by its **line-bearing** identity — the same line-bearing
   `(ns, var, arity, line)` key **A5** uses — *not* by its line-insensitive A2 key. The
   target is a single physical defunit, so exactly **one** row is removed (hence "row",
   singular; never the whole `(ns, var, arity)` key group). When the target shares its
   line-insensitive key with siblings (the 51-row defmethod case), those siblings **stay
   in `T`** and remain policed by A2a/A2b — so relocating the tangle into a key-sharing
   sibling still trips the ceiling (or, if that sibling was already `≥ B`, surfaces
   through **A3**/**A5**); the group is never blanket-exempted and the relocation guard
   has no hole.
5. **A2a (new pieces):** for every `u ∈ T` whose key has no before-row
   (`before-max(k) = 0`), assert `after(u) < B`.
6. **A2b (no ceiling breach):** for every `u ∈ T` whose key is pre-existing with
   `0 < before-max(k) < B`, assert `after(u) < B`. (A key with `before-max(k) ≥ B` is
   exempt — it already contained an oversized member through no fault of this change;
   genuine architectural worsening there is caught by **A3**.)

Every clause is a numeric `<` over fields read directly from the two JSON files; there is
no threshold to tune and no interpretation step. This is the same *kind* of objective
check as **A3**.

A2 deliberately keys on the **line-insensitive** `(ns, var, arity)` (not the line-bearing
key A5 uses): a refactor moves line numbers for almost every unit, so a line-sensitive
join would make nearly all before/after units fail to match and would collapse `T` into
"everything changed", defeating the per-unit comparison. (The latent question of whether
A5's line-bearing target key is itself robust to line movement is a separate matter, not
in this task's scope.)

**Non-unique keys (defmethods).** The line-insensitive key is *not* unique in the live
data: `before-local.json` for task 214 contains 51 rows sharing the exact key
`(psi.agent-session.dispatch-effects, execute-effect!, nil-arity)`, disambiguated only by
`line`. The grouping rule above is well-posed precisely for this case: the before side
collapses to the scalar `before-max(k)` (max over the group, the natural generalization of
the single-unit exemption), while the ceiling `after(u) < B` is asserted **per physical
after-row** rather than against a pairing. So a key matching many rows is never undefined
— each after-row is checked individually against the original tangle `B`, and the
group's largest pre-existing member sets the exemption. (Relocating a decomplected tangle
into one member of a polymorphic group is implausible and, were it attempted, would still
surface through **A5**/**A3**/blast-radius; A2 does not need a special case beyond the
per-row ceiling.)

Unlike A3, there is **no single `bb gordian` subcommand** that performs the A2 join-and-
compare today, and adding one is **out of scope** for this task (constrained to a
criterion-text correction in the emitter + knowledge/doc sync). The emitter therefore
spells out the deterministic procedure above so the executing agent performs a
mechanical, non-judged check; promoting it to a dedicated `bb gordian` gate command is a
reasonable but separate follow-up.

### Why this is sound and well-posed

- **No sum → immune to the sub-additivity defect.** Splitting a tangle of burden `B`
  into pieces each `< B` is exactly what decomplection does; A2a can always be satisfied
  by a genuine split.
- **Stable anchor.** `B` comes from the committed `before-local.json`, so the gate does
  not depend on a contestable recomputed value.
- **Gaming-resistant within the full suite.** Fragmenting into many tiny units raises the
  *target's own* dependency / working-set burden → fails **A5**; helper-chasing →
  flagged by **A3**; scope sprawl → fails the **blast-radius / minimality** criteria. A2
  need not (and must not) re-police total burden.
- **Objective.** Every clause is a concrete numeric comparison against committed
  baselines; no "with margin"-style undefined buffer and no tunable threshold.

### Resolved design parameters

The parameters previously left open are now settled:

1. **Substantial-increase threshold `θ` — removed.** A2b is the pure ceiling inequality
   `after(m) < B`; no margin (see "Pure inequalities" above).
2. **Jitter slack `ε` — removed.** The ceiling form is jitter-immune; no absolute slack
   is needed.
3. **Unit identity / baseline-absent conventions — settled.** A2 joins on the
   line-insensitive `(ns, var, arity)` key (deliberate, for move-robustness — see the
   mechanical-check note), and carries over the emitter's `before(u) := 0` convention for
   newly-created units unchanged.

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
  internally consistent with the existing A5 (target reduction), A3 (gate), and the
  unnumbered minimality / blast-radius criteria.
- Keep the unit-identity and baseline-absent (`before(u) := 0`) conventions consistent
  with the rest of the emitted contract.

## Acceptance

1. `.psi/workflows/reduce-incidental-complexity.edn` no longer emits the net-sum A2; it
   emits the per-unit A2a/A2b form (pure inequalities against `B`, no `θ`/`ε`), and the
   workflow EDN still reads/loads correctly.
2. No other emitted criterion — **A5** (target burden reduction), **A3** (architectural
   no-regression gate), and the unnumbered Phase-0/tests-GREEN, minimality, and
   blast-radius criteria — is altered, except wording needed to reference the new A2
   consistently; A2 no longer governs the target unit (**A5** does). The emitter's
   existing (non-sequential) numbering is preserved; no renumbering.
3. Any A2 restatement in the relevant skill files is aligned (or confirmed absent).
4. The knowledge page reflects that the framework-level fix has landed.
5. A dry read-through (or a generated sample task) shows the new A2 is satisfiable by a
   genuine extraction and still rejects a relocated/inverted extraction.

## Autonomy note

Diagnosis is fully grounded (proof + task-214 empirics in the knowledge page). The design
surface — anchor choice (`B` from `before-local.json`), margins (`θ`/`ε` removed → pure
inequalities), ceiling form, join key (line-insensitive `(ns, var, arity)`), and taxonomy
anchoring (actual A5/A2/A3 labels) — has been settled through the design-review loop; the
remaining work is the mechanical emitter/knowledge edit.
