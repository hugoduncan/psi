---
title: Gordian net-sum burden gate is structurally unsatisfiable by decomplecting extraction
status: active
category: tooling
tags: [gordian, incidental-complexity, reduce-incidental-complexity, acceptance-gates, metrics, refactoring]
related: ["munera/closed/214-simplify-start-nrepl", ".psi/skills/incidental-complexity-finder/SKILL.md", ".psi/skills/gordian/SKILL.md"]
depends-on: []
---

A `reduce-incidental-complexity` task's emitted "A2" acceptance gate — *net normalized
burden over the touched units strictly decreases* — is **provably unsatisfiable by any
behaviour-preserving decomplecting extraction**, and structurally forbids the very
refactor those tasks select for. Discovered closing task 214 (`simplify-start-nrepl`).

## The defect

A2 (as emitted) requires, over `T = {u | before(u) != after(u)}`:

    sum_{u in T} after(u) < sum_{u in T} before(u)

But Gordian's per-unit per-dimension transform is `log1p-over-scale` — **concave** with
`f(0)=0`, hence **sub-additive**:

    f(b1) + f(b2) >= f(b1 + b2)

Decomplection = extraction = splitting one unit's raw burden across two units. Under a
sub-additive transform the *summed normalized* burden rises even when **raw** burden is
conserved or reduced. So A2 grows precisely when you do the right thing.

The task's selection rationale (`abstraction-mix`/`abstraction-oscillation` braiding is
*in-body*; the only behaviour-preserving fix is to move the interop out into a seam) and
its Phase-1 approach *prescribe extraction*. Therefore the emitted A2 forbids the task's
own prescribed fix. It is a category error: comprehension burden is **local** (per-unit,
a reader understands one unit at a time); summing normalized per-unit burdens across
target + extracted seam **double-counts decomplection's benefit as a cost**.

## Empirical confirmation (task 214)

Target `psi.app-runtime.nrepl-runtime/start-nrepl!`/4, extracting `start-server-quietly`:

- Seam-only (the Pareto-optimum over 4 measured variants) nets **+0.3565** on A2 — a FAIL.
- Target dependency raw `21 -> 15` (normalized `2.14 -> 1.253`, −0.887), but the seam's
  7 raw deps re-normalize to `0.773` — almost the whole reduction reappears, **purely**
  from concavity, not from any coding defect.
- Every additional extraction or shared-local measured *increased* net burden further.
- **A5** (the **target** unit's own lcc-total — its live emitter label, *not* "A1")
  PASSED strongly: `6.0154 -> 5.5499` (−7.7%).

## The genuine intent, correctly expressed

- **Reduce the target unit's local burden** → already captured by **A5** (per-unit
  decrease), which passes when the refactor is real. (The live emitter labels target
  reduction **A5**; there is no "A1" in the emitted contract.)
- **Don't just relocate the tangle into a new seam** (A2's real purpose) → ~~express as
  "each extracted seam is strictly simpler than the *residual target*":
  `for-all s in (after-units \ before-units): after(s) < after(target)`~~ **SUPERSEDED**
  by the form that landed in the emitter (task 215): the per-unit ceiling
  `after(u) < B`, where `B := before(target)` read from the committed `before-local.json`.
  Rationale for the supersession: the residual `after(target)` is a *contestable recompute*
  (the target's after-value is itself in flux during the refactor), whereas `B` is an
  *immutable, already-published anchor*. The landed gate is two clauses — **A2a** (every
  new physical after-row `after(u) < B`) and **A2b** (every pre-existing below-ceiling row
  `after(u) < B`; rows already `>= B` exempt) — keyed line-insensitively on
  `(ns, var, arity)`. This rejects superficial/inverted extraction without penalising
  genuine decomplection. (In 214: seam lcc `0.8220 < B ≈ 6.0154` → PASS.)
- **No architectural regression** → captured by **A3** (`gordian gate` new
  cycles/high/medium findings).

## Action for future sessions

1. **LANDED (task 215).** The framework-level fix is filed and merged: the
   `reduce-incidental-complexity` emitter
   (`.psi/workflows/reduce-incidental-complexity.edn`, `select-and-create` step) no longer
   emits the net-sum A2. It now emits the per-unit **A2a/A2b** ceiling gate (`after(u) < B`,
   `B := before(target)` from the committed `before-local.json`; pure inequalities, no
   `θ`/`ε`), with a spelled-out deterministic mechanical-check procedure (line-insensitive
   `(ns, var, arity)` grouping, `before-max(k)`, physical-row `after(u)`, multiset change
   filter for the touched set, line-bearing single-row target exclusion). Every future
   incidental-complexity task therefore carries the corrected A2. The workflow-loader
   content-lock tests were re-pointed to lock the new wording.
2. **Do not silently rewrite A2 per-task to self-close.** Task 214 redefined A2 in its
   own `design.md` and self-closed; the math is sound but rewriting an acceptance bar is
   an autonomous change to the contract that needs human ratification. Surface it; prefer
   fixing the emitter over per-task redefinition. (Task 215 fixed the *template* going
   forward; 214 remains as-is, its in-place redefinition still subject to human review.)
3. **A5 + A3 already cover the genuine goals.** If forced to choose, trust A5 (target
   burden down) + A3 (no architectural regression). The net-sum A2 is fully retired; the
   landed A2a/A2b is its sound replacement.

## Status / ratification

The framework-level fix (correcting the emitted criterion) **landed in task 215** — the
emitter now emits the per-unit A2a/A2b ceiling gate (`after(u) < B`) instead of the
net-sum form. The task-214 in-place A2 redefinition remains **provisional, pending human
ratification** (a human may ratify it or revert `design.md`'s A2 and reopen task 214);
task 215 corrected the template prospectively and did not re-open or re-run 214.
