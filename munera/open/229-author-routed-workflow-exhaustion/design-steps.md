# 229 — Design/plan review follow-up steps

Actionable follow-ups raised by review passes. Tick when resolved in
plan.md / design.md (steps.md is read-only review context).

## Ambiguity review

- [ ] **Terminal-yield resolution for two summary steps is underspecified
      (Slice 2/3, DI-1).** DI-1 makes both `final-summary` and
      `final-summary-not-converged` explicitly terminal, which fixes internal
      `:next` fall-through — but it does not specify (a) the *relative order* of
      the two summary steps in `:steps`, nor (b) how each consumer resolves the
      workflow's terminal `:yield :text` to the **executed** summary. Two code
      paths diverge: the lifecycle **delegate gate** path
      (`statechart_runtime/delegate.clj` → `terminal_contract/terminal-result-envelope`)
      prefers `:terminal-outcome :result-envelope` and reads the *actually-executed*
      terminal step (works regardless of order), but the **standalone `/delegate`
      result-text** path (`agent_session/mutations/canonical_workflows.clj`, and
      `terminal_contract/terminal-yielded-text`) keys strictly off
      `(last (:step-order …))`. If `final-summary-not-converged` is appended last,
      a *converged* standalone run surfaces the never-run not-converged step's
      empty text — contradicting D5 ("standalone output accepted as useful").
      Resolve in plan.md: specify summary-step ordering and state which
      resolution path each consumer (lifecycle gate vs standalone `/delegate`)
      uses, and confirm the converged path surfaces the converged summary's
      `PASS_STATUS: REVIEW_COMPLETE` text in *both* paths. Add a test that locks
      the converged standalone result text (not just definition-level routing).

- [ ] **`N follow-up iterations` source unspecified (Slice 2/3 not-converged
      summaries).** The template is to say "design/plan review did not converge
      after N follow-up iterations", but no contribution/source for `N` is
      defined and the not-converged summary step's contributions list does not
      include an iteration count. Decide and record in plan.md: emit the literal
      `:max-iterations` cap (e.g. 3 / 5), source an actual count if one is
      available, or drop the count from the template wording.
