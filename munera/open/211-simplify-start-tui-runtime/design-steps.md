# Design follow-up steps

## Ambiguity review (ψ)

- [ ] B1 — Specify how A2/A4 compare Gordian local units when refactoring changes line-based keys. The design keys units by `(ns, var, arity, line)` and pins the target as `(psi.app-runtime, start-tui-runtime!, 5, 603)`, but local decomplecting may insert or extract helpers and move the `defn`, causing the after `bb gordian local --json` run to lack the old line key and/or include new helper keys with no baseline row. Define whether implementation must preserve the target line, whether the target is re-identified by stable `(ns, var, arity)` plus old-line provenance, and how A2 treats added/deleted/missing units when summing before/after `lcc-total`. Update A2/A4 so the burden gates have one executable interpretation.
