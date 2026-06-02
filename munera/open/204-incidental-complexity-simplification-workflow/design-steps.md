# Design review follow-up steps

## Architecture fit

- [x] Specify the step-1 → step-2 handoff using the verified workflow-grammar
      delegate-yield mechanism. The design must state that step-2 sources its
      `task-lifecycle` `:input` from step-1's yielded text via
      `:prompt-string {:type :map :fields {:input {:from {:step "<select-step-name>" :yield :text}}}}`,
      consistent with the `gh-issue-implement.edn` precedent and the fact that
      `task-lifecycle` sub-workflows read `{:from :workflow-input :path [:input]}`
      (a map `{:input "munera/open/NNN-slug"}`). This closes a `one_way` /
      grammar-conformance gap the design itself raises to "Verified facts".

## Ambiguity (pass 1)

- [x] A1 — Define the join's unmatched-row rule. The selector recipe joins
      `local` and `complexity` on `(ns, var, arity)`; specify what happens when a
      unit appears in only one lens (drop, treat `cc=1`, or exclude) so
      `gap = lcc-total / max(cc, 1)` cannot silently inflate gap from a missing
      `cc` row. Required for the "reproducible, embedded verbatim" recipe claim.

- [x] A2 — Define "touched units" for the net-burden acceptance. Specify whether
      the "net `lcc-total` across all touched units decreased" check covers units
      in touched files, units whose source changed, or all units whose recomputed
      `local` burden changed (refactors can shift `dependency`/`working-set`
      burden into untouched callers). Make the acceptance objectively checkable.

- [x] A3 — Specify the `gordian gate` flags that enforce the claimed semantics.
      The acceptance/Locked-decision-4 claim "no new cycles, no new high/medium
      findings" is not enforced by bare `gate --baseline`; state the
      `--fail-on new-cycles,new-high-findings` (and `--max-new-medium-findings`)
      flags the generated task uses, or weaken the claim to match the command.

- [x] A4 — Resolve the baseline path against the worktree cwd. Baselines are
      stored in the task directory but `gate --baseline before-diagnose.edn` (bare
      relative path) is run from the worktree root during Phase 1. Specify how the
      generated task references the baseline (absolute, task-dir-relative path, or
      copied into cwd) so the gate resolves.

- [x] A5 — Name the before/after comparison source for "decreased". The
      acceptance re-runs `local --json` and asserts `lcc-total` "decreased"
      without naming the baseline it compares against (stored `before-local.json`,
      selector evidence, or fresh recompute). State the comparison source so the
      objective check is well-defined.

## Inconsistency (pass 1)

- [ ] I1 — Reconcile the worktree-inheritance claim with its cited precedent.
      "Verified facts" claims a `:delegate` step inherits the worktree set by a
      prior `:session` `work-on` call, citing `implement-task-in-worktree.md`,
      and Step 2 calls the `task-lifecycle` delegate's inheritance "verified
      behaviour." But that precedent shows inheritance happening INSIDE a
      worktree-resolving wrapper (`resolve-worktree` `:session` re-calls
      `work-on` from a `worktree_path:` handoff field) before it delegates to
      `implement-task` — not from a sibling step in the outer workflow. Task 204
      delegates DIRECTLY to `task-lifecycle` (no `resolve-worktree`/`work-on`
      sub-step; sub-workflows read only `{:input <task-path>}`) and step-1 emits
      ONLY the bare task path (no `worktree_path:` threaded). Resolve by one of:
      (a) delegate to a worktree-resolving wrapper and thread `worktree_path:`
      through step-1 output (reconciling with the "emit only the task path"
      constraint); (b) cite the actual mechanism that lets a fresh `:delegate`
      to `task-lifecycle` execute in step-1's worktree; or (c) weaken the
      "verified behaviour" claim to a stated open risk.
