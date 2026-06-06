# Design review follow-up steps

## Architectural fit

- [x] Resolve A2/A3 enforcement asymmetry: specify how A2a/A2b are mechanically
      computed (concrete command/check over `before-local.json` + after
      `local --json`) so A2 is an enforceable gate like A3, or explicitly justify
      leaving A2 as an agent-evaluated prose criterion.
- [x] Justify (or remove) the tunable margins θ (and optional ε): explain why a
      non-zero buffer is architecturally necessary vs. a pure inequality, given the
      design's own "no undefined buffer" / one-way objective-gate posture.
- [x] Reconcile the criterion taxonomy with the live emitter: the emitted contract
      labels target reduction **A5**, net burden **A2**, gate **A3** (no **A1**, no
      **A4**, blast radius unnumbered). Either bring the renumbering into scope or
      re-anchor the A2-rescoping rationale and acceptance criterion 2 to the actual
      labels so the emitted A1–A5 contract is self-consistent.

## Ambiguities

- [ ] Define the `(ns, var, arity)` join semantics for the non-unique-key case in
      "How A2 is mechanically checked" step 3. The line-insensitive key is non-unique
      in the live data (51 `dispatch-effects/execute-effect!` defmethods share it), so
      the many-to-many join is undefined when such a unit is touched (which
      `before`/`after` value; aggregate vs. per-line vs. exclude). Either specify the
      aggregation/fallback rule or qualify the "A2's units are distinct vars/arities"
      assertion to the units A2 can actually encounter.
- [ ] Fix the phantom "`reduce-incidental-complexity` skill" reference in
      Scope / blast radius. No such skill exists (only the
      `incidental-complexity-finder` skill + the workflow EDN already being edited).
      Drop the phantom skill reference or, if a distinct skill restatement is intended,
      identify it by concrete path.
- [x] Define the change-detection aggregation in "How A2 is mechanically checked"
      step 4. It forms `T` via "per-key **aggregate** before-burden ≠ aggregate
      after-burden," but "aggregate" is undefined and is a second before-side quantity
      that contradicts step 3's "`before-max` is the only before-side quantity A2
      uses." If aggregate = sum it re-imports the sub-additive sum the redesign
      eliminates into row-selection. Specify the aggregation function (or detect change
      per physical row) and reconcile it with the "only before-side quantity" claim.
- [x] Specify how the target is excluded from `T` in A2's line-insensitive keyspace.
      A5 keys the target line-bearing; A2 groups line-insensitive `(ns, var, arity)`.
      Step 4's "Remove the target's own row(s) from `T`" is ambiguous when the target
      shares its key with siblings (the 51-row defmethod case): target physical row
      only, or whole key group? Removing the group would exempt a relocation into a
      key-sharing sibling — a hole in the relocation guard. State that only the
      target's physical (line-bearing) row is removed, the behaviour when its
      line-insensitive key is shared, and why "(s)" is plural for a single defunit.

## Inconsistencies

- [x] Reconcile the adopted A2 form with the cited knowledge page. `design.md` adopts
      `after(n/m) < B`, `B := before(target)` (committed `before-local.json`), and
      rejects `after(target)`; the `active` knowledge page
      (`gordian-net-sum-burden-gate-sub-additivity.md`) proposes the opposite
      `after(s) < after(target)` and labels target reduction **A1**. Scope item 3 only
      says to record "the fix has landed", leaving the knowledge page documenting a
      different gate (`after(target)`, A1) than what landed (`B`, A5). Extend Scope
      item 3 to reconcile the knowledge page's proposed formula and A1 labeling with the
      adopted `after < B` / A5 form (or explicitly mark the `after(target)` form
      superseded by `B`).
- [x] Reconcile the conflicting definition of "unit". "Proposed corrected A2" says
      "Units are identified by the line-insensitive key `(ns, var, arity)`" and states
      A2a/A2b via per-unit `before(n)`/`before(m)`/`after(m)`; "How A2 is mechanically
      checked" says "A2's atomic unit is the physical defunit row … key used only to
      pair … never to merge", using group `before-max(k)` and per-row `after(u)`. The
      two framings contradict (key-as-unit vs row-as-unit), leaving `before(m)`/`after(m)`
      undefined for the 51-row defmethod case. Restate A2a/A2b over physical-row
      `after(u)` and group `before-max(k)` so both sections share one notion of "unit".
      (Distinct from the open join-semantics ambiguity item, which concerns the join
      procedure rather than the formal A2a/A2b notation.)
