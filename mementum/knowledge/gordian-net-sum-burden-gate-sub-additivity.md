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
- A1 (the **target** unit's own lcc-total) PASSED strongly: `6.0154 -> 5.5499` (−7.7%).

## The genuine intent, correctly expressed

- **Reduce the target unit's local burden** → already captured by **A1** (per-unit
  decrease), which passes when the refactor is real.
- **Don't just relocate the tangle into a new seam** (A2's real purpose) → express as
  *"each extracted seam is strictly simpler than the residual target"*:
  `for-all s in (after-units \ before-units): after(s) < after(target)`.
  This rejects superficial/inverted extraction without penalising genuine decomplection.
  (In 214: seam lcc `0.8220 < target after 5.5499` → PASS.)
- **No architectural regression** → captured by **A3** (`gordian gate` new
  cycles/high/medium findings).

## Action for future sessions

1. **This is framework-level, not per-task.** The defect lives in whatever emits the A2
   criterion (the `reduce-incidental-complexity` workflow / `task-design` template /
   `incidental-complexity-finder` handoff). Fix it once at the source so every future
   incidental-complexity task emits the corrected A2 ("each seam simpler than residual
   target"), not the net-sum form. Until then, expect every such task to hit this wall.
2. **Do not silently rewrite A2 per-task to self-close.** Task 214 redefined A2 in its
   own `design.md` and self-closed; the math is sound but rewriting an acceptance bar is
   an autonomous change to the contract that needs human ratification. Surface it; prefer
   fixing the emitter over per-task redefinition.
3. **A1 + A3 already cover the genuine goals.** If forced to choose, trust A1 (target
   burden down) + A3 (no architectural regression); treat the net-sum A2 as known-broken.

## Status / ratification

The task-214 in-place A2 redefinition is **provisional, pending human ratification** —
a human may ratify the redefinition or revert `design.md`'s A2 and reopen task 214. The
framework-level fix (correcting the emitted criterion) is a **separate, un-filed** action
that still needs routing to the gordian / reduce-incidental-complexity-workflow /
task-design owner.
